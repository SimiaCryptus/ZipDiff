package io.cognotik.zipdiff.diff

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Represents the diff status/mode for a ZIP entry.
 */
enum class EntryMode {
    UNCHANGED,
    NEW,
    MODIFIED,
    DELETED,
    EMPTY_FILE
}

/**
 * Represents a logical delta entry in a ZIP diff analysis.
 *
 * @property path Relative path of the entry in the archive.
 * @property mode The diff classification mode ([EntryMode]).
 * @property metadata Additional metadata attributes associated with the entry.
 * @property payloadSupplier Optional supplier for lazy or streaming content access.
 */
class DiffEntry @JvmOverloads constructor(
    val path: String,
    val mode: EntryMode,
    payloadBytes: ByteArray? = null,
    val metadata: Map<String, String> = emptyMap(),
    val payloadSupplier: (() -> InputStream)? = null,
    payloadSize: Long? = null
) {
    private val _payloadBytes: ByteArray? = payloadBytes?.copyOf()

    /**
     * Defensive copy of the payload byte array, or lazily loaded bytes if [payloadSupplier] is present.
     */
    val payloadBytes: ByteArray?
        get() = _payloadBytes?.copyOf() ?: payloadSupplier?.invoke()?.use { it.readBytes() }

    /**
     * Size of the payload in bytes.
     */
    val payloadSize: Long = payloadSize
        ?: metadata["size"]?.toLongOrNull()
        ?: metadata["contentLength"]?.toLongOrNull()
        ?: _payloadBytes?.size?.toLong()
        ?: 0L

    /**
     * Opens an [InputStream] to read the entry payload without making in-memory byte array copies.
     *
     * @return An [InputStream] for reading entry content, or `null` if no payload is present.
     */
    fun openPayloadStream(): InputStream? {
        return payloadSupplier?.invoke() ?: _payloadBytes?.let { ByteArrayInputStream(it) }
    }

    operator fun component1(): String = path
    operator fun component2(): EntryMode = mode
    operator fun component3(): ByteArray? = payloadBytes
    operator fun component4(): Map<String, String> = metadata

    fun copy(
        path: String = this.path,
        mode: EntryMode = this.mode,
        payloadBytes: ByteArray? = this._payloadBytes,
        metadata: Map<String, String> = this.metadata,
        payloadSupplier: (() -> InputStream)? = this.payloadSupplier,
        payloadSize: Long? = this.payloadSize
    ): DiffEntry = DiffEntry(path, mode, payloadBytes, metadata, payloadSupplier, payloadSize)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiffEntry

        if (path != other.path) return false
        if (mode != other.mode) return false
        if (metadata != other.metadata) return false

        val thisBytes = _payloadBytes ?: payloadBytes
        val otherBytes = other._payloadBytes ?: other.payloadBytes

        if (thisBytes != null) {
            if (otherBytes == null) return false
            if (!thisBytes.contentEquals(otherBytes)) return false
        } else if (otherBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + mode.hashCode()
        val bytes = _payloadBytes ?: (if (payloadSupplier != null) payloadBytes else null)
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String {
        val payloadDesc = when {
            _payloadBytes != null -> "${_payloadBytes.size} bytes"
            payloadSupplier != null -> if (payloadSize > 0) "$payloadSize bytes (lazy)" else "lazy stream"
            else -> "none"
        }
        return "DiffEntry(path=$path, mode=$mode, payloadSize=$payloadDesc, metadata=$metadata)"
    }
}