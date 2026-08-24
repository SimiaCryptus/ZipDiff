package io.cognotik.zipdiff.diff

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.deflate.DeflateDictionaryEngine
import io.cognotik.zipdiff.exception.ZipdiffException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipException

/**
 * Generator for logical diff analysis between base and target canonical ZIP archives.
 */
object DiffGenerator {

    /**
     * Compares [baseZip] and [targetZip] archives and generates a list of [DiffEntry] classification results.
     *
     * @param baseZip Path to the base ZIP archive.
     * @param targetZip Path to the target ZIP archive.
     * @param profile Canonical profile containing normalization and compression settings.
     * @return List of logical diff entries sorted by path.
     */
    fun generateDiff(
        baseZip: Path,
        targetZip: Path,
        profile: CanonicalProfile
    ): List<DiffEntry> {
        val baseEntries = readZipEntries(baseZip)
        val targetEntries = readZipEntries(targetZip)

        val allPaths = (baseEntries.keys + targetEntries.keys).sorted()

        return allPaths.map { path ->
            val baseBytes = baseEntries[path]
            val targetBytes = targetEntries[path]

            when {
                baseBytes != null && targetBytes == null -> {
                    DiffEntry(
                        path = path,
                        mode = EntryMode.DELETED,
                        payloadBytes = ByteArray(0)
                    )
                }
                baseBytes == null && targetBytes != null -> {
                    if (targetBytes.isEmpty()) {
                        DiffEntry(
                            path = path,
                            mode = EntryMode.EMPTY_FILE,
                            payloadBytes = ByteArray(0)
                        )
                    } else {
                        DiffEntry(
                            path = path,
                            mode = EntryMode.NEW,
                            payloadBytes = targetBytes
                        )
                    }
                }
                baseBytes != null && targetBytes != null -> {
                    if (baseBytes.contentEquals(targetBytes)) {
                        DiffEntry(
                            path = path,
                            mode = EntryMode.UNCHANGED,
                            payloadBytes = null
                        )
                    } else if (targetBytes.isEmpty()) {
                        DiffEntry(
                            path = path,
                            mode = EntryMode.EMPTY_FILE,
                            payloadBytes = ByteArray(0)
                        )
                    } else {
                        val compressedPayload = DeflateDictionaryEngine.compressWithDict(
                            targetBytes = targetBytes,
                            dictBytes = baseBytes,
                            level = profile.compressionLevel
                        )
                        DiffEntry(
                            path = path,
                            mode = EntryMode.MODIFIED,
                            payloadBytes = compressedPayload
                        )
                    }
                }
                else -> error("Unreachable state for path: $path")
            }
        }
    }

    private fun readZipEntries(zipPath: Path): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        try {
            ZipFile(zipPath.toFile()).use { zipFile ->
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val normalizedPath = normalizePath(entry.name)
                    if (!entry.isDirectory && normalizedPath.isNotEmpty()) {
                        map[normalizedPath] = readEntryBytes(zipFile, entry)
                    }
                }
            }
        } catch (e: ZipException) {
            throw ZipdiffException("Invalid or corrupted ZIP archive: $zipPath", e)
        } catch (e: IOException) {
            throw ZipdiffException("Failed to read ZIP archive: $zipPath", e)
        }
        return map
    }

    private fun readEntryBytes(zipFile: ZipFile, entry: ZipArchiveEntry): ByteArray {
        val size = entry.size
        if (size == 0L) return ByteArray(0)

        return zipFile.getInputStream(entry).use { stream ->
            if (size > 0L && size <= Int.MAX_VALUE) {
                val bytes = ByteArray(size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = stream.read(bytes, offset, bytes.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset < bytes.size) {
                    bytes.copyOf(offset)
                } else {
                    bytes
                }
            } else {
                stream.readBytes()
            }
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').trimStart('/')
    }
}