package dev.jdx.index.maven

import dev.jdx.core.model.MavenCoordinate
import dev.jdx.index.artifact.SourcesPairing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves `group:artifact:version` to jar files (PROPOSAL.md §13, T-019).
 *
 * Lookup order:
 * 1. the fetch cache (`~/.cache/jdx/m2`, `~/.m2`-shaped — our own past downloads);
 * 2. the Gradle files cache (`modules-2/files-2.1/<group>/<name>/<version>/…`);
 * 3. the local Maven repository (`~/.m2/repository`);
 * 4. Maven Central into `~/.cache/jdx/m2` — **only when [allowFetch]**, opt-in per
 *    invocation via `--fetch` (D-006), with SHA-1 verification and the `-sources.jar`
 *    fetched alongside the binary.
 *
 * Every entry point is total: failures read as [Outcome.Unresolved] (callers map
 * them to exit 3 for malformed coordinates, exit 5 for missing/unfetchable ones),
 * never as throws (TESTING.md §7).
 */
public object MavenResolver {

    /** Where local repositories live (all nullable — a missing root reads as empty). */
    public data class Repositories(
        public val gradleFilesRoot: Path? = defaultGradleFilesRoot(),
        public val m2Repo: Path? = defaultM2Repo(),
        public val fetchCacheRoot: Path? = defaultFetchCacheRoot(),
        public val repoBaseUrl: String = MavenCoords.CENTRAL_BASE_URL,
    )

    /** One resolved coordinate: the binary jar plus its sources when found. */
    public data class ResolvedArtifact(
        /** Absolute, normalised binary jar path. */
        val binaryJar: Path,
        /** Absolute, normalised sources jar path, or `null` when none exists. */
        val sourcesJar: Path?,
        /** True when the binary came from the network this invocation. */
        val fetched: Boolean,
    )

    /**
     * The resolution outcome: `Resolved` jars to query, or `Unresolved` with a
     * message already naming the problem and the fix. [fetchHint] is true only
     * when the artifact is absent locally and fetching was *not* allowed — the
     * caller then names `--fetch`.
     */
    public sealed interface Outcome {
        public data class Resolved(val artifact: ResolvedArtifact) : Outcome
        public data class Unresolved(val message: String, val fetchHint: Boolean) : Outcome
    }

    /** Default `~/.gradle/caches/modules-2/files-2.1`, or null when unreadable. */
    public fun defaultGradleFilesRoot(): Path? = runCatching {
        Paths.get(System.getProperty("user.home")).resolve(".gradle/caches/modules-2/files-2.1")
    }.getOrNull()

    /** Default `~/.m2/repository`, or null when unreadable. */
    public fun defaultM2Repo(): Path? = runCatching {
        Paths.get(System.getProperty("user.home")).resolve(".m2/repository")
    }.getOrNull()

    /** Default `~/.cache/jdx/m2` — the fetch cache, mirroring `doctor`'s cache literals. */
    public fun defaultFetchCacheRoot(): Path? = runCatching {
        Paths.get(System.getProperty("user.home")).resolve(".cache/jdx/m2")
    }.getOrNull()

    /**
     * Resolves one coordinate text. [fetcher] defaults to live HTTP; tests inject
     * a fake serving canned bytes, so resolution tests never touch the network.
     */
    public fun resolve(
        coordinateText: String,
        allowFetch: Boolean,
        repositories: Repositories = Repositories(),
        fetcher: MavenFetch.Fetcher = MavenFetch.httpFetcher(),
    ): Outcome {
        val coordinate = MavenCoords.parse(coordinateText)
            ?: return Outcome.Unresolved(MavenCoords.invalidReason(coordinateText), fetchHint = false)
        return resolveParsed(coordinate, allowFetch, repositories, fetcher)
    }

    /** Resolves an already-parsed coordinate (e.g. a `g:a:v/` ref prefix). */
    public fun resolveParsed(
        coordinate: MavenCoordinate,
        allowFetch: Boolean,
        repositories: Repositories = Repositories(),
        fetcher: MavenFetch.Fetcher = MavenFetch.httpFetcher(),
    ): Outcome {
        return try {
            findLocal(coordinate, repositories)?.let { return Outcome.Resolved(it) }
            if (!allowFetch) {
                return Outcome.Unresolved(
                    "Maven coordinate '${MavenCoords.format(coordinate)}' is not in the local " +
                        "caches (${describeRoots(repositories)}). Re-run with --fetch to download " +
                        "it (and its -sources.jar) from Maven Central into ~/.cache/jdx/m2 " +
                        "with checksum verification.",
                    fetchHint = true,
                )
            }
            fetch(coordinate, repositories, fetcher)?.let { return Outcome.Resolved(it) }
            Outcome.Unresolved(
                "cannot fetch Maven coordinate '${MavenCoords.format(coordinate)}' " +
                    "from ${repositories.repoBaseUrl} (network unreachable, artifact missing, " +
                    "or checksum rejected — nothing was written)",
                fetchHint = false,
            )
        } catch (e: Exception) {
            Outcome.Unresolved(
                "cannot resolve Maven coordinate '${MavenCoords.format(coordinate)}': " +
                    (e.message ?: e.javaClass.simpleName),
                fetchHint = false,
            )
        }
    }

    /**
     * Resolves several coordinate texts (a `--coord` list), stopping at the first
     * failure. Success carries one [ResolvedArtifact] per coordinate, in order.
     */
    public fun resolveAll(
        coordinateTexts: List<String>,
        allowFetch: Boolean,
        repositories: Repositories = Repositories(),
        fetcher: MavenFetch.Fetcher = MavenFetch.httpFetcher(),
    ): ResolveAllOutcome {
        val resolved = ArrayList<ResolvedArtifact>(coordinateTexts.size)
        for (text in coordinateTexts) {
            when (val outcome = resolve(text, allowFetch, repositories, fetcher)) {
                is Outcome.Resolved -> resolved.add(outcome.artifact)
                is Outcome.Unresolved -> return ResolveAllOutcome.Failed(outcome.message, outcome.fetchHint)
            }
        }
        return ResolveAllOutcome.Ok(resolved)
    }

    /** The multi-coordinate outcome: all jars, or the first failure's message. */
    public sealed interface ResolveAllOutcome {
        public data class Ok(val artifacts: List<ResolvedArtifact>) : ResolveAllOutcome
        public data class Failed(val message: String, val fetchHint: Boolean) : ResolveAllOutcome
    }

    // -- local lookup -----------------------------------------------------------

    private fun findLocal(coordinate: MavenCoordinate, repositories: Repositories): ResolvedArtifact? {
        // Our own fetch cache first (exact `~/.m2` layout, so the pairing below
        // finds a previously fetched `-sources.jar` as a sibling).
        repositories.fetchCacheRoot?.let { root ->
            findM2Shaped(coordinate, root)?.let { return it.copy(fetched = false) }
        }
        repositories.gradleFilesRoot?.let { root ->
            findGradle(coordinate, root)?.let { return it }
        }
        repositories.m2Repo?.let { root ->
            findM2Shaped(coordinate, root)?.let { return it }
        }
        return null
    }

    private fun withSources(binary: Path): ResolvedArtifact {
        val sources = when (val pair = SourcesPairing.pair(binary)) {
            is dev.jdx.index.artifact.SourcesPair.External -> pair.path
            else -> null
        }
        return ResolvedArtifact(
            binaryJar = binary.toAbsolutePath().normalize(),
            sourcesJar = sources,
            fetched = false,
        )
    }

    /**
     * `~/.m2`-shaped lookup (local repository and our fetch cache share the
     * layout): the exact `<name>-<version>.jar` in the version directory.
     */
    internal fun findM2Shaped(coordinate: MavenCoordinate, repoRoot: Path): ResolvedArtifact? {
        return try {
            val binary = MavenCoords.m2BinaryPath(repoRoot, coordinate)
            if (!Files.isRegularFile(binary)) return null
            withSources(binary)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gradle files-cache lookup: `modules-2/files-2.1/<group>/<name>/<version>/`
     * walked two levels for jars. Prefers the exact `<name>-<version>.jar`;
     * otherwise the first sorted binary jar — `-sources`/`-javadoc` excluded.
     * (Mirrors `ProjectDiscovery`'s walk without depending on its privates.)
     */
    internal fun findGradle(coordinate: MavenCoordinate, filesRoot: Path): ResolvedArtifact? {
        return try {
            val versionDir = filesRoot.resolve(coordinate.group)
                .resolve(coordinate.artifact)
                .resolve(coordinate.version)
            if (!Files.isDirectory(versionDir)) return null
            val jars = mutableListOf<Path>()
            Files.walk(versionDir, 2).use { walk ->
                walk.filter { Files.isRegularFile(it) }
                    .map { it.toAbsolutePath().normalize() }
                    .filter { it.toString().endsWith(".jar") && !isSourcesOrJavadoc(it.fileName.toString()) }
                    .sorted()
                    .forEach { jars.add(it) }
            }
            if (jars.isEmpty()) return null
            val exact = "${coordinate.artifact}-${coordinate.version}.jar"
            withSources(jars.firstOrNull { it.fileName.toString() == exact } ?: jars.first())
        } catch (e: Exception) {
            null
        }
    }

    private fun isSourcesOrJavadoc(fileName: String): Boolean =
        fileName.endsWith("-sources.jar") || fileName.endsWith("-javadoc.jar")

    // -- fetching ---------------------------------------------------------------

    private fun fetch(
        coordinate: MavenCoordinate,
        repositories: Repositories,
        fetcher: MavenFetch.Fetcher,
    ): ResolvedArtifact? {
        val cacheRoot = repositories.fetchCacheRoot ?: return null
        val binaryName = MavenCoords.binaryFileName(coordinate)
        val binaryUrl = MavenCoords.downloadUrl(coordinate, binaryName, repositories.repoBaseUrl)
        val binaryTarget = MavenCoords.m2BinaryPath(cacheRoot, coordinate)
        // A half-written target from a killed earlier run must never read as a hit:
        // only a checksum-verified download lands here, and it lands atomically.
        when (val downloaded = MavenFetch.fetchVerified(binaryUrl, fetcher)) {
            is MavenFetch.FetchResult.Failed -> return null
            is MavenFetch.FetchResult.Ok -> {
                if (MavenFetch.writeAtomically(binaryTarget, downloaded.bytes) != null) return null
            }
        }
        // Sources ride alongside, best-effort: a missing `-sources.jar` degrades
        // the query to bytecode, never fails it.
        val sourcesName = MavenCoords.sourcesFileName(coordinate)
        val sourcesUrl = MavenCoords.downloadUrl(coordinate, sourcesName, repositories.repoBaseUrl)
        val sourcesTarget = MavenCoords.m2SourcesPath(cacheRoot, coordinate)
        if (!Files.isRegularFile(sourcesTarget)) {
            when (val downloaded = MavenFetch.fetchVerified(sourcesUrl, fetcher)) {
                is MavenFetch.FetchResult.Ok ->
                    MavenFetch.writeAtomically(sourcesTarget, downloaded.bytes)
                is MavenFetch.FetchResult.Failed -> Unit
            }
        }
        if (!Files.isRegularFile(binaryTarget)) return null
        return ResolvedArtifact(
            binaryJar = binaryTarget.toAbsolutePath().normalize(),
            sourcesJar = if (Files.isRegularFile(sourcesTarget)) {
                sourcesTarget.toAbsolutePath().normalize()
            } else {
                null
            },
            fetched = true,
        )
    }

    private fun describeRoots(repositories: Repositories): String {
        val roots = listOfNotNull(
            repositories.gradleFilesRoot?.let { "~/.gradle/caches" },
            repositories.m2Repo?.let { "~/.m2/repository" },
            repositories.fetchCacheRoot?.let { "~/.cache/jdx/m2" },
        )
        return if (roots.isEmpty()) "no local repositories readable" else roots.joinToString(", ")
    }
}

/**
 * One coordinate resolution behind `JdxService`'s `g:a:v/` ref prefix,
 * injectable so service tests resolve without IO (same seam pattern as
 * `ProjectDiscoveryFn`). Production is [productionMavenResolve].
 */
public typealias MavenResolveFn = (
    coordinateText: String,
    allowFetch: Boolean,
) -> MavenResolver.Outcome

/** The production [MavenResolveFn]: [MavenResolver] over the default repositories. */
public fun productionMavenResolve(coordinateText: String, allowFetch: Boolean): MavenResolver.Outcome =
    MavenResolver.resolve(coordinateText, allowFetch)
