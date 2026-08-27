package io.cognotik.zipdiff.canonical

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CanonicalResultTest {

    private val validHash = "a".repeat(64)

    @Test
    fun `constructs successfully with valid values`() {
        val result = CanonicalResult(entryCount = 5, sha256Hex = validHash, outputPath = Paths.get("out.zip"))
        assertEquals(5, result.entryCount)
        assertEquals(validHash, result.sha256Hex)
    }

    @Test
    fun `rejects negative entryCount`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalResult(entryCount = -1, sha256Hex = validHash, outputPath = Paths.get("out.zip"))
        }
    }

    @Test
    fun `rejects invalid sha256Hex length`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalResult(entryCount = 0, sha256Hex = "short", outputPath = Paths.get("out.zip"))
        }
    }

    @Test
    fun `rejects non-hex characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalResult(entryCount = 0, sha256Hex = "g".repeat(64), outputPath = Paths.get("out.zip"))
        }
    }

    @Test
    fun `accepts uppercase hex characters`() {
        val result = CanonicalResult(entryCount = 0, sha256Hex = "A".repeat(64), outputPath = Paths.get("out.zip"))
        assertEquals("A".repeat(64), result.sha256Hex)
    }
}