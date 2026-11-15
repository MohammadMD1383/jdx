package dev.jdx.index.artifact

/**
 * `META-INF/services` path helpers and the ServiceLoader file grammar
 * (issue #25 / JLS §9.6.1). Pure — no IO — so jar and directory roots share
 * one parser and tier-1 tests can feed hostile bytes without opening a zip.
 *
 * Only **base** `META-INF/services/<iface>` paths are servable: versioned
 * copies under `META-INF/versions/<n>/…` are ignored in this slice (SPI files
 * almost always live at the jar root; multi-release resource selection is a
 * later slice of issue #25).
 */
public object ServiceEntries {

    /** Prefix every classic ServiceLoader registration lives under. */
    public const val PREFIX: String = "META-INF/services/"

    /**
     * `true` when [path] is a base (non-versioned) service-file entry whose
     * trailing segment is a non-empty service interface name.
     */
    public fun isServicePath(path: String): Boolean {
        if (!path.startsWith(PREFIX)) return false
        if (path.startsWith(MultiRelease.VERSIONS_PREFIX)) return false
        val name = path.removePrefix(PREFIX)
        return name.isNotEmpty() && !name.contains('/') && !name.endsWith("/")
    }

    /**
     * The service interface FQN encoded in [path], or `null` when the path is
     * not a base service entry.
     */
    public fun serviceName(path: String): String? =
        if (isServicePath(path)) path.removePrefix(PREFIX) else null

    /**
     * Parses a ServiceLoader provider file: one fully-qualified provider class
     * per line, `#` starts a comment, surrounding whitespace ignored. Empty
     * names and duplicate lines are dropped; order is first-seen (deterministic
     * for identical bytes). Never throws — corrupt UTF-8 becomes the
     * replacement character and is still split into lines.
     */
    public fun parseProviders(bytes: ByteArray): List<String> {
        val text = bytes.toString(Charsets.UTF_8)
        val seen = linkedSetOf<String>()
        for (rawLine in text.lineSequence()) {
            val withoutComment = rawLine.substringBefore('#').trim()
            if (withoutComment.isEmpty()) continue
            // Binary names only: reject anything that looks like a path or
            // descriptor so a hostile file cannot invent odd output rows.
            if (!isBinaryClassName(withoutComment)) continue
            seen.add(withoutComment)
        }
        return seen.toList()
    }

    /**
     * A provider / service name is a binary class name: segments separated by
     * `.`, each starting with a letter or `_`/`$`, never empty. Keeps the
     * listing free of path-like noise from a crafted services file.
     */
    internal fun isBinaryClassName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('.') || name.endsWith('.')) return false
        for (segment in name.split('.')) {
            if (segment.isEmpty()) return false
            val first = segment[0]
            if (!(first.isLetter() || first == '_' || first == '$')) return false
            for (i in 1 until segment.length) {
                val c = segment[i]
                if (!(c.isLetterOrDigit() || c == '_' || c == '$')) return false
            }
        }
        return true
    }
}
