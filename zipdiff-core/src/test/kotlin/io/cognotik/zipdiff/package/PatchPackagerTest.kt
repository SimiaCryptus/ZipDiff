package io.cognotik.zipdiff.`package`

import io.cognotik.zipdiff.diff.DiffEntry
import io.cognotik.zipdiff.diff.EntryMode
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.signature.PlacementRule
import io.cognotik.zipdiff.signature.SignatureBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PatchPackagerTest {

    @Test
    fun `writePatchPackage and readPatchPackage round trip preserves metadata and entries`(@TempDir tempDir: Path) {
        val outputPath = tempDir.resolve("test.patch.zp")
        val metadata = PatchMetadata(
            baseVersion = "1.0",
            targetVersion = "2.0",
            canonicalizationProfileVersion = "1.0",
            canonicalZipSha256 = "a".repeat(64),
            signatureSchemes = listOf("schemeA")
        )
        val diffs = listOf(
            DiffEntry("new.txt", EntryMode.NEW, "new content".toByteArray()),
            DiffEntry("modified.txt", EntryMode.MODIFIED, "modified payload".toByteArray()),
            DiffEntry("deleted.txt", EntryMode.DELETED, ByteArray(0)),
            DiffEntry("unchanged.txt", EntryMode.UNCHANGED, null),
            DiffEntry("empty.txt", EntryMode.EMPTY_FILE, ByteArray(0))
        )
        val signatures = listOf(
            SignatureBlock("schemeA", "1.0", "a".repeat(64), "sigdata".toByteArray(), PlacementRule.META_INF_ENTRY)
        )

        PatchPackager.writePatchPackage(outputPath, metadata, diffs, signatures)
        val readBack = PatchPackager.readPatchPackage(outputPath)

        assertEquals(metadata.baseVersion, readBack.metadata.baseVersion)
        assertEquals(metadata.targetVersion, readBack.metadata.targetVersion)
        assertEquals(metadata.canonicalizationProfileVersion, readBack.metadata.canonicalizationProfileVersion)
        assertEquals(metadata.canonicalZipSha256, readBack.metadata.canonicalZipSha256)

        val entriesByPath = readBack.diffEntries.associateBy { it.path }
        assertEquals(EntryMode.NEW, entriesByPath["new.txt"]!!.mode)
        assertArrayEquals("new content".toByteArray(), entriesByPath["new.txt"]!!.payloadBytes)

        assertEquals(EntryMode.MODIFIED, entriesByPath["modified.txt"]!!.mode)
        assertArrayEquals("modified payload".toByteArray(), entriesByPath["modified.txt"]!!.payloadBytes)

        assertEquals(EntryMode.DELETED, entriesByPath["deleted.txt"]!!.mode)
        assertEquals(EntryMode.UNCHANGED, entriesByPath["unchanged.txt"]!!.mode)
        assertEquals(EntryMode.EMPTY_FILE, entriesByPath["empty.txt"]!!.mode)

        assertEquals(1, readBack.signatureBlocks.size)
        assertEquals("schemeA", readBack.signatureBlocks[0].schemeId)
        assertArrayEquals("sigdata".toByteArray(), readBack.signatureBlocks[0].signatureData)
    }

    @Test
    fun `readPatchPackage throws when file does not exist`(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("missing.patch.zp")
        assertThrows(ZipdiffException::class.java) {
            PatchPackager.readPatchPackage(missing)
        }
    }

    @Test
    fun `writePatchPackage with no signatures results in empty signatureBlocks on read`(@TempDir tempDir: Path) {
        val outputPath = tempDir.resolve("nosig.patch.zp")
        val metadata = PatchMetadata(
            baseVersion = "1.0",
            targetVersion = "2.0",
            canonicalizationProfileVersion = "1.0",
            canonicalZipSha256 = "b".repeat(64)
        )
        val diffs = listOf(DiffEntry("file.txt", EntryMode.NEW, "content".toByteArray()))

        PatchPackager.writePatchPackage(outputPath, metadata, diffs)
        val readBack = PatchPackager.readPatchPackage(outputPath)

        assertTrue(readBack.signatureBlocks.isEmpty())
    }

    @Test
    fun `sanitizePath normalizes backslashes and leading slashes`() {
        assertEquals("a/b/c.txt", sanitizePath("\\a\\b\\c.txt"))
        assertEquals("a/b.txt", sanitizePath("/a/b.txt"))
    }

    @Test
    fun `sanitizePath rejects directory traversal`() {
        assertThrows(ZipdiffException::class.java) {
            sanitizePath("../etc/passwd")
        }
    }

    @Test
    fun `sanitizePath rejects empty resulting path`() {
        assertThrows(ZipdiffException::class.java) {
            sanitizePath("///")
        }
    }
}