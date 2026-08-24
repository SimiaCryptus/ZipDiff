package io.cognotik.zipdiff.`package`

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.cognotik.zipdiff.diff.DiffEntry
import io.cognotik.zipdiff.diff.EntryMode
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.signature.SignatureBlock
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Handles packaging and parsing of standard `.patch.zp` ZIP patch archives.
 */
object PatchPackager {

    private val objectMapper: ObjectMapper by lazy {
        jacksonObjectMapper()
    }

    /**
     * Packages metadata, diff entries, and signatures into a patch ZIP archive.
     *
     * @param outputPath Output path for the patch archive.
     * @param metadata Patch metadata information.
     * @param diffs List of logical diff entries.
     * @param signatures Optional list of signature blocks.
     */
    @JvmStatic
    fun writePatchPackage(
        outputPath: Path,
        metadata: PatchMetadata,
        diffs: List<DiffEntry>,
        signatures: List<SignatureBlock> = emptyList()
    ) {
        try {
            outputPath.parent?.let { Files.createDirectories(it) }

            ZipOutputStream(Files.newOutputStream(outputPath)).use { zos ->
                // Write META-INF/ directory entry
                zos.putNextEntry(ZipEntry("META-INF/"))
                zos.closeEntry()

                // Write META-INF/version.txt
                val versionTxt = "baseVersion=${metadata.baseVersion}\ntargetVersion=${metadata.targetVersion}\n"
                writeZipEntry(zos, "META-INF/version.txt", versionTxt.toByteArray(Charsets.UTF_8))

                // Write META-INF/canonicalization.json
                val canonicalizationMap = mapOf(
                    "canonicalizationProfileVersion" to metadata.canonicalizationProfileVersion,
                    "profileVersion" to metadata.canonicalizationProfileVersion
                )
                val canonicalizationJson = objectMapper.writeValueAsString(canonicalizationMap)
                writeZipEntry(zos, "META-INF/canonicalization.json", canonicalizationJson.toByteArray(Charsets.UTF_8))

                // Write META-INF/canonical-zip.sha256
                val sha256Content = metadata.canonicalZipSha256.trim() + "\n"
                writeZipEntry(zos, "META-INF/canonical-zip.sha256", sha256Content.toByteArray(Charsets.UTF_8))

                // Write META-INF/signature-schemes.json
                val schemesJson = objectMapper.writeValueAsString(metadata.signatureSchemes)
                writeZipEntry(zos, "META-INF/signature-schemes.json", schemesJson.toByteArray(Charsets.UTF_8))

                // Write META-INF/signatures.json if signature blocks exist
                if (signatures.isNotEmpty()) {
                    val signaturesJson = objectMapper.writeValueAsString(signatures)
                    writeZipEntry(zos, "META-INF/signatures.json", signaturesJson.toByteArray(Charsets.UTF_8))
                }

                // Write META-INF/entries.json for diff metadata manifest
                val entryManifest = diffs.map { entry ->
                    mapOf(
                        "path" to entry.path,
                        "mode" to entry.mode.name,
                        "hasPayload" to (entry.payloadBytes != null),
                        "metadata" to entry.metadata
                    )
                }
                val entriesJson = objectMapper.writeValueAsString(entryManifest)
                writeZipEntry(zos, "META-INF/entries.json", entriesJson.toByteArray(Charsets.UTF_8))

                // Write DIFF/ directory entry
                zos.putNextEntry(ZipEntry("DIFF/"))
                zos.closeEntry()

                // Write entry payloads and tombstone markers under DIFF/
                for (diff in diffs) {
                   val cleanPath = sanitizePath(diff.path)
                    val zipEntryPath = if (diff.mode == EntryMode.DELETED) {
                        "DIFF/$cleanPath.tombstone"
                    } else {
                        "DIFF/$cleanPath"
                    }
                    val payload = diff.payloadBytes ?: ByteArray(0)
                    writeZipEntry(zos, zipEntryPath, payload)
                }
            }
        } catch (e: ZipdiffException) {
            throw e
        } catch (e: Exception) {
            throw ZipdiffException("Failed to write patch package to $outputPath", e)
        }
    }

    /**
     * Reads a patch package archive and parses META-INF/ metadata and DIFF/ entries into a PatchPackage.
     *
     * @param patchPath Path to the patch archive file.
     * @return Reconstructed PatchPackage instance.
     * @throws ZipdiffException If required entries are missing or the archive is corrupted.
     */
    @JvmStatic
    fun readPatchPackage(patchPath: Path): PatchPackage {
        if (!Files.exists(patchPath) || !Files.isRegularFile(patchPath)) {
            throw ZipdiffException("Patch package file does not exist or is not a valid file: $patchPath")
        }

        val zipFile = try {
            ZipFile(patchPath.toFile())
        } catch (e: Exception) {
            throw ZipdiffException("Failed to open patch package archive or corrupted structure: $patchPath", e)
        }

        return zipFile.use { zip ->
            val metadata = readMetadata(zip)
           val signatures = readSignatures(zip)
            val diffEntries = readDiffEntries(zip)

            PatchPackage(
                metadata = metadata,
                diffEntries = diffEntries,
                signatureBlocks = signatures
            )
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName)
        entry.size = bytes.size.toLong()
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun getEntryBytes(zipFile: ZipFile, entryName: String): ByteArray? {
        val entry = zipFile.getEntry(entryName) ?: zipFile.getEntry(entryName.removePrefix("/")) ?: return null
        return zipFile.getInputStream(entry).use { it.readBytes() }
    }

    private fun getRequiredEntryBytes(zipFile: ZipFile, entryName: String): ByteArray {
        return getEntryBytes(zipFile, entryName)
            ?: throw ZipdiffException("Missing required entry in patch package: $entryName")
    }

    private fun readMetadata(zipFile: ZipFile): PatchMetadata {
        // Read version.txt
        val versionBytes = getRequiredEntryBytes(zipFile, "META-INF/version.txt")
        val versionStr = String(versionBytes, Charsets.UTF_8)
        var baseVersion: String? = null
        var targetVersion: String? = null

        val lines = versionStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.contains("=")) {
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (key.equals("baseVersion", ignoreCase = true)) baseVersion = value
                else if (key.equals("targetVersion", ignoreCase = true)) targetVersion = value
            } else if (line.contains(":")) {
                val key = line.substringBefore(":").trim()
                val value = line.substringAfter(":").trim()
                if (key.equals("baseVersion", ignoreCase = true)) baseVersion = value
                else if (key.equals("targetVersion", ignoreCase = true)) targetVersion = value
            }
        }
        if (baseVersion == null || targetVersion == null) {
            if (lines.size >= 2) {
                if (baseVersion == null) baseVersion = lines[0]
                if (targetVersion == null) targetVersion = lines[1]
            }
        }

        val finalBaseVersion = baseVersion
           ?: throw ZipdiffException("Could not parse baseVersion from META-INF/version.txt. Found content:\n$versionStr")
        val finalTargetVersion = targetVersion
           ?: throw ZipdiffException("Could not parse targetVersion from META-INF/version.txt. Found content:\n$versionStr")

        // Read canonicalization.json
        val canonicalizationBytes = getRequiredEntryBytes(zipFile, "META-INF/canonicalization.json")
        val canonicalizationProfileVersion = try {
            val node = objectMapper.readTree(canonicalizationBytes)
            if (node.isObject) {
                node.get("canonicalizationProfileVersion")?.asText()
                    ?: node.get("profileVersion")?.asText()
                    ?: node.get("version")?.asText()
                    ?: throw ZipdiffException("Missing profile version field in META-INF/canonicalization.json")
            } else {
                node.asText()
            }
        } catch (e: ZipdiffException) {
            throw e
        } catch (e: Exception) {
            throw ZipdiffException("Failed to parse META-INF/canonicalization.json", e)
        }

        // Read canonical-zip.sha256
        val sha256Bytes = getRequiredEntryBytes(zipFile, "META-INF/canonical-zip.sha256")
        val canonicalZipSha256 = String(sha256Bytes, Charsets.UTF_8).trim()
        if (canonicalZipSha256.isEmpty()) {
            throw ZipdiffException("META-INF/canonical-zip.sha256 is empty")
        }

        // Read signature-schemes.json
        val schemesBytes = getRequiredEntryBytes(zipFile, "META-INF/signature-schemes.json")
        val signatureSchemes: List<String> = try {
            val node = objectMapper.readTree(schemesBytes)
            if (node.isArray) {
                node.map { it.asText() }
            } else if (node.isObject && node.has("schemes")) {
                node.get("schemes").map { it.asText() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            throw ZipdiffException("Failed to parse META-INF/signature-schemes.json", e)
        }

        return PatchMetadata(
            baseVersion = finalBaseVersion,
            targetVersion = finalTargetVersion,
            canonicalizationProfileVersion = canonicalizationProfileVersion,
            canonicalZipSha256 = canonicalZipSha256,
            signatureSchemes = signatureSchemes
        )
    }

    private fun readSignatures(zipFile: ZipFile): List<SignatureBlock> {
        val signaturesBytes = getEntryBytes(zipFile, "META-INF/signatures.json") ?: return emptyList()
        if (signaturesBytes.isEmpty()) return emptyList()
        return try {
            val listType = objectMapper.typeFactory.constructCollectionType(List::class.java, SignatureBlock::class.java)
            objectMapper.readValue(signaturesBytes, listType)
        } catch (e: Exception) {
            throw ZipdiffException("Failed to parse META-INF/signatures.json", e)
        }
    }

    private fun readDiffEntries(zipFile: ZipFile): List<DiffEntry> {
        val entriesBytes = getEntryBytes(zipFile, "META-INF/entries.json")
        if (entriesBytes != null && entriesBytes.isNotEmpty()) {
            try {
                val root = objectMapper.readTree(entriesBytes)
                if (root.isArray) {
                    val result = mutableListOf<DiffEntry>()
                    for (node in root) {
                        val path = node.get("path")?.asText() ?: continue
                       val cleanPath = sanitizePath(path)
                        val modeStr = node.get("mode")?.asText() ?: EntryMode.MODIFIED.name
                        val mode = try {
                            EntryMode.valueOf(modeStr)
                        } catch (e: Exception) {
                            EntryMode.MODIFIED
                        }
                        val hasPayload = node.get("hasPayload")?.asBoolean() ?: true
                        val metadataMap = mutableMapOf<String, String>()
                        node.get("metadata")?.fields()?.forEach { (k, v) ->
                            metadataMap[k] = v.asText()
                        }

                        val zipPath = if (mode == EntryMode.DELETED) "DIFF/$cleanPath.tombstone" else "DIFF/$cleanPath"
                        val bytes = getEntryBytes(zipFile, zipPath) ?: getEntryBytes(zipFile, "DIFF/$cleanPath")
                        val payloadBytes = if (hasPayload) (bytes ?: ByteArray(0)) else null

                        result.add(
                            DiffEntry(
                               path = cleanPath,
                                mode = mode,
                                payloadBytes = payloadBytes,
                                metadata = metadataMap
                            )
                        )
                    }
                    return result
                }
            } catch (e: Exception) {
                throw ZipdiffException("Failed to parse META-INF/entries.json", e)
            }
        }

        // Fallback: Scan ZIP entries under DIFF/
        val result = mutableListOf<DiffEntry>()
        val zipEntries = zipFile.entries()
        while (zipEntries.hasMoreElements()) {
            val entry = zipEntries.nextElement()
            val entryName = entry.name.replace('\\', '/')
            if (entry.isDirectory || !entryName.startsWith("DIFF/")) continue

            val relativePath = entryName.removePrefix("DIFF/").removePrefix("/")
            if (relativePath.isEmpty()) continue
           val cleanPath = sanitizePath(relativePath)

           val (path, modeCandidate) = when {
               cleanPath.endsWith(".tombstone") -> cleanPath.removeSuffix(".tombstone") to EntryMode.DELETED
               cleanPath.endsWith(".empty") -> cleanPath.removeSuffix(".empty") to EntryMode.EMPTY_FILE
               else -> cleanPath to null
           }
           val bytes = if (entry.size == 0L) {
               ByteArray(0)
           } else {
               zipFile.getInputStream(entry).use { it.readBytes() }
           }
           val mode = modeCandidate ?: if (bytes.isEmpty()) EntryMode.EMPTY_FILE else EntryMode.MODIFIED

            val payloadBytes = if (mode == EntryMode.DELETED && bytes.isEmpty()) null else bytes
            result.add(
                DiffEntry(
                    path = path,
                    mode = mode,
                    payloadBytes = payloadBytes
                )
            )
        }
        return result
    }
}

fun sanitizePath(path: String): String {
    // Remove leading slashes and normalize path separators
    val normalized = path.replace('\\', '/').trimStart('/')
    if (normalized.isEmpty()) {
        throw ZipdiffException("Invalid entry path: '$path' resolves to an empty path after sanitization")
    }
    if (normalized.contains("..")) {
        throw ZipdiffException("Invalid entry path: '$path' contains directory traversal '..'")
    }
    return normalized
}