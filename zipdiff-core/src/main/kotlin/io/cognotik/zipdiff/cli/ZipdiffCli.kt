package io.cognotik.zipdiff.cli

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.diff.DiffGenerator
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.`package`.PatchMetadata
import io.cognotik.zipdiff.`package`.PatchPackager
import io.cognotik.zipdiff.patch.PatchApplier
import io.cognotik.zipdiff.patch.PatchChainApplier
import io.cognotik.zipdiff.signature.PlacementRule
import io.cognotik.zipdiff.signature.SignatureBlock
import io.cognotik.zipdiff.signature.SignatureManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Command-line entry point for the zipdiff library.
 *
 * Supported subcommands:
 *   canonicalize   Produce a deterministic canonical ZIP archive.
 *   diff           Generate a patch package (.patch.zp) between two ZIP archives.
 *   apply          Apply a single patch package to a base ZIP archive.
 *   apply-chain    Apply a sequence of patch packages to a base ZIP archive.
 *   sign-extract   Extract a signature block from a signed canonical ZIP archive.
 *   sign-apply     Apply an externally supplied signature block to a canonical ZIP archive.
 */
object ZipdiffCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printUsage()
            exitProcess(1)
        }

        val command = args[0]
        val rest = args.drop(1)

        try {
            when (command) {
                "canonicalize" -> runCanonicalize(rest)
                "diff" -> runDiff(rest)
                "apply" -> runApply(rest)
                "apply-chain" -> runApplyChain(rest)
                "sign-extract" -> runSignExtract(rest)
                "sign-apply" -> runSignApply(rest)
                "help", "-h", "--help" -> printUsage()
                else -> {
                    System.err.println("Unknown command: $command")
                    printUsage()
                    exitProcess(1)
                }
            }
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            exitProcess(2)
        } catch (e: ZipdiffException) {
            System.err.println("Error: ${e.message}")
            exitProcess(3)
        } catch (e: Exception) {
            System.err.println("Unexpected error: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun printUsage() {
        println(
            """
            zipdiff - ZIP canonicalization, diffing, and patching CLI

            Usage:
              zipdiff canonicalize <input.zip> <output.zip> [options]
                  --level N                    Compression level (-1 or 0..9, default 9)
                  --timestamp SECONDS           Normalized entry timestamp in epoch seconds (default 0)
                  --no-normalize-permissions    Do not normalize POSIX permissions
                  --preserve-stored             Keep STORED entries as STORED

              zipdiff diff <base.zip> <target.zip> <output.patch.zp> [options]
                  --base-version V              Base version identifier (default "unknown")
                  --target-version V             Target version identifier (default "unknown")
                  --profile-version V            Canonicalization profile version (default "1.0")
                  --level N                      Compression level for canonicalization/diff payloads
                  --no-canonicalize              Treat base/target as already canonical
                  --sign-scheme ID                Extract and embed a signature block for the given scheme id
                                                   (requires the target archive to already contain the signature)

              zipdiff apply <base.zip> <patch.zp> <output.zip>

              zipdiff apply-chain <base.zip> <output.zip> <patch1.zp> [patch2.zp ...]

              zipdiff sign-extract <canonical.zip> <schemeId> [--output-dir DIR]

              zipdiff sign-apply <canonical.zip> <signature-metadata.json> [--data FILE]

              zipdiff help
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------------
    // canonicalize
    // ---------------------------------------------------------------------

    private fun runCanonicalize(args: List<String>) {
        val (positional, options) = parseArgs(args)
        require(positional.size >= 2) {
            "Usage: canonicalize <input.zip> <output.zip> [--level N] [--timestamp SECONDS] " +
                "[--no-normalize-permissions] [--preserve-stored]"
        }

        val input = Paths.get(positional[0])
        val output = Paths.get(positional[1])

        val level = options["level"]?.toIntOrNull() ?: 9
        val timestamp = options["timestamp"]?.toLongOrNull() ?: 0L
        val normalizePermissions = !options.containsKey("no-normalize-permissions")
        val preserveStored = options.containsKey("preserve-stored")

        val profile = CanonicalProfile(
            compressionLevel = level,
            timestampEpochSeconds = timestamp,
            normalizePermissions = normalizePermissions,
            preserveStored = preserveStored
        )

        val result = Canonicalizer().canonicalize(input, output, profile)

        println("Canonicalized $input -> ${result.outputPath}")
        println("Entries: ${result.entryCount}")
        println("SHA-256: ${result.sha256Hex}")
    }

    // ---------------------------------------------------------------------
    // diff
    // ---------------------------------------------------------------------

    private fun runDiff(args: List<String>) {
        val (positional, options) = parseArgs(args)
        require(positional.size >= 3) {
            "Usage: diff <base.zip> <target.zip> <output.patch.zp> [--base-version V] " +
                "[--target-version V] [--profile-version V] [--level N] [--no-canonicalize] [--sign-scheme ID]"
        }

        val baseZip = Paths.get(positional[0])
        val targetZip = Paths.get(positional[1])
        val outputPath = Paths.get(positional[2])

        val level = options["level"]?.toIntOrNull() ?: 9
        val profileVersion = options["profile-version"] ?: "1.0"
        val baseVersion = options["base-version"] ?: "unknown"
        val targetVersion = options["target-version"] ?: "unknown"
        val skipCanonicalize = options.containsKey("no-canonicalize")
        val signSchemeId = options["sign-scheme"]

        val profile = CanonicalProfile(compressionLevel = level)
        val tempFiles = mutableListOf<Path>()

        try {
            val canonicalBase: Path
            val canonicalTarget: Path
            val targetSha256: String

            if (skipCanonicalize) {
                canonicalBase = baseZip
                canonicalTarget = targetZip
                targetSha256 = computeSha256(canonicalTarget)
            } else {
                val tempBase = Files.createTempFile("zipdiff-cli-base-", ".zip")
                val tempTarget = Files.createTempFile("zipdiff-cli-target-", ".zip")
                Files.deleteIfExists(tempBase)
                Files.deleteIfExists(tempTarget)
                tempFiles.add(tempBase)
                tempFiles.add(tempTarget)

                Canonicalizer().canonicalize(baseZip, tempBase, profile)
                val targetResult = Canonicalizer().canonicalize(targetZip, tempTarget, profile)

                canonicalBase = tempBase
                canonicalTarget = tempTarget
                targetSha256 = targetResult.sha256Hex
            }

            val diffEntries = DiffGenerator.generateDiff(canonicalBase, canonicalTarget, profile)

            val signatureBlocks = mutableListOf<SignatureBlock>()
            val signatureSchemes = mutableListOf<String>()
            if (signSchemeId != null) {
                val block = SignatureManager().generateSignatureBlock(canonicalTarget, signSchemeId)
                signatureBlocks.add(block)
                signatureSchemes.add(signSchemeId)
            }

            val metadata = PatchMetadata(
                baseVersion = baseVersion,
                targetVersion = targetVersion,
                canonicalizationProfileVersion = profileVersion,
                canonicalZipSha256 = targetSha256,
                signatureSchemes = signatureSchemes
            )

            PatchPackager.writePatchPackage(outputPath, metadata, diffEntries, signatureBlocks)

            println("Diff package written to $outputPath")
            println("Entries: ${diffEntries.size}")
            println("Canonical target SHA-256: $targetSha256")
        } finally {
            tempFiles.forEach { Files.deleteIfExists(it) }
        }
    }

    // ---------------------------------------------------------------------
    // apply / apply-chain
    // ---------------------------------------------------------------------

    private fun runApply(args: List<String>) {
        val (positional, _) = parseArgs(args)
        require(positional.size >= 3) { "Usage: apply <base.zip> <patch.zp> <output.zip>" }

        val baseZip = Paths.get(positional[0])
        val patchFile = Paths.get(positional[1])
        val outputPath = Paths.get(positional[2])

        val patchPackage = PatchPackager.readPatchPackage(patchFile)
        val result = PatchApplier().applyPatch(baseZip, patchPackage, outputPath)

        println("Patch applied. Output: $result")
    }

    private fun runApplyChain(args: List<String>) {
        val (positional, _) = parseArgs(args)
        require(positional.size >= 3) {
            "Usage: apply-chain <base.zip> <output.zip> <patch1.zp> [patch2.zp ...]"
        }

        val baseZip = Paths.get(positional[0])
        val outputPath = Paths.get(positional[1])
        val patchPaths = positional.drop(2).map { Paths.get(it) }
        val patchPackages = patchPaths.map { PatchPackager.readPatchPackage(it) }

        val result = PatchChainApplier().applyChain(baseZip, patchPackages, outputPath)

        println("Patch chain applied (${patchPackages.size} step(s)). Output: $result")
    }

    // ---------------------------------------------------------------------
    // sign-extract / sign-apply
    // ---------------------------------------------------------------------

    private fun runSignExtract(args: List<String>) {
        val (positional, options) = parseArgs(args)
        require(positional.size >= 2) {
            "Usage: sign-extract <canonical.zip> <schemeId> [--output-dir DIR]"
        }

        val canonicalZip = Paths.get(positional[0])
        val schemeId = positional[1]
        val outputDir = options["output-dir"]?.let { Paths.get(it) }
            ?: canonicalZip.toAbsolutePath().parent
            ?: Paths.get(".")

        Files.createDirectories(outputDir)

        val block = SignatureManager().generateSignatureBlock(canonicalZip, schemeId)

        val sigDataPath = outputDir.resolve("$schemeId.sig.bin")
        Files.write(sigDataPath, block.signatureData)

        val metadataPath = outputDir.resolve("$schemeId.sig.json")
        val metadataJson = buildString {
            append("{\n")
            append("  \"schemeId\": \"${escapeJson(block.schemeId)}\",\n")
            append("  \"canonicalProfileVersion\": \"${escapeJson(block.canonicalProfileVersion)}\",\n")
            append("  \"targetZipHash\": \"${escapeJson(block.targetZipHash)}\",\n")
            append("  \"placementRule\": \"${block.placementRule.name}\",\n")
            append("  \"signatureDataFile\": \"${escapeJson(sigDataPath.fileName.toString())}\"\n")
            append("}\n")
        }
        Files.write(metadataPath, metadataJson.toByteArray(Charsets.UTF_8))

        println("Signature block extracted for scheme '$schemeId'")
        println("Metadata: $metadataPath")
        println("Signature data: $sigDataPath")
    }

    private fun runSignApply(args: List<String>) {
        val (positional, options) = parseArgs(args)
        require(positional.size >= 2) {
            "Usage: sign-apply <canonical.zip> <signature-metadata.json> [--data FILE]"
        }

        val canonicalZip = Paths.get(positional[0])
        val metadataPath = Paths.get(positional[1])

        val metadataJson = String(Files.readAllBytes(metadataPath), Charsets.UTF_8)

        val schemeId = extractJsonValue(metadataJson, "schemeId")
            ?: throw IllegalArgumentException("Missing schemeId in $metadataPath")
        val canonicalProfileVersion = extractJsonValue(metadataJson, "canonicalProfileVersion") ?: "1.0"
        val targetZipHash = extractJsonValue(metadataJson, "targetZipHash")
            ?: throw IllegalArgumentException("Missing targetZipHash in $metadataPath")
        val placementRuleStr = extractJsonValue(metadataJson, "placementRule") ?: PlacementRule.META_INF_ENTRY.name
        val placementRule = try {
            PlacementRule.valueOf(placementRuleStr)
        } catch (_: Exception) {
            PlacementRule.META_INF_ENTRY
        }

        val dataFile = options["data"]?.let { Paths.get(it) }
            ?: extractJsonValue(metadataJson, "signatureDataFile")?.let {
                (metadataPath.toAbsolutePath().parent ?: Paths.get(".")).resolve(it)
            }
            ?: throw IllegalArgumentException(
                "Signature data file not specified (use --data or signatureDataFile field in metadata)"
            )

        val signatureData = Files.readAllBytes(dataFile)

        val block = SignatureBlock(
            schemeId = schemeId,
            canonicalProfileVersion = canonicalProfileVersion,
            targetZipHash = targetZipHash,
            signatureData = signatureData,
            placementRule = placementRule
        )

        val result = SignatureManager().applySignatureBlock(canonicalZip, block)
        println("Signature block for scheme '$schemeId' applied to $result")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun parseArgs(args: List<String>): Pair<List<String>, Map<String, String>> {
        val positional = mutableListOf<String>()
        val options = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val key = arg.removePrefix("--")
                if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    options[key] = args[i + 1]
                    i += 2
                } else {
                    options[key] = "true"
                    i += 1
                }
            } else {
                positional.add(arg)
                i += 1
            }
        }
        return positional to options
    }

    private fun computeSha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }
}