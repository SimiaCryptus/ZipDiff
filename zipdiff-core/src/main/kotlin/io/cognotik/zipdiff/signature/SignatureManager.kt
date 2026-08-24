package io.cognotik.zipdiff.signature

import io.cognotik.zipdiff.exception.SignatureValidationException
import io.cognotik.zipdiff.exception.ZipdiffException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages extraction and application of signature blocks in canonical ZIP archives.
 */
class SignatureManager {

    /**
     * Extracts pre-computed signature block structures from a signed target canonical archive.
     *
     * @param canonicalZip Path to the canonical ZIP archive.
     * @param schemeId Identifier of the signature scheme to extract.
     * @return Extracted [SignatureBlock].
     * @throws IllegalArgumentException if the signature data cannot be found.
     */
    fun generateSignatureBlock(canonicalZip: Path, schemeId: String): SignatureBlock {
        val targetZipHash = computeSha256(canonicalZip)

        var profileVersion = "1.0"
        var placementRule = PlacementRule.META_INF_ENTRY
        var signatureData: ByteArray? = null
        var customHashFromMetadata: String? = null

        val metadataJson = readZipEntry(canonicalZip, "META-INF/signature-schemes.json")
        if (metadataJson != null) {
            val metadata = parseMetadata(String(metadataJson, Charsets.UTF_8))
            val schemeEntry = metadata.schemes.firstOrNull { it.schemeId == schemeId }
            if (schemeEntry != null) {
                profileVersion = schemeEntry.canonicalProfileVersion
                placementRule = schemeEntry.placementRule
                customHashFromMetadata = schemeEntry.targetZipHash
            } else if (metadata.schemeId == schemeId) {
                profileVersion = metadata.canonicalProfileVersion
                placementRule = metadata.placementRule
                customHashFromMetadata = metadata.targetZipHash
            }
        }

        when (placementRule) {
            PlacementRule.META_INF_ENTRY -> {
                signatureData = readZipEntry(canonicalZip, "META-INF/$schemeId.sig")
                    ?: readZipEntry(canonicalZip, "META-INF/$schemeId.bin")
                    ?: readZipEntry(canonicalZip, "META-INF/$schemeId")
                    ?: readZipEntry(canonicalZip, "META-INF/SIGNATURE.SIG")
                    ?: readZipEntry(canonicalZip, "META-INF/signature.sig")
            }
            PlacementRule.CENTRAL_DIRECTORY_COMMENT -> {
                val comment = getZipComment(canonicalZip)
                if (!comment.isNullOrEmpty()) {
                    signatureData = comment.toByteArray(Charsets.UTF_8)
                }
            }
            PlacementRule.EXTRA_FIELD -> {
                signatureData = readZipEntry(canonicalZip, "META-INF/$schemeId.extra")
                    ?: readZipEntry(canonicalZip, "META-INF/extra_fields.bin")
            }
            PlacementRule.DEDICATED_SECTION -> {
                signatureData = readZipEntry(canonicalZip, "META-INF/$schemeId.dedicated")
                    ?: readZipEntry(canonicalZip, "META-INF/dedicated_section.bin")
            }
        }

        if (signatureData == null) {
            signatureData = readZipEntry(canonicalZip, "META-INF/$schemeId.sig")
                ?: readZipEntry(canonicalZip, "META-INF/$schemeId")
        }

        if (signatureData == null) {
            throw IllegalArgumentException("Signature data for scheme '$schemeId' not found in $canonicalZip")
        }

        return SignatureBlock(
            schemeId = schemeId,
            canonicalProfileVersion = profileVersion,
            targetZipHash = customHashFromMetadata ?: targetZipHash,
            signatureData = signatureData,
            placementRule = placementRule
        )
    }

    /**
     * Mechanically inserts the signature block into a reconstructed canonical ZIP archive.
     * Validates that canonicalZip SHA-256 hash matches block.targetZipHash prior to insertion.
     *
     * @param canonicalZip Path to the reconstructed canonical ZIP archive.
     * @param block The [SignatureBlock] to apply.
     * @return Path to the updated ZIP archive.
     * @throws ZipdiffException.SignatureValidationException if checksum mismatch occurs.
     */
    fun applySignatureBlock(canonicalZip: Path, block: SignatureBlock): Path {
        val currentHash = computeSha256(canonicalZip)
        if (!currentHash.equals(block.targetZipHash, ignoreCase = true)) {
            throw SignatureValidationException(
                "Checksum mismatch for $canonicalZip: expected ${block.targetZipHash}, got $currentHash"
            )
        }

        val tempFile = Files.createTempFile("signed_canonical_", ".zip")
        try {
            val entriesToSkip = mutableSetOf<String>()
            val sigFileName = "META-INF/${block.schemeId}.sig"
            entriesToSkip.add(sigFileName)
            entriesToSkip.add("META-INF/signature-schemes.json")

            when (block.placementRule) {
                PlacementRule.EXTRA_FIELD -> entriesToSkip.add("META-INF/${block.schemeId}.extra")
                PlacementRule.DEDICATED_SECTION -> entriesToSkip.add("META-INF/${block.schemeId}.dedicated")
                else -> {}
            }

            val existingMetadataRaw = readZipEntry(canonicalZip, "META-INF/signature-schemes.json")
            val existingMetadata = existingMetadataRaw?.let { parseMetadata(String(it, Charsets.UTF_8)) }

            val updatedSchemes = mutableListOf<SignatureSchemeEntry>()
            existingMetadata?.schemes?.filterTo(updatedSchemes) { it.schemeId != block.schemeId }
            updatedSchemes.add(
                SignatureSchemeEntry(
                    schemeId = block.schemeId,
                    canonicalProfileVersion = block.canonicalProfileVersion,
                    placementRule = block.placementRule,
                    targetZipHash = block.targetZipHash
                )
            )

            // Use the primary constructor: the convenience accessors (schemeId,
            // canonicalProfileVersion, placementRule, targetZipHash) are derived
            // from the first entry of `schemes`, so make sure this block's entry
            // is the primary one.
            val orderedSchemes = updatedSchemes
                .sortedByDescending { it.schemeId == block.schemeId }
            val updatedMetadata = SignatureMetadata(
                version = existingMetadata?.version ?: "1.0",
                schemes = orderedSchemes,
                attributes = existingMetadata?.attributes ?: emptyMap()
            )

            ZipInputStream(Files.newInputStream(canonicalZip)).use { zis ->
                ZipOutputStream(Files.newOutputStream(tempFile)).use { zos ->
                    if (block.placementRule == PlacementRule.CENTRAL_DIRECTORY_COMMENT) {
                        zos.setComment(String(block.signatureData, Charsets.UTF_8))
                    }

                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name !in entriesToSkip) {
                            val newEntry = ZipEntry(entry.name)
                            if (entry.time != -1L) newEntry.time = entry.time
                            zos.putNextEntry(newEntry)
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                        entry = zis.nextEntry
                    }

                    val jsonBytes = serializeMetadata(updatedMetadata).toByteArray(Charsets.UTF_8)
                    zos.putNextEntry(ZipEntry("META-INF/signature-schemes.json"))
                    zos.write(jsonBytes)
                    zos.closeEntry()

                    when (block.placementRule) {
                        PlacementRule.META_INF_ENTRY -> {
                            zos.putNextEntry(ZipEntry(sigFileName))
                            zos.write(block.signatureData)
                            zos.closeEntry()
                        }
                        PlacementRule.EXTRA_FIELD -> {
                            zos.putNextEntry(ZipEntry("META-INF/${block.schemeId}.extra"))
                            zos.write(block.signatureData)
                            zos.closeEntry()
                        }
                        PlacementRule.DEDICATED_SECTION -> {
                            zos.putNextEntry(ZipEntry("META-INF/${block.schemeId}.dedicated"))
                            zos.write(block.signatureData)
                            zos.closeEntry()
                        }
                        PlacementRule.CENTRAL_DIRECTORY_COMMENT -> {
                            // Handled via zos.setComment
                        }
                    }
                }
            }

            Files.move(tempFile, canonicalZip, StandardCopyOption.REPLACE_EXISTING)
            return canonicalZip
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            throw e
        }
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

    private fun readZipEntry(zipPath: Path, entryName: String): ByteArray? {
        ZipFile(zipPath.toFile()).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: return null
            zipFile.getInputStream(entry).use { input ->
                return input.readBytes()
            }
        }
    }

    private fun getZipComment(zipPath: Path): String? {
        ZipFile(zipPath.toFile()).use { zipFile ->
            return zipFile.comment
        }
    }

    private fun parseMetadata(json: String): SignatureMetadata {
        val schemeId = extractJsonValue(json, "schemeId") ?: ""
        val canonicalProfileVersion = extractJsonValue(json, "canonicalProfileVersion") ?: ""
        val placementRuleStr = extractJsonValue(json, "placementRule") ?: PlacementRule.META_INF_ENTRY.name
        val placementRule = try {
            PlacementRule.valueOf(placementRuleStr)
        } catch (_: Exception) {
            PlacementRule.META_INF_ENTRY
        }
        val targetZipHash = extractJsonValue(json, "targetZipHash")
        val version = extractJsonValue(json, "version") ?: "1.0"


        val parsedSchemes = parseSchemesList(json)
        val schemes = when {
            parsedSchemes.isNotEmpty() -> parsedSchemes
            schemeId.isNotEmpty() -> listOf(
                SignatureSchemeEntry(
                    schemeId = schemeId,
                    canonicalProfileVersion = canonicalProfileVersion,
                    placementRule = placementRule,
                    targetZipHash = targetZipHash
                )
            )
            else -> emptyList()
        }

        return SignatureMetadata(
            version = version,
            schemes = schemes
        )
    }

    private fun serializeMetadata(metadata: SignatureMetadata): String {
        val schemesJson = metadata.schemes.joinToString(",", "[", "]") { scheme ->
            val schemeHash = scheme.targetZipHash
            val targetHash = if (schemeHash != null) ""","targetZipHash":"${escapeJson(schemeHash)}"""" else ""
            """{"schemeId":"${escapeJson(scheme.schemeId)}","canonicalProfileVersion":"${escapeJson(scheme.canonicalProfileVersion)}","placementRule":"${scheme.placementRule.name}"$targetHash}"""
        }
        val metadataHash = metadata.targetZipHash
        val targetZipHashStr = if (metadataHash != null) ""","targetZipHash":"${escapeJson(metadataHash)}"""" else ""
        return """{"schemeId":"${escapeJson(metadata.schemeId)}","canonicalProfileVersion":"${escapeJson(metadata.canonicalProfileVersion)}","placementRule":"${metadata.placementRule.name}"$targetZipHashStr,"version":"${escapeJson(metadata.version)}","schemes":$schemesJson}"""
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun parseSchemesList(json: String): List<SignatureSchemeEntry> {
        val schemesIndex = json.indexOf("\"schemes\"")
        if (schemesIndex == -1) return emptyList()
        val bracketStart = json.indexOf('[', schemesIndex)
        if (bracketStart == -1) return emptyList()
        val bracketEnd = json.indexOf(']', bracketStart)
        if (bracketEnd == -1) return emptyList()

        val arrayContent = json.substring(bracketStart + 1, bracketEnd)
        val objectRegex = Regex("""\{[^}]*\}""")
        return objectRegex.findAll(arrayContent).mapNotNull { match ->
            val obj = match.value
            val sId = extractJsonValue(obj, "schemeId") ?: return@mapNotNull null
            val profileVer = extractJsonValue(obj, "canonicalProfileVersion") ?: "1.0"
            val ruleStr = extractJsonValue(obj, "placementRule") ?: PlacementRule.META_INF_ENTRY.name
            val rule = try { PlacementRule.valueOf(ruleStr) } catch (_: Exception) { PlacementRule.META_INF_ENTRY }
            val hash = extractJsonValue(obj, "targetZipHash")
            SignatureSchemeEntry(sId, profileVer, rule, hash)
        }.toList()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }
}