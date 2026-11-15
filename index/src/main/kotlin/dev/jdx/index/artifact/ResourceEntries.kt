package dev.jdx.index.artifact

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Non-class resource path + content helpers (issue #25). Pure — no IO —
 * so jar and directory roots share one predicate / decoder and tier-1
 * tests can feed hostile names and bytes without opening a zip.
 *
 * A **resource** is any normalised file entry that is not a `.class` file.
 * That includes `META-INF/MANIFEST.MF`, `META-INF/services/…`,
 * `.properties`, XML, and versioned copies under `META-INF/versions/` —
 * listing shows the path as stored (multi-release selection is left to
 * class loading; this slice does not collapse variants). Directories and
 * `.class` entries are never resources (use `search` / `ls` / `modules`
 * for those).
 *
 * `--cat` content extraction refuses binary payloads (NUL in the probe
 * window, or bytes that are not well-formed UTF-8) so agents never get
 * opaque bytes dumped into context.
 */
public object ResourceEntries {

    /** Bytes inspected for a leading NUL when classifying binary content. */
    public const val BINARY_PROBE_BYTES: Int = 8192

    /**
     * `true` when [path] is a servable non-class file entry. Directories
     * (trailing `/`) and `.class` files are excluded. Empty / blank paths
     * are rejected.
     */
    public fun isResourcePath(path: String): Boolean {
        if (path.isEmpty() || path.endsWith("/")) return false
        if (path.endsWith(".class")) return false
        return true
    }

    /**
     * `true` when [bytes] look binary: a NUL in the first
     * [BINARY_PROBE_BYTES], or not well-formed UTF-8. Empty is text.
     * Never throws.
     */
    public fun isBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val probe = minOf(bytes.size, BINARY_PROBE_BYTES)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return true
        }
        return decodeUtf8(bytes) == null
    }

    /**
     * Decodes [bytes] as strict UTF-8, or `null` when the payload is
     * binary / malformed. Never throws — hostile bytes return `null`.
     */
    public fun decodeText(bytes: ByteArray): String? {
        if (isBinary(bytes)) return null
        return decodeUtf8(bytes)
    }

    private fun decodeUtf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
