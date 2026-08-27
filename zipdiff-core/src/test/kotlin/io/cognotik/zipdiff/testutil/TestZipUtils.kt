package io.cognotik.zipdiff.testutil

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Shared helpers for building and inspecting ZIP fixtures in tests.
 */
object TestZipUtils {

    fun createZip(path: Path, entries: Map<String, ByteArray>, comment: String? = null) {
        path.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(path).use { out ->
            ZipOutputStream(out).use { zos ->
                comment?.let { zos.setComment(it) }
                for ((name, bytes) in entries) {
                    val entry = ZipEntry(name)
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    fun readZipEntry(path: Path, name: String): ByteArray? {
        ZipFile(path.toFile()).use { zf ->
            val entry = zf.getEntry(name) ?: return null
            return zf.getInputStream(entry).use { it.readBytes() }
        }
    }

    fun zipComment(path: Path): String? {
        ZipFile(path.toFile()).use { return it.comment }
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun listEntryNames(path: Path): List<String> {
        ZipFile(path.toFile()).use { zf ->
            return zf.entries().asSequence().map { it.name }.toList()
        }
    }
}