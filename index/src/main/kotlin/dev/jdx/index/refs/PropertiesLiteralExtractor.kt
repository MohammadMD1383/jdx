package dev.jdx.index.refs

import dev.jdx.index.artifact.ResourceEntries
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Extracts key/value string pairs from `.properties` resource bytes
 * (issue #86 properties slice).
 *
 * Feeds `jdx literals` alongside bytecode `LDC` / `ConstantValue` hits so a
 * resource-key search finds both the class that loads the key and the bundle
 * that defines it — the dogfood path that previously needed
 * `unzip | tr | grep` across every jar.
 *
 * Never throws: binary / unreadable payloads yield an empty list. Text is
 * preferred as UTF-8 ([ResourceEntries.decodeText]); when that refuses and
 * the bytes have no NUL, falls back to ISO-8859-1 (classic
 * `Properties.load(InputStream)` encoding) so escaped `\uXXXX` bundles still
 * parse. Entries are first-wins deduplicated per `(path, key)`.
 */
public object PropertiesLiteralExtractor {

    /** One property entry found in a resource file. */
    public data class PropOccurrence(
        public val path: String,
        public val key: String,
        public val value: String,
    )

    /**
     * `true` when [path] is a `.properties` resource (case-insensitive).
     * Directories and blank paths are never properties files.
     */
    public fun isPropertiesPath(path: String): Boolean {
        if (path.isEmpty() || path.endsWith("/")) return false
        return path.endsWith(".properties", ignoreCase = true)
    }

    /**
     * Parses every non-empty key from [bytes] at [path]. Never throws:
     * hostile / binary bytes degrade to an empty list.
     */
    public fun extract(path: String, bytes: ByteArray): List<PropOccurrence> {
        if (!isPropertiesPath(path)) return emptyList()
        val text = decodePropertiesText(bytes) ?: return emptyList()
        val props = Properties()
        try {
            props.load(StringReader(text))
        } catch (_: Exception) {
            return emptyList()
        }
        val hits = LinkedHashMap<String, PropOccurrence>()
        for ((rawKey, rawValue) in props.entries) {
            val key = (rawKey as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val value = (rawValue as? String) ?: continue
            if (key !in hits) {
                hits[key] = PropOccurrence(path = path, key = key, value = value)
            }
        }
        // Deterministic: key, then value (path is fixed per call).
        return hits.values.sortedWith(compareBy({ it.key }, { it.value }))
    }

    /**
     * UTF-8 first (matches `resources --cat`); ISO-8859-1 fallback for classic
     * Properties encoding when the bytes are non-NUL but not well-formed UTF-8.
     * NUL anywhere in the probe window → binary → null.
     */
    internal fun decodePropertiesText(bytes: ByteArray): String? {
        ResourceEntries.decodeText(bytes)?.let { return it }
        val probe = minOf(bytes.size, ResourceEntries.BINARY_PROBE_BYTES)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return null
        }
        return try {
            String(bytes, StandardCharsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
    }
}
