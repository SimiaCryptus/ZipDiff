package io.cognotik.zipdiff.diff

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class DiffEntryTest {

    @Test
    fun `payloadBytes defensively copies input array`() {
        val original = byteArrayOf(1, 2, 3)
        val entry = DiffEntry("path", EntryMode.NEW, original)
        original[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.payloadBytes)
    }

    @Test
    fun `payloadBytes getter returns fresh copy each call`() {
        val entry = DiffEntry("path", EntryMode.NEW, byteArrayOf(1, 2, 3))
        val copy1 = entry.payloadBytes
        copy1!![0] = 42
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.payloadBytes)
    }

    @Test
    fun `payloadSize derived from byte array when not explicit`() {
        val entry = DiffEntry("path", EntryMode.NEW, byteArrayOf(1, 2, 3, 4))
        assertEquals(4L, entry.payloadSize)
    }

    @Test
    fun `payloadSize derived from metadata size field`() {
        val entry = DiffEntry("path", EntryMode.NEW, null, metadata = mapOf("size" to "123"))
        assertEquals(123L, entry.payloadSize)
    }

    @Test
    fun `payloadSize derived from metadata contentLength field when size absent`() {
        val entry = DiffEntry("path", EntryMode.NEW, null, metadata = mapOf("contentLength" to "77"))
        assertEquals(77L, entry.payloadSize)
    }

    @Test
    fun `payloadSize explicit overrides other sources`() {
        val entry = DiffEntry("path", EntryMode.NEW, byteArrayOf(1, 2, 3), payloadSize = 999L)
        assertEquals(999L, entry.payloadSize)
    }

    @Test
    fun `payloadSize defaults to zero when nothing available`() {
        val entry = DiffEntry("path", EntryMode.UNCHANGED, null)
        assertEquals(0L, entry.payloadSize)
    }

    @Test
    fun `openPayloadStream reads from supplier lazily`() {
        var invoked = false
        val entry = DiffEntry(
            path = "path",
            mode = EntryMode.NEW,
            payloadSupplier = {
                invoked = true
                ByteArrayInputStream(byteArrayOf(5, 6, 7))
            }
        )
        assertFalse(invoked)
        val bytes = entry.openPayloadStream()?.readBytes()
        assertTrue(invoked)
        assertArrayEquals(byteArrayOf(5, 6, 7), bytes)
    }

    @Test
    fun `payloadBytes reads from supplier when no direct bytes given`() {
        val entry = DiffEntry(
            path = "path",
            mode = EntryMode.NEW,
            payloadSupplier = { ByteArrayInputStream(byteArrayOf(9, 8, 7)) }
        )
        assertArrayEquals(byteArrayOf(9, 8, 7), entry.payloadBytes)
    }

    @Test
    fun `openPayloadStream returns null when no payload present`() {
        val entry = DiffEntry("path", EntryMode.UNCHANGED, null)
        assertNull(entry.openPayloadStream())
    }

    @Test
    fun `equals and hashCode based on content`() {
        val e1 = DiffEntry("path", EntryMode.MODIFIED, byteArrayOf(1, 2, 3))
        val e2 = DiffEntry("path", EntryMode.MODIFIED, byteArrayOf(1, 2, 3))
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `equals returns false for different path`() {
        val e1 = DiffEntry("path1", EntryMode.MODIFIED, byteArrayOf(1, 2, 3))
        val e2 = DiffEntry("path2", EntryMode.MODIFIED, byteArrayOf(1, 2, 3))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `equals returns false for different mode`() {
        val e1 = DiffEntry("path", EntryMode.NEW, byteArrayOf(1, 2, 3))
        val e2 = DiffEntry("path", EntryMode.MODIFIED, byteArrayOf(1, 2, 3))
        assertNotEquals(e1, e2)
    }

    @Test
    fun `copy preserves fields and allows overrides`() {
        val original = DiffEntry("path", EntryMode.NEW, byteArrayOf(1, 2, 3), mapOf("k" to "v"))
        val copy = original.copy(mode = EntryMode.MODIFIED)
        assertEquals("path", copy.path)
        assertEquals(EntryMode.MODIFIED, copy.mode)
        assertArrayEquals(byteArrayOf(1, 2, 3), copy.payloadBytes)
        assertEquals(mapOf("k" to "v"), copy.metadata)
    }

    @Test
    fun `toString does not throw and includes path`() {
        val entry = DiffEntry("path/to/file", EntryMode.UNCHANGED, null)
        assertTrue(entry.toString().contains("path/to/file"))
    }
}