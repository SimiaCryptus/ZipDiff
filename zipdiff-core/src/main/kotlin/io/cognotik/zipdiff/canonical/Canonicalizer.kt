package io.cognotik.zipdiff.canonical

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Engine for creating deterministic (canonical) ZIP archives.
 */
class Canonicalizer {

    /**
     * Reads [inputZip], normalizes its entries according to [profile], and writes a canonical ZIP archive to [outputZip].
     *
     * @param inputZip Path to the source ZIP archive.
     * @param outputZip Path where the canonical ZIP archive will be created.
     * @param profile Parameters and rules for canonicalization.
     * @return [CanonicalResult] containing metadata about the generated canonical ZIP file.
     */
    fun canonicalize(
        inputZip: Path,
        outputZip: Path,
        profile: CanonicalProfile = CanonicalProfile()
    ): CanonicalResult {
        val inputCanonical = inputZip.toAbsolutePath().normalize()
        val outputCanonical = outputZip.toAbsolutePath().normalize()
        if (inputCanonical == outputCanonical ||
            (Files.exists(inputZip) && Files.exists(outputZip) && Files.isSameFile(inputZip, outputZip))
        ) {
            throw IllegalArgumentException("Input and output ZIP paths cannot resolve to the same file: $inputZip")
        }

        outputZip.parent?.let { Files.createDirectories(it) }

        val entryCount: Int = ZipFile(Files.newByteChannel(inputZip)).use { zipFile ->
            val entries = zipFile.entries.asSequence().toList().sortedBy { it.name }

            ZipArchiveOutputStream(Files.newOutputStream(outputZip)).use { zipOut ->
                zipOut.setLevel(profile.compressionLevel)
                zipOut.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
                zipOut.setEncoding("UTF-8")

                for (entry in entries) {
                    val entryName = entry.name
                    val isDirectory = entry.isDirectory || entryName.endsWith("/")
                    val finalName = if (isDirectory && !entryName.endsWith("/")) "$entryName/" else entryName

                    val newEntry = ZipArchiveEntry(finalName)


                    // Normalize timestamp
                    newEntry.time = profile.timestampEpochSeconds * 1000L

                    // Normalize POSIX permissions
                    if (profile.normalizePermissions) {
                        val isExecutable = (entry.unixMode and 0x49) != 0
                        val mode = when {
                            isDirectory -> PERM_DIR_OR_EXEC
                            isExecutable -> PERM_DIR_OR_EXEC
                            else -> PERM_REGULAR_FILE
                        }
                        newEntry.unixMode = mode
                    } else if (entry.unixMode != 0) {
                        newEntry.unixMode = entry.unixMode
                    }
                    val isStored = entry.method == ZipArchiveEntry.STORED
                    val keepStored = isStored && profile.preserveStored

                    if (isDirectory) {
                        newEntry.method = ZipArchiveEntry.STORED
                        newEntry.size = 0L
                        newEntry.crc = 0L
                    } else if (keepStored) {
                        newEntry.method = ZipArchiveEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.crc = entry.crc
                    } else {
                        newEntry.method = ZipArchiveEntry.DEFLATED
                    }

                    // Strip extra fields and comments as final step after property configuration
                    newEntry.extraFields = emptyArray()
                    newEntry.setExtra(null)
                    newEntry.comment = null

                    zipOut.putArchiveEntry(newEntry)
                    if (!isDirectory) {
                        zipFile.getInputStream(entry).use { input ->
                            input.copyTo(zipOut)
                        }
                    }
                    zipOut.closeArchiveEntry()
                }
                zipOut.finish()
            }
            entries.size
        }

        val sha256Hex = computeSha256(outputZip)

        return CanonicalResult(
            outputPath = outputZip,
            entryCount = entryCount,
            sha256Hex = sha256Hex
        )
    }

    private fun computeSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PERM_DIR_OR_EXEC = 493 // 0755 octal
        private const val PERM_REGULAR_FILE = 420 // 0644 octal

        /**
         * Convenience method to canonicalize a ZIP archive using a default [Canonicalizer] instance.
         */
        fun canonicalize(
            inputZip: Path,
            outputZip: Path,
            profile: CanonicalProfile = CanonicalProfile()
        ): CanonicalResult = Canonicalizer().canonicalize(inputZip, outputZip, profile)
    }
}