package io.cognotik.zipdiff.canonical

import java.nio.file.Path

/**
 * Result of a ZIP canonicalization operation.
 *
 * @property entryCount Number of entries processed in the ZIP archive.
 * @property sha256Hex SHA-256 hash of the canonicalized ZIP file represented as a 64-character hex string.
 */
data class CanonicalResult(
    val entryCount: Int,
    val sha256Hex: String,
    val outputPath: Path
) {
    init {
        require(entryCount >= 0) {
            "entryCount must be non-negative, but was $entryCount"
        }
        require(HEX_REGEX.matches(sha256Hex)) {
            "sha256Hex must be a 64-character hex string, but was '$sha256Hex'"
        }
    }

    companion object {
        private val HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}