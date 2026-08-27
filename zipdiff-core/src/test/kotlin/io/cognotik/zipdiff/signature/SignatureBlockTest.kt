package io.cognotik.zipdiff.signature

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SignatureBlockTest {

    @Test
    fun `constructor defensively copies input signature data`() {
        val original = byteArrayOf(1, 2, 3)
        val block = SignatureBlock("scheme", "1.0", "hash", original, PlacementRule.META_INF_ENTRY)
        original[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), block.signatureData)
    }

    @Test
    fun `getter returns defensive copy each time`() {
        val block = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        val copy1 = block.signatureData
        copy1[0] = 42
        val copy2 = block.signatureData
        assertArrayEquals(byteArrayOf(1, 2, 3), copy2)
    }

    @Test
    fun `equals and hashCode based on content`() {
        val block1 = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        val block2 = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        assertEquals(block1, block2)
        assertEquals(block1.hashCode(), block2.hashCode())
    }

    @Test
    fun `equals returns false for different signature data`() {
        val block1 = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        val block2 = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(4, 5, 6), PlacementRule.META_INF_ENTRY)
        assertNotEquals(block1, block2)
    }

    @Test
    fun `equals returns false for different type`() {
        val block1 = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        assertNotEquals(block1, "not a block")
    }

    @Test
    fun `copy creates independent instance with defensive clone`() {
        val original = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        val copy = original.copy(schemeId = "otherScheme")
        assertEquals("otherScheme", copy.schemeId)
        assertArrayEquals(original.signatureData, copy.signatureData)
        assertNotSame(original.signatureData, copy.signatureData)
    }

    @Test
    fun `component functions return correct values`() {
        val block = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.EXTRA_FIELD)
        assertEquals("scheme", block.component1())
        assertEquals("1.0", block.component2())
        assertEquals("hash", block.component3())
        assertArrayEquals(byteArrayOf(1, 2, 3), block.component4())
        assertEquals(PlacementRule.EXTRA_FIELD, block.component5())
    }

    @Test
    fun `toString includes size and scheme information`() {
        val block = SignatureBlock("scheme", "1.0", "hash", byteArrayOf(1, 2, 3), PlacementRule.META_INF_ENTRY)
        val str = block.toString()
        assertTrue(str.contains("size=3"))
        assertTrue(str.contains("scheme"))
    }

    @Test
    fun `SignatureMetadata secondary constructor builds single scheme list`() {
        val metadata = SignatureMetadata(
            schemeId = "sig1",
            canonicalProfileVersion = "2.0",
            placementRule = PlacementRule.EXTRA_FIELD,
            targetZipHash = "abc123"
        )
        assertEquals(1, metadata.schemes.size)
        assertEquals("sig1", metadata.schemeId)
        assertEquals("2.0", metadata.canonicalProfileVersion)
        assertEquals(PlacementRule.EXTRA_FIELD, metadata.placementRule)
        assertEquals("abc123", metadata.targetZipHash)
    }

    @Test
    fun `SignatureMetadata primaryScheme is null when no schemes`() {
        val metadata = SignatureMetadata()
        assertNull(metadata.primaryScheme)
        assertEquals("", metadata.schemeId)
        assertEquals("", metadata.canonicalProfileVersion)
        assertEquals(PlacementRule.META_INF_ENTRY, metadata.placementRule)
        assertNull(metadata.targetZipHash)
    }

    @Test
    fun `SignatureMetadata primaryScheme returns first entry`() {
        val entry1 = SignatureSchemeEntry("s1", "1.0", PlacementRule.META_INF_ENTRY)
        val entry2 = SignatureSchemeEntry("s2", "2.0", PlacementRule.EXTRA_FIELD)
        val metadata = SignatureMetadata(schemes = listOf(entry1, entry2))
        assertEquals(entry1, metadata.primaryScheme)
        assertEquals("s1", metadata.schemeId)
    }
}