package io.cognotik.zipdiff.diff

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.deflate.DeflateDictionaryEngine
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.testutil.TestZipUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DiffGeneratorTest {

    @Test
    fun `classifies unchanged, new, modified, deleted, and empty entries`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        val targetZip = tempDir.resolve("target.zip")

        TestZipUtils.createZip(
            baseZip,
            mapOf(
                "unchanged.txt" to "same content".toByteArray(),
                "modified.txt" to "old content here".toByteArray(),
                "deleted.txt" to "to be removed".toByteArray()
            )
        )

        TestZipUtils.createZip(
            targetZip,
            mapOf(
                "unchanged.txt" to "same content".toByteArray(),
                "modified.txt" to "new content here, changed".toByteArray(),
                "new.txt" to "brand new file".toByteArray(),
                "empty.txt" to ByteArray(0)
            )
        )

        val diffs = DiffGenerator.generateDiff(baseZip, targetZip, CanonicalProfile())
        val byPath = diffs.associateBy { it.path }

        assertEquals(EntryMode.UNCHANGED, byPath["unchanged.txt"]!!.mode)
        assertNull(byPath["unchanged.txt"]!!.payloadBytes)

        assertEquals(EntryMode.MODIFIED, byPath["modified.txt"]!!.mode)
        val decompressed = DeflateDictionaryEngine.decompressWithDict(
            byPath["modified.txt"]!!.payloadBytes!!,
            "old content here".toByteArray()
        )
        assertArrayEquals("new content here, changed".toByteArray(), decompressed)

        assertEquals(EntryMode.NEW, byPath["new.txt"]!!.mode)
        assertArrayEquals("brand new file".toByteArray(), byPath["new.txt"]!!.payloadBytes)

        assertEquals(EntryMode.DELETED, byPath["deleted.txt"]!!.mode)

        assertEquals(EntryMode.EMPTY_FILE, byPath["empty.txt"]!!.mode)

        assertEquals(5, diffs.size)
        assertEquals(diffs.map { it.path }, diffs.map { it.path }.sorted())
    }

    @Test
    fun `target empty file overriding non-empty base classified as EMPTY_FILE`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        val targetZip = tempDir.resolve("target.zip")

        TestZipUtils.createZip(baseZip, mapOf("file.txt" to "some content".toByteArray()))
        TestZipUtils.createZip(targetZip, mapOf("file.txt" to ByteArray(0)))

        val diffs = DiffGenerator.generateDiff(baseZip, targetZip, CanonicalProfile())
        assertEquals(1, diffs.size)
        assertEquals(EntryMode.EMPTY_FILE, diffs[0].mode)
    }

    @Test
    fun `throws ZipdiffException for corrupted archive`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        val targetZip = tempDir.resolve("target.zip")
        Files.write(baseZip, "not a zip file".toByteArray())
        TestZipUtils.createZip(targetZip, mapOf("file.txt" to "content".toByteArray()))

        assertThrows(ZipdiffException::class.java) {
            DiffGenerator.generateDiff(baseZip, targetZip, CanonicalProfile())
        }
    }

    @Test
    fun `ignores directory entries and normalizes paths`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        val targetZip = tempDir.resolve("target.zip")
        TestZipUtils.createZip(baseZip, mapOf("dir/" to ByteArray(0), "dir/file.txt" to "a".toByteArray()))
        TestZipUtils.createZip(targetZip, mapOf("dir/" to ByteArray(0), "dir/file.txt" to "a".toByteArray()))

        val diffs = DiffGenerator.generateDiff(baseZip, targetZip, CanonicalProfile())
        assertEquals(1, diffs.size)
        assertEquals("dir/file.txt", diffs[0].path)
        assertEquals(EntryMode.UNCHANGED, diffs[0].mode)
    }
}