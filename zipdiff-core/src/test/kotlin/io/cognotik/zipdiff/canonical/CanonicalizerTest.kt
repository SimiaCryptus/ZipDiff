package io.cognotik.zipdiff.canonical

import io.cognotik.zipdiff.testutil.TestZipUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CanonicalizerTest {

    @Test
    fun `canonicalization is deterministic across repeated runs`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("input.zip")
        TestZipUtils.createZip(
            input,
            mapOf(
                "b.txt" to "content b".toByteArray(),
                "a.txt" to "content a".toByteArray()
            )
        )

        val out1 = tempDir.resolve("out1.zip")
        val out2 = tempDir.resolve("out2.zip")

        val result1 = Canonicalizer().canonicalize(input, out1)
        val result2 = Canonicalizer().canonicalize(input, out2)

        assertEquals(result1.sha256Hex, result2.sha256Hex)
        assertEquals(2, result1.entryCount)
    }

    @Test
    fun `entries are sorted alphabetically`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("input.zip")
        TestZipUtils.createZip(
            input,
            mapOf(
                "z.txt" to "z".toByteArray(),
                "a.txt" to "a".toByteArray(),
                "m.txt" to "m".toByteArray()
            )
        )
        val output = tempDir.resolve("output.zip")
        Canonicalizer().canonicalize(input, output)

        val names = TestZipUtils.listEntryNames(output)
        assertEquals(listOf("a.txt", "m.txt", "z.txt"), names)
    }

    @Test
    fun `throws when input and output are the same path`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("same.zip")
        TestZipUtils.createZip(input, mapOf("f.txt" to "x".toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            Canonicalizer().canonicalize(input, input)
        }
    }

    @Test
    fun `preserveStored keeps content readable`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("input.zip")
        TestZipUtils.createZip(input, mapOf("f.txt" to "content".toByteArray()))
        val output = tempDir.resolve("output.zip")

        val profile = CanonicalProfile(preserveStored = true)
        Canonicalizer().canonicalize(input, output, profile)

        val bytes = TestZipUtils.readZipEntry(output, "f.txt")
        assertArrayEquals("content".toByteArray(), bytes)
    }

    @Test
    fun `normalizes timestamps consistently and matches configured epoch`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("input.zip")
        TestZipUtils.createZip(
            input,
            mapOf("a.txt" to "content-a".toByteArray(), "b.txt" to "content-b".toByteArray())
        )
        val output = tempDir.resolve("output.zip")

        val epochSeconds = 946684800L // 2000-01-01T00:00:00Z (valid DOS-time range, even seconds)
        val profile = CanonicalProfile(timestampEpochSeconds = epochSeconds)
        Canonicalizer().canonicalize(input, output, profile)

        java.util.zip.ZipFile(output.toFile()).use { zf ->
            val entryA = zf.getEntry("a.txt")!!
            val entryB = zf.getEntry("b.txt")!!
            assertEquals(entryA.time, entryB.time)
            assertEquals(epochSeconds * 1000L, entryA.time)
        }
    }

    @Test
    fun `computes valid sha256 hex digest`(@TempDir tempDir: Path) {
        val input = tempDir.resolve("input.zip")
        TestZipUtils.createZip(input, mapOf("f.txt" to "content".toByteArray()))
        val output = tempDir.resolve("output.zip")

        val result = Canonicalizer().canonicalize(input, output)
        assertTrue(Regex("^[0-9a-fA-F]{64}$").matches(result.sha256Hex))
        assertEquals(result.sha256Hex, TestZipUtils.sha256(output))
    }
}