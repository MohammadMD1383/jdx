package dev.jdx.index.artifact

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Content hashing for artifacts (PROPOSAL.md §5.2, §10.2).
 *
 * The hash is the cache key: an artifact indexed once is reused by every workspace that
 * contains it, and a changed file is trivially a different artifact. Algorithm is SHA-256
 * truncated to 128 bits (32 hex chars) — enough against accidental collision, faster to
 * compare and store than the full digest. This is an identity key, not a security boundary.
 */
public object ArtifactHash {

    /** Full SHA-256 of [bytes], 64 lowercase hex chars. */
    public fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Content id of [bytes]: SHA-256 truncated to 128 bits, 32 lowercase hex chars. */
    public fun shortHashHex(bytes: ByteArray): String = sha256Hex(bytes).substring(0, 32)

    /**
     * Content id of one file, streamed so multi-hundred-megabyte jars never sit fully in
     * memory. Stable across runs and processes for an unchanged file (T-007 acceptance).
     */
    public fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    /**
     * Content id of a class directory: every regular file contributes its slash-separated
     * relative path, its size, and its bytes, in sorted-path order — so renames, edits,
     * additions and removals all change the id, while iteration order and timestamps do
     * not affect it.
     */
    public fun hashDirectory(dir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                .map { dir.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .forEach { relative ->
                    digest.update(relative.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    val file = dir.resolve(relative.replace('/', java.io.File.separatorChar))
                    val size = Files.size(file)
                    digest.update(size.toString().toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    Files.newInputStream(file).use { stream ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.substring(0, 32)
    }
}
