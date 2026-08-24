package io.cognotik.zipdiff.`package`

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.cognotik.zipdiff.diff.DiffEntry
import io.cognotik.zipdiff.signature.SignatureBlock

/**
 * Metadata describing a patch package.
 *
 * @property baseVersion Identifies the expected source/base version.
 * @property targetVersion Identifies the destination/target version.
 * @property canonicalizationProfileVersion Identifies the canonicalization profile version used.
 * @property canonicalZipSha256 Hex SHA-256 digest of the canonical target archive.
 * @property signatureSchemes List of signature scheme identifiers applied to this package.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatchMetadata(
    @JsonProperty("baseVersion", required = true) val baseVersion: String,
    @JsonProperty("targetVersion", required = true) val targetVersion: String,
    @JsonProperty("canonicalizationProfileVersion", required = true) val canonicalizationProfileVersion: String,
    @JsonProperty("canonicalZipSha256", required = true) val canonicalZipSha256: String,
    @JsonProperty("signatureSchemes") val signatureSchemes: List<String> = emptyList()
)

/**
 * Encapsulates a complete patch package including metadata, diff entries, and optional signature blocks.
 *
 * @property metadata Package metadata.
 * @property diffEntries List of diff entries included in the patch.
 * @property signatureBlocks Optional list of cryptographic signature blocks verifying the package.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatchPackage(
    @JsonProperty("metadata", required = true) val metadata: PatchMetadata,
    @JsonProperty("diffEntries", required = true) val diffEntries: List<DiffEntry>,
    @JsonProperty("signatureBlocks") val signatureBlocks: List<SignatureBlock> = emptyList()
)