package io.cognotik.zipdiff.signature

/**
 * Specifies the insertion target within the ZIP archive container for signature storage.
 */
enum class PlacementRule {
    CENTRAL_DIRECTORY_COMMENT,
    EXTRA_FIELD,
    DEDICATED_SECTION,
    META_INF_ENTRY
}

/**
 * Represents a signature block containing signature data, hash target, and placement information.
 */
class SignatureBlock(
    val schemeId: String,
    val canonicalProfileVersion: String,
    val targetZipHash: String,
    signatureData: ByteArray,
    val placementRule: PlacementRule
) {
    private val _signatureData: ByteArray = signatureData.clone()

    /**
     * Returns a defensive copy of the signature binary data.
     */
    val signatureData: ByteArray
        get() = _signatureData.clone()

    operator fun component1(): String = schemeId
    operator fun component2(): String = canonicalProfileVersion
    operator fun component3(): String = targetZipHash
    operator fun component4(): ByteArray = signatureData
    operator fun component5(): PlacementRule = placementRule

    /**
     * Creates a copy of this [SignatureBlock], defensibly cloning the [signatureData] array.
     */
    fun copy(
        schemeId: String = this.schemeId,
        canonicalProfileVersion: String = this.canonicalProfileVersion,
        targetZipHash: String = this.targetZipHash,
        signatureData: ByteArray = this._signatureData,
        placementRule: PlacementRule = this.placementRule
    ): SignatureBlock {
        return SignatureBlock(
            schemeId,
            canonicalProfileVersion,
            targetZipHash,
            signatureData,
            placementRule
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignatureBlock

        if (schemeId != other.schemeId) return false
        if (canonicalProfileVersion != other.canonicalProfileVersion) return false
        if (targetZipHash != other.targetZipHash) return false
        if (!_signatureData.contentEquals(other._signatureData)) return false
        if (placementRule != other.placementRule) return false

        return true
    }

    override fun hashCode(): Int {
        var result = schemeId.hashCode()
        result = 31 * result + canonicalProfileVersion.hashCode()
        result = 31 * result + targetZipHash.hashCode()
        result = 31 * result + _signatureData.contentHashCode()
        result = 31 * result + placementRule.hashCode()
        return result
    }

    override fun toString(): String {
        return "SignatureBlock(schemeId='$schemeId', canonicalProfileVersion='$canonicalProfileVersion', " +
                "targetZipHash='$targetZipHash', signatureData[size=${_signatureData.size}], placementRule=$placementRule)"
    }
}

/**
 * Data class for JSON serialization and deserialization of META-INF/signature-schemes.json.
 */
data class SignatureMetadata(
    val version: String = "1.0",
    val schemes: List<SignatureSchemeEntry> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
) {
    /**
     * Secondary constructor for creating single-scheme metadata.
     */
    constructor(
        schemeId: String,
        canonicalProfileVersion: String,
        placementRule: PlacementRule = PlacementRule.META_INF_ENTRY,
        targetZipHash: String? = null,
        version: String = "1.0",
        attributes: Map<String, String> = emptyMap()
    ) : this(
        version = version,
        schemes = listOf(SignatureSchemeEntry(schemeId, canonicalProfileVersion, placementRule, targetZipHash)),
        attributes = attributes
    )

    /**
     * Returns the primary (first) signature scheme entry, if available.
     */
    val primaryScheme: SignatureSchemeEntry?
        get() = schemes.firstOrNull()

    /**
     * Convenience accessor for the primary scheme's schemeId.
     */
    val schemeId: String
        get() = primaryScheme?.schemeId ?: ""

    /**
     * Convenience accessor for the primary scheme's canonicalProfileVersion.
     */
    val canonicalProfileVersion: String
        get() = primaryScheme?.canonicalProfileVersion ?: ""

    /**
     * Convenience accessor for the primary scheme's placementRule.
     */
    val placementRule: PlacementRule
        get() = primaryScheme?.placementRule ?: PlacementRule.META_INF_ENTRY

    /**
     * Convenience accessor for the primary scheme's targetZipHash.
     */
    val targetZipHash: String?
        get() = primaryScheme?.targetZipHash
}

/**
 * Metadata entry for an individual signature scheme present in the signature manifest.
 */
data class SignatureSchemeEntry(
    val schemeId: String,
    val canonicalProfileVersion: String,
    val placementRule: PlacementRule,
    val targetZipHash: String? = null
)