package dev.jdx.index.artifact

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Zip-entry validation and decompression caps (D-017).
 *
 * Every entry name read out of a jar passes through [normalizeEntryName]; every byte stream
 * out of a jar passes through [readCapped]. Real jars are nowhere near these caps — they
 * exist so a malicious or corrupt artifact fails fast instead of filling the disk or the
 * heap. The fault-injection suite (T-057) owns the adversarial coverage; T-007 owns the
 * mechanism.
 */
public object ZipSafety {

    /** More entries than any real dependency jar holds; beyond this the file is hostile. */
    public const val MAX_ENTRY_COUNT: Int = 100_000

    /** Largest single class/resource entry accepted (256 MiB). */
    public const val MAX_SINGLE_ENTRY_BYTES: Long = 256L * 1024 * 1024

    /** Largest total declared uncompressed size accepted for one jar (1 GiB). */
    public const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 1_000_000_000L

    /**
     * Normalises a raw zip entry name to a forward-slash relative path, or returns `null`
     * when the name is unsafe: empty, absolute, or escaping its root via `..`.
     *
     * Backslashes are treated as separators (jars built on Windows carry them); `.`
     * segments are dropped; anything else — including `$`, unicode, spaces — passes through
     * untouched, so obfuscated and internationalised artifacts keep working.
     */
    public fun normalizeEntryName(raw: String): String? {
        if (raw.isEmpty()) return null
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) return null
        // A Windows drive prefix (`C:/…`) is absolute for our purposes.
        if (unified.length >= 3 && unified[1] == ':' && unified[2] == '/') return null
        val kept = ArrayDeque<String>()
        for (segment in unified.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> return null
                else -> kept.add(segment)
            }
        }
        if (kept.isEmpty()) return null
        return kept.joinToString("/")
    }

    /**
     * Rejects an entry count no honest artifact reaches.
     *
     * @throws ArtifactReadException when [count] exceeds [MAX_ENTRY_COUNT].
     */
    public fun checkEntryCount(count: Int, artifact: String): Unit {
        if (count > MAX_ENTRY_COUNT) {
            throw ArtifactReadException(
                "artifact read error: $artifact holds $count entries " +
                    "(cap $MAX_ENTRY_COUNT) — refusing a probable zip bomb",
            )
        }
    }

    /**
     * Rejects a declared entry size no honest class file reaches. Entries with unknown
     * declared size (`-1`) pass here and are enforced by [readCapped] instead.
     *
     * @throws ArtifactReadException when [declaredSize] exceeds [MAX_SINGLE_ENTRY_BYTES].
     */
    public fun checkDeclaredSize(entry: String, declaredSize: Long, artifact: String): Unit {
        if (declaredSize >= 0 && declaredSize > MAX_SINGLE_ENTRY_BYTES) {
            throw ArtifactReadException(
                "artifact read error: $artifact!$entry declares $declaredSize bytes " +
                    "(cap $MAX_SINGLE_ENTRY_BYTES) — refusing a probable zip bomb",
            )
        }
    }

    /**
     * Rejects a total declared uncompressed size no honest dependency jar reaches.
     * Only entries with known sizes contribute; unknown sizes are capped per-read.
     *
     * @throws ArtifactReadException when [totalBytes] exceeds [MAX_TOTAL_UNCOMPRESSED_BYTES].
     */
    public fun checkTotalSize(totalBytes: Long, artifact: String): Unit {
        if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            throw ArtifactReadException(
                "artifact read error: $artifact declares $totalBytes uncompressed bytes " +
                    "(cap $MAX_TOTAL_UNCOMPRESSED_BYTES) — refusing a probable zip bomb",
            )
        }
    }

    /**
     * Reads [stream] fully, throwing when it yields more than [cap] bytes. The one extra
     * byte is read deliberately: it distinguishes "exactly at the cap" (accepted) from
     * "over the cap" (rejected) without buffering the whole hostile entry.
     *
     * @throws ArtifactReadException when the stream exceeds [cap] bytes.
     */
    public fun readCapped(stream: InputStream, entry: String, cap: Long = MAX_SINGLE_ENTRY_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > cap) {
                throw ArtifactReadException(
                    "artifact read error: $entry exceeds $cap bytes " +
                        "— refusing a probable zip bomb",
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
