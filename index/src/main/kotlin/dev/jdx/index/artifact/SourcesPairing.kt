package dev.jdx.index.artifact

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The result of pairing a binary jar with its sources (PROPOSAL.md §5.2).
 *
 * Structure always comes from bytecode (D-009), so a missing pair is routine, not an
 * error — most jars on a classpath ship without one.
 */
public sealed interface SourcesPair {

    /** A `-sources.jar` file found for the binary. */
    public data class External(val path: Path) : SourcesPair

    /** No separate jar, but the binary jar itself ships `.java`/`.kt` entries. */
    public data object Embedded : SourcesPair

    /** No sources of any kind were found. */
    public data object Absent : SourcesPair
}

/**
 * The five pairing rules from PROPOSAL.md §5.2, in the order they are attempted:
 *
 * 1. sibling `<name>-sources.jar` next to the binary;
 * 2. Gradle cache layout (`modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/…`);
 * 3. Maven cache layout (same directory under `~/.m2/repository`);
 * 4. explicit `--sources <path>` override;
 * 5. sources embedded in the binary jar itself.
 *
 * Rules 2 and 3 share one implementation: the wanted file is `<stem>-sources.jar`, where
 * `<stem>` is the binary name minus `.jar`, sought in the binary's own directory and then
 * in every sibling directory under the same parent (the `<version>/` level of both cache
 * layouts). Constraining the match to the same stem matters: a flat `lib/` dir full of
 * unrelated jars must never pair `foo.jar` with `bar-sources.jar`.
 *
 * Rule 4 reads as fourth in the proposal but is implemented first: it is an *override*,
 * and an override that loses to auto-detection is not one. Behaviour is identical whenever
 * no explicit path is given.
 */
public object SourcesPairing {

    /**
     * Pairs [binaryJar] with its sources, returning [SourcesPair.Absent] (never throwing)
     * when nothing is found — a missing pair is routine. An [explicit] path that names a
     * real file always wins; an explicit path that does not exist is ignored the same way.
     */
    public fun pair(binaryJar: Path, explicit: Path? = null): SourcesPair {
        if (explicit != null && Files.isRegularFile(explicit)) {
            return SourcesPair.External(explicit.toAbsolutePath().normalize())
        }
        val fileName = binaryJar.fileName?.toString() ?: return SourcesPair.Absent
        if (!fileName.endsWith(".jar")) return SourcesPair.Absent
        val stem = fileName.removeSuffix(".jar")
        val wanted = "$stem-sources.jar"
        val dir = binaryJar.parent ?: return SourcesPair.Absent

        val sibling = dir.resolve(wanted)
        if (Files.isRegularFile(sibling)) {
            return SourcesPair.External(sibling.toAbsolutePath().normalize())
        }
        // Rules 2 + 3: sibling sha1-dirs under the same `<version>/` parent. Covers both
        // the Gradle cache layout and a Maven repository layout with one walk.
        val versionDir = dir.parent
        if (versionDir != null && Files.isDirectory(versionDir)) {
            Files.newDirectoryStream(versionDir).use { children ->
                for (child in children) {
                    if (!Files.isDirectory(child) || child == dir) continue
                    val candidate = child.resolve(wanted)
                    if (Files.isRegularFile(candidate)) {
                        return SourcesPair.External(candidate.toAbsolutePath().normalize())
                    }
                }
            }
        }
        if (hasEmbeddedSources(binaryJar)) return SourcesPair.Embedded
        return SourcesPair.Absent
    }

    /**
     * `true` when the jar itself carries source text (`.java` or `.kt` entries).
     * An unreadable jar answers `false` — pairing degrades to [SourcesPair.Absent].
     */
    public fun hasEmbeddedSources(binaryJar: Path): Boolean {
        if (!Files.isRegularFile(binaryJar)) return false
        return try {
            ZipFile(binaryJar.toFile()).use { zip ->
                zip.entries().asSequence().any { entry ->
                    val name = ZipSafety.normalizeEntryName(entry.name) ?: return@any false
                    name.endsWith(".java") || name.endsWith(".kt")
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
