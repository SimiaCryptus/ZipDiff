package io.cognotik.zipdiff.patch

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.diff.DiffGenerator
import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.`package`.PatchMetadata
import io.cognotik.zipdiff.`package`.PatchPackage
import io.cognotik.zipdiff.testutil.TestZipUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PatchChainApplierTest {

    @Test
    fun `applies two sequential patches to reconstruct final version`(@TempDir tempDir: Path) {
        val v1Source = tempDir.resolve("v1-source.zip")
        val v2Source = tempDir.resolve("v2-source.zip")
        val v3Source = tempDir.resolve("v3-source.zip")

        TestZipUtils.createZip(v1Source, mapOf("file.txt" to "version 1".toByteArray()))
        TestZipUtils.createZip(v2Source, mapOf("file.txt" to "version 2".toByteArray()))
        TestZipUtils.createZip(
            v3Source,
            mapOf("file.txt" to "version 3".toByteArray(), "new.txt" to "added in v3".toByteArray())
        )

        val v1 = tempDir.resolve("v1.zip")
        val v2 = tempDir.resolve("v2.zip")
        val v3 = tempDir.resolve("v3.zip")
        Canonicalizer().canonicalize(v1Source, v1)
        val v2Result = Canonicalizer().canonicalize(v2Source, v2)
        val v3Result = Canonicalizer().canonicalize(v3Source, v3)

        val profile = CanonicalProfile()
        val diffs1to2 = DiffGenerator.generateDiff(v1, v2, profile)
        val diffs2to3 = DiffGenerator.generateDiff(v2, v3, profile)

        val patch1 = PatchPackage(
            PatchMetadata("1", "2", "1.0", v2Result.sha256Hex),
            diffs1to2
        )
        val patch2 = PatchPackage(
            PatchMetadata("2", "3", "1.0", v3Result.sha256Hex),
            diffs2to3
        )

        val output = tempDir.resolve("final.zip")
        val result = PatchChainApplier().applyChain(v1, listOf(patch1, patch2), output)

        assertEquals(v3Result.sha256Hex, TestZipUtils.sha256(result))
        assertArrayEquals("version 3".toByteArray(), TestZipUtils.readZipEntry(result, "file.txt"))
        assertArrayEquals("added in v3".toByteArray(), TestZipUtils.readZipEntry(result, "new.txt"))
    }

    @Test
    fun `throws when patch package list is empty`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        TestZipUtils.createZip(baseZip, mapOf("file.txt" to "content".toByteArray()))
        val output = tempDir.resolve("output.zip")

        assertThrows(ZipdiffException::class.java) {
            PatchChainApplier().applyChain(baseZip, emptyList(), output)
        }
    }

    @Test
    fun `throws when base and output paths are the same`(@TempDir tempDir: Path) {
        val baseZip = tempDir.resolve("base.zip")
        TestZipUtils.createZip(baseZip, mapOf("file.txt" to "content".toByteArray()))

        val diffs = DiffGenerator.generateDiff(baseZip, baseZip, CanonicalProfile())
        val patch = PatchPackage(
            PatchMetadata("1", "1", "1.0", TestZipUtils.sha256(baseZip)),
            diffs
        )

        assertThrows(ZipdiffException::class.java) {
            PatchChainApplier().applyChain(baseZip, listOf(patch), baseZip)
        }
    }

    @Test
    fun `companion convenience method applies chain equivalently`(@TempDir tempDir: Path) {
        val v1Source = tempDir.resolve("v1-source.zip")
        val v2Source = tempDir.resolve("v2-source.zip")
        TestZipUtils.createZip(v1Source, mapOf("file.txt" to "one".toByteArray()))
        TestZipUtils.createZip(v2Source, mapOf("file.txt" to "two".toByteArray()))

        val v1 = tempDir.resolve("v1.zip")
        val v2 = tempDir.resolve("v2.zip")
        Canonicalizer().canonicalize(v1Source, v1)
        val v2Result = Canonicalizer().canonicalize(v2Source, v2)

        val diffs = DiffGenerator.generateDiff(v1, v2, CanonicalProfile())
        val patch = PatchPackage(PatchMetadata("1", "2", "1.0", v2Result.sha256Hex), diffs)

        val output = tempDir.resolve("out.zip")
        val result = PatchChainApplier.applyChain(v1, listOf(patch), output)

        assertEquals(v2Result.sha256Hex, TestZipUtils.sha256(result))
    }
}