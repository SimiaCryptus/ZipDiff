# Zipdiff

**Zipdiff** is a JVM library and Gradle plugin for generating and applying compact, binary
**delta patches between ZIP archives**. It is designed for scenarios where you need to ship
incremental updates of ZIP-based artifacts (application bundles, asset packs, plugin archives,
APK-like containers, etc.) without redistributing the full archive on every release.

Zipdiff works by:

1. **Canonicalizing** ZIP archives into a deterministic, bit-for-bit reproducible form.
2. **Diffing** two canonical archives entry-by-entry, using DEFLATE preset-dictionary delta
   compression for changed files.
3. **Packaging** the diff into a self-describing `.patch.zp` archive, optionally carrying
   cryptographic signature material.
4. **Applying** the patch package to a base archive (or a chain of patches) to deterministically
   reconstruct the target canonical archive, with SHA-256 verification.

---

## Table of Contents

- [Why Zipdiff?](#why-zipdiff)
- [Modules](#modules)
- [Core Concepts](#core-concepts)
  - [Canonicalization](#canonicalization)
  - [Diff Generation](#diff-generation)
  - [Patch Packages (`.patch.zp`)](#patch-packages-patchzp)
  - [Patch Application](#patch-application)
  - [Patch Chains](#patch-chains)
  - [Signature Blocks](#signature-blocks)
- [Using the Core Library](#using-the-core-library)
- [Using the Gradle Plugin](#using-the-gradle-plugin)
- [Building From Source](#building-from-source)
- [Requirements](#requirements)
- [Project Layout](#project-layout)

---

## Why Zipdiff?

ZIP archives are notoriously unfriendly to generic binary-diff tools: reordering entries,
re-compressing unchanged bytes, or shifting timestamps/permissions can cause two functionally
identical archives to differ almost entirely at the byte level. Zipdiff avoids this problem by
**operating on the logical entry level** rather than the raw container bytes:

- Entries are diffed independently by path, so unrelated file changes don't perturb unrelated
  regions of the patch.
- A **canonicalization** pass first normalizes ordering, timestamps, permissions, and metadata so
  that two semantically-equal archives always produce byte-identical canonical output — which in
  turn makes patch generation and verification deterministic and reproducible.
- Changed files are delta-compressed against their previous version using a DEFLATE preset
  dictionary, so only the "new" information needs to be shipped when a file changes slightly.

---

## Modules

| Module            | Description                                                                 |
|--------------------|------------------------------------------------------------------------------|
| `zipdiff-core`     | Pure JVM/Kotlin library implementing canonicalization, diffing, packaging, patch application, and signature handling. No Gradle dependency. |
| `zipdiff-plugin`   | A Gradle plugin (`io.cognotik.zipdiff-patch`) that wires the core library into a build, exposing a `generateZipdiffPatch` task and a `zipdiff { ... }` DSL extension. |

---

## Core Concepts

### Canonicalization

Implemented by [`Canonicalizer`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/canonical/Canonicalizer.kt)
and configured via [`CanonicalProfile`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/canonical/CanonicalProfile.kt).

Given an arbitrary input ZIP, `Canonicalizer.canonicalize(...)` produces a new ZIP where:

- **Entries are sorted** lexicographically by name, guaranteeing a stable, order-independent
  layout.
- **Timestamps are normalized** to a fixed epoch (`timestampEpochSeconds`, default `0`), so
  builds performed at different times/machines produce identical bytes.
- **POSIX permissions are normalized** (optional, on by default) to either `0755` (directories /
  executables) or `0644` (regular files), removing environment-specific mode bits.
- **Extra fields and comments are stripped** from each entry to eliminate tool/OS-specific noise
  (e.g. Unicode path extra fields, Info-ZIP UT timestamps).
- Entries are re-compressed with **DEFLATE** at a configurable `compressionLevel` (directories are
  always `STORED`; optionally, previously-`STORED` entries can be preserved as `STORED` via
  `preserveStored`).

The result is returned as a [`CanonicalResult`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/canonical/CanonicalResult.kt),
which reports the number of entries processed and the **SHA-256 hash** of the canonical archive.
This hash is the basis for all downstream integrity checks (patch metadata, signature
verification).

### Diff Generation

Implemented by [`DiffGenerator`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/diff/DiffGenerator.kt).

`DiffGenerator.generateDiff(baseZip, targetZip, profile)` reads both (canonical) archives,
compares them entry-by-entry by normalized path, and produces a sorted list of
[`DiffEntry`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/diff/DiffEntry.kt) objects, each
tagged with an [`EntryMode`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/diff/DiffEntry.kt):

| Mode          | Meaning                                                                 |
|---------------|--------------------------------------------------------------------------|
| `UNCHANGED`   | Entry content is identical between base and target (no payload carried). |
| `NEW`         | Entry exists only in target (raw payload carried).                       |
| `MODIFIED`    | Entry exists in both but content differs (delta-compressed payload).     |
| `DELETED`     | Entry exists only in base (tombstone, no payload).                       |
| `EMPTY_FILE`  | Entry resolves to a zero-length file in the target.                      |

For `MODIFIED` entries, the target bytes are compressed via
[`DeflateDictionaryEngine`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/deflate/DeflateDictionaryEngine.kt)
using the **base entry's bytes as a preset DEFLATE dictionary** (`compressWithDict`). This lets
the compressor reference unchanged byte sequences from the old file, so small edits to large files
can produce very small patch payloads. If dictionary-based compression doesn't actually help (e.g.
for unrelated content), the engine automatically falls back to standard DEFLATE.

> DEFLATE preset dictionaries are limited to a 32 KB sliding window
> (`DeflateDictionaryEngine.MAX_DICT_SIZE`); for larger base files only the trailing 32 KB is used
> as the dictionary.

### Patch Packages (`.patch.zp`)

Implemented by [`PatchPackager`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/package/PatchPackager.kt),
with data model in [`PatchPackage.kt`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/package/PatchPackage.kt).

A patch package is itself a plain ZIP file (conventionally named `<base>-to-<target>.patch.zp`)
with the following internal layout:

```
META-INF/version.txt                 # baseVersion=..., targetVersion=...
META-INF/canonicalization.json       # canonicalizationProfileVersion used to build the patch
META-INF/canonical-zip.sha256        # expected SHA-256 of the reconstructed canonical target
META-INF/signature-schemes.json      # list of signature scheme identifiers included
META-INF/signatures.json             # (optional) serialized SignatureBlock list
META-INF/entries.json                # manifest describing each DIFF/ entry (path, mode, metadata)
DIFF/<path>                          # payload for NEW / MODIFIED / EMPTY_FILE entries
DIFF/<path>.tombstone                # zero-length marker for DELETED entries
```

- `PatchPackager.writePatchPackage(outputPath, metadata, diffs, signatures)` builds this structure
  from a [`PatchMetadata`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/package/PatchPackage.kt),
  a list of `DiffEntry`, and optional `SignatureBlock`s.
- `PatchPackager.readPatchPackage(patchPath)` parses a `.patch.zp` file back into a
  `PatchPackage` (metadata + diff entries + signature blocks), with a defensive fallback scanner
  that reconstructs the entry manifest directly from the `DIFF/` tree if `entries.json` is
  missing or unreadable.

### Patch Application

Implemented by [`PatchApplier`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/patch/PatchApplier.kt).

`PatchApplier.applyPatch(baseZip, patchPackage, outputPath)`:

1. Reads the base ZIP archive.
2. For every `DiffEntry` in the patch:
   - `DELETED` → entry is omitted from the reconstructed archive.
   - `EMPTY_FILE` → a zero-length entry is written.
   - `UNCHANGED` → the entry's bytes are streamed straight from the base archive.
   - `NEW` → the raw (or DEFLATE-dictionary-compressed) payload is decompressed and written.
   - `MODIFIED` → the corresponding base entry is used as the preset dictionary to decompress the
     delta payload, producing the new target bytes.
3. Any base entries not referenced by the patch are copied through unchanged.
4. The resulting archive is re-**canonicalized** (to guarantee it matches the exact canonical
   layout the patch was generated against).
5. The canonical result's SHA-256 is compared against `metadata.canonicalZipSha256`; a mismatch
   raises a `ZipdiffException`.
6. Any `SignatureBlock`s attached to the patch are applied to the final archive via
   `SignatureManager.applySignatureBlock`.

### Patch Chains

Implemented by [`PatchChainApplier`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/patch/PatchChainApplier.kt).

When upgrading across multiple versions (e.g. `v1 → v2 → v3`), `PatchChainApplier.applyChain`
sequentially applies an ordered list of `PatchPackage`s, using the output of each step as the
base for the next, and writing only the final result to the requested `outputPath` (intermediate
results are held in temp files that are cleaned up automatically). Errors at any step abort the
chain with a `ZipdiffException`, and the applier refuses to run if the base and output paths are
the same file.

### Signature Blocks

Implemented by [`SignatureManager`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/signature/SignatureManager.kt)
and [`SignatureBlock`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/signature/SignatureBlock.kt).

Zipdiff treats signing as an orthogonal, pluggable concern:

- `SignatureManager.generateSignatureBlock(canonicalZip, schemeId)` extracts pre-existing
  signature material from a **signed** canonical archive (e.g. produced by an external signing
  tool) — reading it from a `META-INF/<scheme>.sig` entry, the central directory comment, or a
  dedicated/extra-field location, depending on the archive's `META-INF/signature-schemes.json`
  manifest — and wraps it, together with the archive's SHA-256, into a `SignatureBlock`.
- `SignatureManager.applySignatureBlock(canonicalZip, block)` re-inserts that signature material
  into a freshly reconstructed canonical archive, **after first verifying** that the archive's
  SHA-256 matches `block.targetZipHash` (raising `SignatureValidationException` on mismatch). This
  lets a patch recipient deterministically reproduce a signed archive without ever needing access
  to the private signing key.

Four placement strategies are supported via [`PlacementRule`](zipdiff-core/src/main/kotlin/io/cognotik/zipdiff/signature/SignatureBlock.kt):
`META_INF_ENTRY`, `CENTRAL_DIRECTORY_COMMENT`, `EXTRA_FIELD`, and `DEDICATED_SECTION`.

---

## Using the Core Library

Add `zipdiff-core` as a dependency (published as `org.zipdiff:zipdiff-core`), then:

```kotlin
import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.diff.DiffGenerator
import io.cognotik.zipdiff.package.PatchMetadata
import io.cognotik.zipdiff.package.PatchPackager
import io.cognotik.zipdiff.patch.PatchApplier
import java.nio.file.Path

// 1. Canonicalize base and target archives
val canonicalizer = Canonicalizer()
val baseResult = canonicalizer.canonicalize(Path.of("base.zip"), Path.of("base.canonical.zip"))
val targetResult = canonicalizer.canonicalize(Path.of("target.zip"), Path.of("target.canonical.zip"))

// 2. Generate a logical diff
val diffs = DiffGenerator.generateDiff(
    Path.of("base.canonical.zip"),
    Path.of("target.canonical.zip"),
    profile = io.cognotik.zipdiff.canonical.CanonicalProfile()
)

// 3. Package the patch
val metadata = PatchMetadata(
    baseVersion = "1.0.0",
    targetVersion = "1.1.0",
    canonicalizationProfileVersion = "v1",
    canonicalZipSha256 = targetResult.sha256Hex
)
PatchPackager.writePatchPackage(Path.of("1.0.0-to-1.1.0.patch.zp"), metadata, diffs)

// 4. Apply the patch later, on the recipient side
val patchPackage = PatchPackager.readPatchPackage(Path.of("1.0.0-to-1.1.0.patch.zp"))
PatchApplier.applyPatch(Path.of("base.canonical.zip"), patchPackage, Path.of("reconstructed-target.zip"))
```

For multi-step upgrades, use `PatchChainApplier.applyChain(baseZip, listOf(patch1, patch2, ...), outputPath)`.

---

## Using the Gradle Plugin

Apply the plugin (`io.cognotik.zipdiff-patch`) in a project that wants to auto-generate a patch as
part of its build:

```kotlin
plugins {
    id("io.cognotik.zipdiff-patch")
}

zipdiff {
    baseArchive.set(layout.projectDirectory.file("releases/app-1.0.0.zip"))
    targetArchive.set(layout.projectDirectory.file("build/distributions/app-1.1.0.zip"))
    baseVersion.set("1.0.0")
    targetVersion.set("1.1.0")
    outputDirectory.set(layout.buildDirectory.dir("zipdiff"))

    // optional
    canonicalProfileVersion.set("v1")     // defaults to "v1"
    signatureScheme.set("scheme-v1")      // defaults to "scheme-v1"
    fallbackOnMissingBase.set(true)       // defaults to true
}
```

This registers a `generateZipdiffPatch` task that:

- Falls back to copying the full `targetArchive` into the output directory when `baseArchive` is
  missing (unless `fallbackOnMissingBase` is set to `false`, in which case the build fails).
- Otherwise canonicalizes both archives, generates the diff, attempts to extract a signature block
  for `signatureScheme` (skipped with a warning if unavailable), and writes
  `<baseVersion>-to-<targetVersion>.patch.zp` into `outputDirectory`.
- Is automatically wired to run before `assemble`/`build` whenever `baseArchive` or
  `targetArchive` is configured.

---

## Building From Source

This project uses the Gradle wrapper; no local Gradle installation is required.

```bash
./gradlew build      # compiles and tests both modules
./gradlew test        # runs zipdiff-core's unit tests
./gradlew publishToMavenLocal   # (if publishing is configured) install artifacts locally
```

## Requirements

- **JDK 21** — pinned via Gradle toolchains for both Java and Kotlin compilation
  (`JavaLanguageVersion.of(21)`, `JvmTarget.JVM_21`) across all subprojects.
- **Kotlin 2.2.20** (applied via the `kotlin("jvm")` plugin), chosen for compatibility with the
  Kotlin stdlib metadata (2.3.x) exposed on the Gradle 9.6 plugin classpath via `gradleApi()`.
- **Gradle 9.6** (via the included wrapper).

## Project Layout

```
zipdiff-core/
  src/main/kotlin/io/cognotik/zipdiff/
    canonical/   # CanonicalProfile, Canonicalizer, CanonicalResult
    deflate/     # DeflateDictionaryEngine (preset-dictionary DEFLATE compression)
    diff/        # DiffEntry, EntryMode, DiffGenerator
    exception/   # ZipdiffException, SignatureValidationException
    package/     # PatchMetadata, PatchPackage, PatchPackager (.patch.zp I/O)
    patch/       # PatchApplier, PatchChainApplier
    signature/   # SignatureBlock, SignatureMetadata, SignatureManager

zipdiff-plugin/
  src/main/kotlin/io/cognotik/zipdiff/plugin/
    ZipdiffExtension.kt     # `zipdiff { ... }` DSL
    ZipdiffPatchPlugin.kt   # Plugin entry point / task wiring
    ZipdiffPatchTask.kt     # `generateZipdiffPatch` task implementation
