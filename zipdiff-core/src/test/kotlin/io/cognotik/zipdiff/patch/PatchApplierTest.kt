package io.cognotik.zipdiff.patch

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.diff.DiffEntry
import io.cognotik.zipdiff.diff.DiffGenerator
import io.cognotik.zipdiff.diff.EntryMode
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.`package`.PatchMetadata
import io.cognotik.zipdiff.`package`.PatchPackage
import io.cognotik.zipdiff.testutil.TestZipUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PatchApplierTest {

    @Test
    fun `applies full diff and reconstructs matching canonical archive`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        val targetZip = tempDir.resolve("target.zip")

        TestZipUtils.createZip(
            baseZip,
            mapOf(
                "unchanged.txt" to "same".toByteArray(),
                "modified.txt" to "old content".toByteArray(),
                "deleted.txt" to "gone".toByteArray()
            )
        )
        TestZipUtils.createZip(
            targetZip,
            mapOf(
                "unchanged.txt" to "same".toByteArray(),
                "modified.txt" to "brand new content!".toByteArray(),
                "new.txt" to "new file content".toByteArray(),
                "empty.txt" to ByteArray(0)
            )
        )

        val canonicalBase = tempDir.resolve("canonical-base.zip")
        val canonicalTarget = tempDir.resolve("canonical-target.zip")
        val profile = CanonicalProfile()
        Canonicalizer().canonicalize(baseZip, canonicalBase, profile)
        val targetResult = Canonicalizer().canonicalize(targetZip, canonicalTarget, profile)

        val diffs = DiffGenerator.generateDiff(canonicalBase, canonicalTarget, profile)

        val metadata = PatchMetadata(
            baseVersion = "1.0",
            targetVersion = "2.0",
            canonicalizationProfileVersion = "1.0",
            canonicalZipSha256 = targetResult.sha256Hex
        )
        val patchPackage = PatchPackage(metadata, diffs)

        val outputZip = tempDir.resolve("output.zip")
        val result = PatchApplier().applyPatch(canonicalBase, patchPackage, outputZip)

        assertEquals(targetResult.sha256Hex, TestZipUtils.sha256(result))
        assertArrayEquals("brand new content!".toByteArray(), TestZipUtils.readZipEntry(result, "modified.txt"))
        assertArrayEquals("new file content".toByteArray(), TestZipUtils.readZipEntry(result, "new.txt"))
        assertNull(TestZipUtils.readZipEntry(result, "deleted.txt"))
        assertArrayEquals(ByteArray(0), TestZipUtils.readZipEntry(result, "empty.txt"))
    }

    @Test
    fun `throws ZipdiffException on sha256 mismatch`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        TestZipUtils.createZip(baseZip, mapOf("file.txt" to "content".toByteArray()))

        val diffs = listOf(DiffEntry("file.txt", EntryMode.UNCHANGED, null))
        val metadata = PatchMetadata(
            baseVersion = "1.0",
            targetVersion = "2.0",
            canonicalizationProfileVersion = "1.0",
            canonicalZipSha256 = "0".repeat(64)
        )
        val patchPackage = PatchPackage(metadata, diffs)
        val outputZip = tempDir.resolve("output.zip")

        assertThrows(ZipdiffException::class.java) {
            PatchApplier().applyPatch(baseZip, patchPackage, outputZip)
        }
        assertFalse(java.nio.file.Files.exists(outputZip))
    }

    @Test
    fun `applies NEW entry with raw uncompressed payload matching expected canonical hash`(@TempDir tempDir: Path) {
        val baseSource = tempDir.resolve("base-source.zip")
        TestZipUtils.createZip(baseSource, mapOf("existing.txt" to "keep me".toByteArray()))

        val canonicalBase = tempDir.resolve("canonical-base.zip")
        Canonicalizer().canonicalize(baseSource, canonicalBase)

        val diffs = listOf(
            DiffEntry("existing.txt", EntryMode.UNCHANGED, null),
            DiffEntry("added.txt", EntryMode.NEW, "totally new".toByteArray())
        )

        val expectedTargetSource = tempDir.resolve("expected-source.zip")
        TestZipUtils.createZip(
            expectedTargetSource,
            mapOf("existing.txt" to "keep me".toByteArray(), "added.txt" to "totally new".toByteArray())
        )
        val expectedCanonical = tempDir.resolve("expected-canonical.zip")
        val expectedResult = Canonicalizer().canonicalize(expectedTargetSource, expectedCanonical)

        val metadata = PatchMetadata(
            baseVersion = "1.0",
            targetVersion = "2.0",
            canonicalizationProfileVersion = "1.0",
            canonicalZipSha256 = expectedResult.sha256Hex
        )
        val patchPackage = PatchPackage(metadata, diffs)
        val outputZip = tempDir.resolve("output.zip")

        val result = PatchApplier().applyPatch(canonicalBase, patchPackage, outputZip)
        assertEquals(expectedResult.sha256Hex, TestZipUtils.sha256(result))
    }
}