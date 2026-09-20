package dev.jdx.sources

import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * One place Java/Kotlin source text can be read from: a `-sources.jar`
 * (or any jar carrying `.java`/`.kt` entries), a source directory, or
 * `src.zip` (T-071, first slice of T-020).
 *
 * This is the seam every later M3 task reads through: `body`/`source`/`doc`
 * first resolve `binaryName → source file` here, and only then parse (T-021).
 * Structure never comes from here — bytecode is the skeleton, sources are
 * the flesh (D-009) — so a missing file is routine, not an error.
 *
 * Paths are normalised `com/foo/Bar.java` slash paths, sorted, so identical
 * inputs list identical bytes (CLAUDE.md §2.5). Nothing here parses source
 * text; that is T-021's JavaParser work.
 */
public sealed interface SourceRoot : Closeable {

    /** Human label for messages and provenance: a file name or a directory. */
    public val displayName: String

    /**
     * Every servable source entry, normalised slash paths, sorted.
     * Only `.java` and `.kt` files — a mixed jar's `.class` entries never leak.
     */
    public fun sourcePaths(): List<String>

    /**
     * Opens one source entry for reading. The caller closes the stream.
     *
     * @throws SourceReadException when [path] is unsafe or absent.
     */
    public fun openSource(path: String): InputStream

    /**
     * Maps a binary class name to its source file: `$`-nested classes resolve
     * to the outer file, trying `.java` before `.kt`. Returns `null` when this
     * root holds no source for the class — the caller degrades (decompile or
     * bytecode-only), never fails.
     *
     * Linear scan over [sourcePaths] by construction; a lazily built `srcmap`
     * index will lift this when source queries go indexed (PROPOSAL.md §10.4).
     */
    public fun findSource(binaryName: String): String? {
        val available = sourcePaths().toSet()
        return sourceCandidatesFor(binaryName).firstOrNull { it in available }
    }
}

/**
 * Opens [path] as a [SourceRoot]: a jar/zip file reads as a sources jar, a
 * directory reads as a source tree.
 *
 * @throws SourceReadException when [path] names nothing readable.
 */
public fun openSourceRoot(path: Path): SourceRoot {
    if (path.isRegularFile()) return JarSourceRoot(path)
    if (path.isDirectory()) return DirSourceRoot(path)
    throw SourceReadException("source read error: no such source root: $path")
}

/**
 * The `binaryName → source file` mapping, pure so tier 1 can own it (T-071).
 *
 * The outer binary is everything before the first `$` (`com.foo.Outer$Inner`
 * lives in `com/foo/Outer.java`); `.java` is preferred over `.kt` because a
 * Kotlin file facade (`FooKt`) never shares its binary name with a Java file
 * it would shadow. An empty or blank name maps to nothing — callers pass
 * through, never throw.
 */
public fun sourceCandidatesFor(binaryName: String): List<String> {
    if (binaryName.isBlank()) return emptyList()
    val outer = binaryName.substringBefore('$')
    if (outer.isEmpty()) return emptyList()
    val slashPath = outer.replace('.', '/')
    return listOf("$slashPath.java", "$slashPath.kt")
}

/**
 * Normalises a raw zip entry name to a forward-slash relative path, or
 * returns `null` when the name is unsafe: empty, absolute, or escaping its
 * root via `..` (D-017 zip-slip defence).
 *
 * Deliberately twins `ZipSafety.normalizeEntryName` instead of sharing it:
 * `:sources` must not depend on `:index` (both depend only on `:core`), and
 * `core` stays dependency-free so the rule cannot live there either. The two
 * copies pin identical behaviour in their own suites — change one, change both.
 */
internal fun normalizeSourceEntryName(raw: String): String? {
    if (raw.isEmpty()) return null
    val unified = raw.replace('\\', '/')
    if (unified.startsWith("/")) return null
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

/** Failure to open or read a source root (exit 5 at the service layer, D-015). */
public class SourceReadException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * A sources jar (or any jar/zip carrying source entries) read through
 * `java.util.zip` — never a class load, so the D-017 static-initialiser rule
 * holds trivially: no bytecode is even parsed here.
 */
public class JarSourceRoot(private val jar: Path) : SourceRoot {

    override val displayName: String = jar.fileName.toString()

    private val zip = ZipFile(jar.toFile())

    /**
     * Normalised source entry → raw zip entry name. Built once: entry names
     * are normalised exactly once here, so [openSource] cannot be smuggled a
     * raw `../` name past the listing filter.
     */
    private val entries: Map<String, String> by lazy {
        val mapped = LinkedHashMap<String, String>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val raw = enumeration.nextElement()
            if (raw.isDirectory) continue
            val normalised = normalizeSourceEntryName(raw.name) ?: continue
            if (!normalised.endsWith(".java") && !normalised.endsWith(".kt")) continue
            mapped.putIfAbsent(normalised, raw.name)
        }
        mapped
    }

    override fun sourcePaths(): List<String> = entries.keys.sorted()

    override fun openSource(path: String): InputStream {
        val normalised = normalizeSourceEntryName(path)
            ?: throw SourceReadException("source read error: unsafe source path: $path")
        val raw = entries[normalised]
            ?: throw SourceReadException("source read error: no such source: $path in $displayName")
        return try {
            zip.getInputStream(zip.getEntry(raw)) ?: throw SourceReadException(
                "source read error: no such source: $path in $displayName",
            )
        } catch (e: SourceReadException) {
            throw e
        } catch (e: Exception) {
            throw SourceReadException(
                "source read error: cannot read $path from $displayName: ${e.message}",
                e,
            )
        }
    }

    override fun close(): Unit = zip.close()
}

/**
 * A source directory (a Gradle `src/main/java` tree, an unpacked `-sources`
 * jar, `$JAVA_HOME/lib/src.zip` extracted) walked on demand.
 */
public class DirSourceRoot(private val dir: Path) : SourceRoot {

    override val displayName: String = dir.fileName?.toString() ?: dir.toString()

    private val base: Path = dir.toAbsolutePath().normalize()

    override fun sourcePaths(): List<String> {
        val found = mutableListOf<String>()
        Files.walk(base).use { walk ->
            walk.filter { it.isRegularFile() }.forEach { file ->
                val relative = base.relativize(file.toAbsolutePath().normalize()).joinToString("/")
                val normalised = normalizeSourceEntryName(relative) ?: return@forEach
                if (normalised.endsWith(".java") || normalised.endsWith(".kt")) {
                    found.add(normalised)
                }
            }
        }
        return found.sorted()
    }

    override fun openSource(path: String): InputStream {
        val normalised = normalizeSourceEntryName(path)
            ?: throw SourceReadException("source read error: unsafe source path: $path")
        // Mirrors JarSourceRoot: only listed source kinds are servable, so a
        // stray `.class` or text file in the tree is not misread as source.
        if (!normalised.endsWith(".java") && !normalised.endsWith(".kt")) {
            throw SourceReadException("source read error: no such source: $path in $displayName")
        }
        val file = base.resolve(normalised).normalize()
        // Defence in depth: the normaliser already rejects `..`, but the
        // containment check holds even if the normaliser is ever weakened.
        if (!file.startsWith(base) || !file.isRegularFile()) {
            throw SourceReadException("source read error: no such source: $path in $displayName")
        }
        return try {
            Files.newInputStream(file)
        } catch (e: Exception) {
            throw SourceReadException(
                "source read error: cannot read $path from $displayName: ${e.message}",
                e,
            )
        }
    }

    override fun close(): Unit = Unit
}
