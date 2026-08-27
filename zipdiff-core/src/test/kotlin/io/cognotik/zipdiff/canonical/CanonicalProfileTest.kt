package io.cognotik.zipdiff.canonical

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CanonicalProfileTest {

    @Test
    fun `default profile has expected values`() {
        val profile = CanonicalProfile()
        assertEquals(9, profile.compressionLevel)
        assertEquals(0L, profile.timestampEpochSeconds)
        assertTrue(profile.normalizePermissions)
        assertFalse(profile.preserveStored)
    }

    @Test
    fun `accepts compressionLevel of -1`() {
        val profile = CanonicalProfile(compressionLevel = -1)
        assertEquals(-1, profile.compressionLevel)
    }

    @Test
    fun `accepts boundary compressionLevel values 0 and 9`() {
        assertEquals(0, CanonicalProfile(compressionLevel = 0).compressionLevel)
        assertEquals(9, CanonicalProfile(compressionLevel = 9).compressionLevel)
    }

    @Test
    fun `rejects invalid compressionLevel above range`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalProfile(compressionLevel = 10)
        }
    }

    @Test
    fun `rejects invalid compressionLevel below range`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalProfile(compressionLevel = -2)
        }
    }
}