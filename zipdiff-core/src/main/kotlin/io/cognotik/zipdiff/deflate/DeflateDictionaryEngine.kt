package io.cognotik.zipdiff.deflate

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Engine for DEFLATE compression and decompression utilizing preset dictionaries.
 *
 * Supports DEFLATE 32KB window limits and handles fallback to standard DEFLATE
 * when preset dictionary compression provides no size benefit.
 */
object DeflateDictionaryEngine {

    /**
     * Maximum dictionary size supported by the DEFLATE window limit (32 KB).
     */
    const val MAX_DICT_SIZE: Int = 32768

    /**
     * Compresses the target byte array using a preset dictionary.
     *
     * If [dictBytes] exceeds [MAX_DICT_SIZE] (32KB), only the trailing 32KB window is used.
     * Automatically falls back to standard DEFLATE compression if preset dictionary
     * compression yields no size benefit.
     *
     * @param targetBytes Uncompressed target data.
     * @param dictBytes Preset dictionary bytes.
     * @param level Compression level (defaults to [Deflater.DEFAULT_COMPRESSION]).
     * @return Compressed byte array.
     */
    fun compressWithDict(
        targetBytes: ByteArray,
        dictBytes: ByteArray,
        level: Int = Deflater.DEFAULT_COMPRESSION
    ): ByteArray {
        val effectiveDict = prepareDictionary(dictBytes)
        val standardCompressed = compress(targetBytes, null, level)

        if (effectiveDict.isEmpty()) {
            return standardCompressed
        }

        val dictCompressed = compress(targetBytes, effectiveDict, level)

        return if (dictCompressed.size < standardCompressed.size) {
            dictCompressed
        } else {
            standardCompressed
        }
    }

    /**
     * Decompresses bytes previously compressed with DEFLATE.
     *
     * Applies the preset dictionary if requested by the compressed stream header.
     * Handles streams compressed with or without a preset dictionary (e.g. standard DEFLATE fallback).
     *
     * @param compressedBytes Compressed input data.
     * @param dictBytes Preset dictionary bytes.
     * @return Decompressed target data.
     */
    fun decompressWithDict(
        compressedBytes: ByteArray,
        dictBytes: ByteArray
    ): ByteArray {
        val effectiveDict = prepareDictionary(dictBytes)
        val inflater = Inflater()
        try {
            inflater.setInput(compressedBytes)
            val outputStream = ByteArrayOutputStream(compressedBytes.size * 2)
            val buffer = ByteArray(8192)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    outputStream.write(buffer, 0, count)
                } else {
                    if (inflater.needsDictionary()) {
                        require(effectiveDict.isNotEmpty()) {
                            "Compressed data requires a preset dictionary, but provided dictionary is empty."
                        }
                        inflater.setDictionary(effectiveDict)
                    } else if (inflater.needsInput()) {
                        break
                    } else {
                        break
                    }
                }
            }
            return outputStream.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun prepareDictionary(dictBytes: ByteArray): ByteArray {
        return if (dictBytes.size > MAX_DICT_SIZE) {
            dictBytes.copyOfRange(dictBytes.size - MAX_DICT_SIZE, dictBytes.size)
        } else {
            dictBytes
        }
    }

    private fun compress(
        targetBytes: ByteArray,
        dictBytes: ByteArray?,
        level: Int
    ): ByteArray {
        val deflater = Deflater(level)
        try {
            if (dictBytes != null && dictBytes.isNotEmpty()) {
                deflater.setDictionary(dictBytes)
            }
            deflater.setInput(targetBytes)
            deflater.finish()

            val outputStream = ByteArrayOutputStream(targetBytes.size)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            return outputStream.toByteArray()
        } finally {
            deflater.end()
        }
    }
}