package io.cognotik.zipdiff.deflate

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DeflateDictionaryEngineTest {

    @Test
    fun `compress and decompress with dictionary round trip`() {
        val dict = "The quick brown fox jumps over the lazy dog. ".repeat(50).toByteArray()
        val target = ("The quick brown fox jumps over the lazy dog, again and again. " +
                "The quick brown fox jumps over the lazy dog.").toByteArray()

        val compressed = DeflateDictionaryEngine.compressWithDict(target, dict)
        val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, dict)

        assertArrayEquals(target, decompressed)
    }

    @Test
    fun `compress with empty dictionary behaves like standard deflate`() {
        val target = "some content to compress".toByteArray()
        val compressed = DeflateDictionaryEngine.compressWithDict(target, ByteArray(0))
        val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, ByteArray(0))
        assertArrayEquals(target, decompressed)
    }

    @Test
    fun `dictionary larger than max size is truncated to trailing window`() {
        val bigDict = ByteArray(DeflateDictionaryEngine.MAX_DICT_SIZE + 5000) { (it % 251).toByte() }
        val target = "content referencing trailing dictionary bytes".toByteArray()

        val compressed = DeflateDictionaryEngine.compressWithDict(target, bigDict)
        val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, bigDict)

        assertArrayEquals(target, decompressed)
    }

    @Test
    fun `falls back to standard deflate when dictionary provides no benefit`() {
        val random = Random(42)
        val target = ByteArray(2000) { random.nextInt(256).toByte() }
        val unrelatedDict = ByteArray(2000) { random.nextInt(256).toByte() }

        val compressed = DeflateDictionaryEngine.compressWithDict(target, unrelatedDict)
        val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, unrelatedDict)

        assertArrayEquals(target, decompressed)
    }

    @Test
    fun `handles empty target bytes`() {
        val dict = "dictionary content".toByteArray()
        val compressed = DeflateDictionaryEngine.compressWithDict(ByteArray(0), dict)
        val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, dict)
        assertArrayEquals(ByteArray(0), decompressed)
    }

    @Test
    fun `max dict size constant is 32KB`() {
        assertEquals(32768, DeflateDictionaryEngine.MAX_DICT_SIZE)
    }

    @Test
    fun `different compression levels still round trip correctly`() {
        val dict = "reference material for compression ".repeat(20).toByteArray()
        val target = "reference material for compression, with a twist added.".toByteArray()

        for (level in listOf(0, 1, 5, 9)) {
            val compressed = DeflateDictionaryEngine.compressWithDict(target, dict, level)
            val decompressed = DeflateDictionaryEngine.decompressWithDict(compressed, dict)
            assertArrayEquals(target, decompressed, "Failed round trip at level $level")
        }
    }
}