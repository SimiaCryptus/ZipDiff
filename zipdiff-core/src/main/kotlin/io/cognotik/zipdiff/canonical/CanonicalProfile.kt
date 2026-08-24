package io.cognotik.zipdiff.canonical

/**
 * Parameters and rules for ZIP canonicalization.
 *
* @property compressionLevel Compression level for DEFLATED entries (-1 or 0..9).
 * @property timestampEpochSeconds Normalized timestamp in epoch seconds for entries.
 * @property normalizePermissions Whether to normalize POSIX file permissions.
 * @property preserveStored Whether STORED entries should remain STORED instead of being forced to DEFLATED.
 */
data class CanonicalProfile(
    val compressionLevel: Int = 9,
    val timestampEpochSeconds: Long = 0L,
    val normalizePermissions: Boolean = true,
    val preserveStored: Boolean = false
) {
    init {
        require(compressionLevel == -1 || compressionLevel in 0..9) {
            "compressionLevel must be -1 or in range 0..9, but was $compressionLevel"
        }
    }
}