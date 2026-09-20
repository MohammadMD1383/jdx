package dev.jdx.decompile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * On-disk decompilation cache (PROPOSAL.md §11.2, §15): agents typically ask
 * for several members of the same class in a row, and decompiling a large
 * class costs 50–500 ms — so the raw decompiled text is stored once per
 * `(class hash, engine, engine version)` and reused by every later query.
 *
 * Layout: `<dir>/<sha256(classBytes)>-<engine>-<version>.java`. The version
 * segment means an engine upgrade never serves a stale reconstruction, and
 * the content hash means a changed class never collides. Reads and writes
 * never throw: a missing or unreadable entry is a cache miss, a failed write
 * is silently skipped (the query still answers — it just pays full price).
 */
public data class DecompileCache(public val dir: Path) {

    /**
     * Returns the cached text for this exact class/engine/version, or `null`
     * on a miss or any read problem. Never throws.
     */
    public fun read(classBytes: ByteArray, engine: String, engineVersion: String): String? {
        return runCatching {
            val file = entryFile(classBytes, engine, engineVersion)
            if (!Files.isRegularFile(file)) return null
            Files.readString(file, Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Stores [text] for a later [read]. Best-effort: writes to a temp sibling
     * then moves atomically, creating the directory on demand. Never throws.
     */
    public fun write(classBytes: ByteArray, engine: String, engineVersion: String, text: String) {
        runCatching {
            Files.createDirectories(dir)
            val target = entryFile(classBytes, engine, engineVersion)
            val tmp = Files.createTempFile(dir, "decompile-", ".tmp")
            try {
                Files.writeString(tmp, text, Charsets.UTF_8)
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: UnsupportedOperationException) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
        }
    }

    /** The cache file for this key. Pure path math, no IO. */
    internal fun entryFile(classBytes: ByteArray, engine: String, engineVersion: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(classBytes)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return dir.resolve("$digest-${safeSegment(engine)}-${safeSegment(engineVersion)}.java")
    }

    public companion object {
        /**
         * The production cache: `~/.cache/jdx/decompile` (the literal
         * `~/.cache/jdx` root per D-027 — no `XDG_*` fallback, mirroring the
         * index and auto-discovery caches).
         */
        public fun system(): DecompileCache =
            DecompileCache(Path.of(System.getProperty("user.home"), ".cache", "jdx", "decompile"))

        private fun safeSegment(raw: String): String {
            val mapped = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
                .joinToString("").take(64).ifEmpty { "_" }
            // Dots are kept for readable versions (`1.12.0`), but a `..` run
            // in a filename invites traversal confusion — collapse it.
            return mapped.replace(Regex("\\.\\.+"), "_")
        }
    }
}
