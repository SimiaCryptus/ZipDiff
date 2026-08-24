package io.cognotik.zipdiff.patch

import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.deflate.DeflateDictionaryEngine
import io.cognotik.zipdiff.diff.DiffEntry
import io.cognotik.zipdiff.diff.EntryMode
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.`package`.PatchPackage
import io.cognotik.zipdiff.signature.SignatureManager
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.ZipException

/**
 * Engine for applying patch packages to base ZIP archives to reconstruct canonical target archives.
 */
class PatchApplier {

    /**
     * Reconstructs the target canonical ZIP archive by applying [patchPackage] to [baseZip].
     *
     * @param baseZip Path to the base source ZIP archive.
     * @param patchPackage Patch package containing metadata and diff entries.
     * @param outputPath Target path where the reconstructed canonical ZIP archive will be created.
     * @return Path to the created canonical target ZIP archive.
     * @throws ZipdiffException If hash mismatch occurs or patch application fails.
     */
    fun applyPatch(
        baseZip: Path,
        patchPackage: PatchPackage,
        outputPath: Path
    ): Path {
        val baseZipFile = try {
            ZipFile(baseZip.toFile())
        } catch (e: Exception) {
            throw ZipdiffException("Failed to read base ZIP archive at $baseZip", e)
        }

        val tempZip = Files.createTempFile("zipdiff-target-", ".zip")
        try {
            baseZipFile.use { zipFile ->
                ZipArchiveOutputStream(BufferedOutputStream(Files.newOutputStream(tempZip))).use { zipOut ->
                    zipOut.setEncoding("UTF-8")
                    val handledPaths = mutableSetOf<String>()

                    for (entry in patchPackage.diffEntries) {
                        val path = entry.path
                        handledPaths.add(path)

                        when (entry.mode) {
                            EntryMode.DELETED -> {
                                // Omit DELETED tombstone entries
                            }
                            EntryMode.EMPTY_FILE -> {
                                writeEntry(zipOut, path, ByteArray(0))
                            }
                            EntryMode.UNCHANGED -> {
                                val baseZipEntry = zipFile.getEntry(path)
                                if (baseZipEntry != null) {
                                    zipFile.getInputStream(baseZipEntry).use { input ->
                                        streamEntry(zipOut, path, input)
                                    }
                                } else {
                                    val rawData = entry.payloadBytes ?: ByteArray(0)
                                    writeEntry(zipOut, path, rawData)
                                }
                            }
                            EntryMode.NEW -> {
                                val rawData = entry.payloadBytes ?: ByteArray(0)
                                if (rawData.isNotEmpty()) {
                                    if (isCompressed(entry, rawData)) {
                                        val dict = getDictionary(entry, null)
                                        val decompressed = decompressPayload(rawData, dict, path)
                                        writeEntry(zipOut, path, decompressed)
                                    } else {
                                        writeEntry(zipOut, path, rawData)
                                    }
                                } else {
                                    writeEntry(zipOut, path, ByteArray(0))
                                }
                            }
                            EntryMode.MODIFIED -> {
                                val rawData = entry.payloadBytes ?: ByteArray(0)
                                val baseZipEntry = zipFile.getEntry(path)
                                val baseBytes = if (baseZipEntry != null) {
                                    zipFile.getInputStream(baseZipEntry).use { it.readBytes() }
                                } else null

                                if (rawData.isNotEmpty()) {
                                    val dict = getDictionary(entry, baseBytes)
                                    if (isCompressed(entry, rawData)) {
                                        val decompressed = decompressPayload(rawData, dict, path)
                                        writeEntry(zipOut, path, decompressed)
                                    } else {
                                        writeEntry(zipOut, path, rawData)
                                    }
                                } else {
                                    writeEntry(zipOut, path, baseBytes ?: ByteArray(0))
                                }
                            }
                        }
                    }

                    // Copy any remaining base archive entries not mentioned in diff entries
                    val zipEntries = zipFile.entries
                    while (zipEntries.hasMoreElements()) {
                        val baseEntry = zipEntries.nextElement()
                        if (baseEntry.name !in handledPaths) {
                            zipFile.getInputStream(baseEntry).use { input ->
                                streamEntry(zipOut, baseEntry.name, input)
                            }
                        }
                    }
                    zipOut.finish()
                }
            }

            val canonicalizer = Canonicalizer()
            val canonicalResult = canonicalizer.canonicalize(tempZip, outputPath)

            val expectedSha256 = patchPackage.metadata.canonicalZipSha256
            if (expectedSha256.isNotBlank()) {
                if (!canonicalResult.sha256Hex.equals(expectedSha256, ignoreCase = true)) {
                    Files.deleteIfExists(outputPath)
                    throw ZipdiffException(
                        "SHA-256 hash mismatch for reconstructed canonical archive. Expected: $expectedSha256, Actual: ${canonicalResult.sha256Hex}"
                    )
                }
            }
            if (patchPackage.signatureBlocks.isNotEmpty()) {
                val signatureManager = SignatureManager()
                for (block in patchPackage.signatureBlocks) {
                    signatureManager.applySignatureBlock(outputPath, block)
                }
            }

            return outputPath
        } catch (e: ZipdiffException) {
            Files.deleteIfExists(outputPath)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(outputPath)
            throw ZipdiffException("Failed to apply patch package to $baseZip", e)
        } finally {
            Files.deleteIfExists(tempZip)
        }
    }

    private fun decompressPayload(payload: ByteArray, dict: ByteArray, path: String): ByteArray {
        return try {
            DeflateDictionaryEngine.decompressWithDict(payload, dict)
        } catch (e: DataFormatException) {
            throw ZipdiffException("Decompression failed for entry '$path': invalid deflate data format", e)
        } catch (e: ZipException) {
            throw ZipdiffException("Decompression failed for entry '$path': ZIP format error", e)
        } catch (e: IOException) {
            throw ZipdiffException("IO error during decompression for entry '$path'", e)
        } catch (e: IllegalArgumentException) {
            throw ZipdiffException("Invalid argument for decompression of entry '$path'", e)
        } catch (e: ZipdiffException) {
            throw e
        }
    }

    private fun isCompressed(entry: DiffEntry, payload: ByteArray): Boolean {
        val compressionMeta = entry.metadata["compression"] ?: entry.metadata["compressed"] ?: entry.metadata["encoding"]
        if (compressionMeta != null) {
            if (compressionMeta.equals("true", ignoreCase = true) ||
                compressionMeta.equals("deflate", ignoreCase = true) ||
                compressionMeta.equals("zlib", ignoreCase = true)) {
                return true
            }
            if (compressionMeta.equals("false", ignoreCase = true) ||
                compressionMeta.equals("raw", ignoreCase = true) ||
                compressionMeta.equals("store", ignoreCase = true) ||
                compressionMeta.equals("none", ignoreCase = true)) {
                return false
            }
        }
        return isZlibHeader(payload)
    }

    private fun isZlibHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val cmf = bytes[0].toInt() and 0xFF
        val flg = bytes[1].toInt() and 0xFF
        return (cmf and 0x0F == 8) && ((cmf * 256 + flg) % 31 == 0)
    }

    private fun getDictionary(entry: DiffEntry, baseBytes: ByteArray?): ByteArray {
        val metaDict = entry.metadata["dictionary"] ?: entry.metadata["dict"] ?: entry.metadata["presetDictionary"]
        if (metaDict != null) {
            return runCatching { Base64.getDecoder().decode(metaDict) }.getOrDefault(baseBytes ?: ByteArray(0))
        }
        return baseBytes ?: ByteArray(0)
    }

    private fun writeEntry(zipOut: ZipArchiveOutputStream, name: String, content: ByteArray) {
        val isDirectory = name.endsWith("/")
        val zipEntry = ZipArchiveEntry(name)
        if (isDirectory) {
            zipEntry.method = ZipArchiveEntry.STORED
            zipEntry.size = 0L
            zipEntry.crc = 0L
            zipOut.putArchiveEntry(zipEntry)
            zipOut.closeArchiveEntry()
        } else {
            zipEntry.method = ZipArchiveEntry.DEFLATED
            zipOut.putArchiveEntry(zipEntry)
            zipOut.write(content)
            zipOut.closeArchiveEntry()
        }
    }

    private fun streamEntry(zipOut: ZipArchiveOutputStream, name: String, inputStream: InputStream) {
        val isDirectory = name.endsWith("/")
        val zipEntry = ZipArchiveEntry(name)
        if (isDirectory) {
            zipEntry.method = ZipArchiveEntry.STORED
            zipEntry.size = 0L
            zipEntry.crc = 0L
            zipOut.putArchiveEntry(zipEntry)
            zipOut.closeArchiveEntry()
        } else {
            zipEntry.method = ZipArchiveEntry.DEFLATED
            zipOut.putArchiveEntry(zipEntry)
            inputStream.copyTo(zipOut)
            zipOut.closeArchiveEntry()
        }
    }

    companion object {
        /**
         * Convenience method to apply a patch package using a default [PatchApplier] instance.
         */
        fun applyPatch(
            baseZip: Path,
            patchPackage: PatchPackage,
            outputPath: Path
        ): Path = PatchApplier().applyPatch(baseZip, patchPackage, outputPath)
    }
}