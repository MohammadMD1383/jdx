package dev.jdx.index.workspace

import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.index.artifact.ArtifactHash
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Project auto-discovery (PROPOSAL.md §13 step 4, T-016).
 *
 * When a read query names no workspace (`-w`, `JDX_WORKSPACE`, `jdx ws use` are all
 * absent), `jdx` walks up from the working directory looking for build markers and, on
 * a hit, assembles binary roots from the project itself plus the dependency jars its
 * lock/metadata files reference. The running JDK is still appended unless `--no-jdk`.
 *
 * What this deliberately does **not** do (see D-030):
 * - never runs Gradle or Maven — coordinates come from `gradle.lockfile`, the
 *   `*.gradle(.kts)` scripts' quoted `group:name:version` literals, and `pom.xml`;
 * - never performs the proposal's "broader cache scan" fallback: scanning every cached
 *   jar would be non-deterministic and slow, so an undeterminable dependency set reads
 *   as "project classes only" plus a labelled [WarningCode.PROJECT_DISCOVERY_FALLBACK];
 * - never contributes source dirs: v1 workspaces hold binary roots only (`ws create
 *   --src` still names T-031).
 *
 * Every entry point is total: I/O failures read as "no project" / "no coordinates" /
 * "no jars", never as throws, so a broken project file degrades a query instead of
 * aborting it (TESTING.md §7).
 */
public object ProjectDiscovery {

    /** Build markers, in precedence order: the most project-root-like file wins per directory. */
    public val BUILD_MARKERS: List<String> = listOf(
        "settings.gradle.kts",
        "settings.gradle",
        "build.gradle.kts",
        "build.gradle",
        "pom.xml",
        ".idea",
    )

    /** Lock/metadata files scanned for `group:name:version` coordinates (never executed). */
    private val COORDINATE_FILES: List<String> = listOf(
        "gradle.lockfile",
        "build.gradle.kts",
        "build.gradle",
        "settings.gradle.kts",
        "settings.gradle",
    )

    /**
     * Class-output directories, most specific first. Deduplicated to the *deepest*
     * existing dirs below: entries in a [dev.jdx.index.artifact.DirArtifact] are
     * relative to the opened root, so only a true package root
     * (`build/classes/java/main`, not `build/classes`) lists usable entries.
     */
    private val CLASS_DIR_CANDIDATES: List<String> = listOf(
        "build/classes/java/main",
        "build/classes/kotlin/main",
        "build/classes",
        "target/classes",
    )

    /** Upper bound on resolved dependency jars: a project referencing more is pathological. */
    private const val MAX_DEPENDENCY_JARS: Int = 2_000

    /** A project root plus the marker that identified it. */
    public data class DiscoveredProject(val root: Path, val marker: String)

    /** A `group:name:version` dependency coordinate. */
    public data class MavenCoordinate(val group: String, val name: String, val version: String)

    /** The derived binary roots behind one auto-discovered project. */
    public data class DerivedRoots(
        /** Existing class dirs first (own code shadows dependencies), then jars. Sorted. */
        val jars: List<String>,
        /** Present when the dependency set could not be determined (empty coordinates). */
        val fallbackWarning: Warning? = null,
    )

    /**
     * Walks up from [start] (a directory, or a file whose parent is used) returning the
     * nearest directory containing a build marker, or `null` when none exists.
     * Never throws.
     */
    public fun findProjectRoot(start: Path): DiscoveredProject? {
        return try {
            var dir = start.toAbsolutePath().normalize()
            if (Files.isRegularFile(dir)) dir = dir.parent ?: return null
            generateSequence(dir) { it.parent }.firstNotNullOfOrNull { candidate ->
                BUILD_MARKERS.firstOrNull { Files.exists(candidate.resolve(it)) }
                    ?.let { DiscoveredProject(candidate, it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts `group:name:version` triples from free text (lockfiles, Gradle scripts).
     * Pure, sorted, deduplicated — never throws.
     */
    public fun parseLockCoordinates(text: String): List<MavenCoordinate> {
        return try {
            Regex("[\"']?([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+)[\"']?")
                .findAll(text)
                .mapNotNull { match ->
                    val parts = match.groupValues[1].split(":")
                    if (parts.size != 3 || parts.any { it.isEmpty() || it == "." || it == ".." }) {
                        null
                    } else {
                        MavenCoordinate(parts[0], parts[1], parts[2])
                    }
                }
                .toSortedSet(compareBy({ it.group }, { it.name }, { it.version }))
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reads `<dependency>` coordinates from a Maven `pom.xml`. Versions containing
     * property placeholders (`${…}`) are skipped — resolving them would mean evaluating
     * the build, which N3 forbids. Never throws (unreadable/invalid reads as empty).
     */
    public fun parsePomCoordinates(pomFile: Path): List<MavenCoordinate> {
        return try {
            if (!Files.isRegularFile(pomFile)) return emptyList()
            val factory = DocumentBuilderFactory.newInstance()
            // A pom is third-party XML: no doctypes, no external entities, no XInclude.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val document = factory.newDocumentBuilder().parse(pomFile.toFile())
            val dependencies = document.getElementsByTagName("dependency")
            val found = mutableListOf<MavenCoordinate>()
            for (index in 0 until dependencies.length) {
                val node = dependencies.item(index) ?: continue
                val children = node.childNodes
                var group: String? = null
                var name: String? = null
                var version: String? = null
                for (child in 0 until children.length) {
                    val element = children.item(child) ?: continue
                    when (element.nodeName) {
                        "groupId" -> group = element.textContent?.trim()
                        "artifactId" -> name = element.textContent?.trim()
                        "version" -> version = element.textContent?.trim()
                    }
                }
                if (group.isNullOrEmpty() || name.isNullOrEmpty() || version.isNullOrEmpty()) continue
                if (version.contains('$') || version.contains('{')) continue
                found.add(MavenCoordinate(group, name, version))
            }
            found.toSortedSet(compareBy({ it.group }, { it.name }, { it.version })).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves [coordinates] to existing jar files under the Gradle files cache and the
     * local Maven repository. Gradle layout is
     * `modules-2/files-2.1/<group>/<name>/<version>/…/<file>.jar`; Maven layout is
     * `<group-dots-to-slashes>/<name>/<version>/<name>-<version>*.jar`. Files matching
     * (KDoc wording rule: Kotlin block comments nest, so glob patterns here spell the
     * directory and the file as separate segments.)
     * `*-sources.jar`/`*-javadoc.jar` are excluded. Sorted, deduplicated, capped.
     * Missing cache roots read as empty. Never throws.
     */
    public fun resolveDependencyJars(
        coordinates: List<MavenCoordinate>,
        gradleFilesRoot: Path?,
        m2Repo: Path?,
    ): List<String> {
        return try {
            val jars = LinkedHashSet<String>()
            for (coordinate in coordinates) {
                if (jars.size >= MAX_DEPENDENCY_JARS) break
                if (gradleFilesRoot != null) {
                    jars.addAll(gradleJarsFor(coordinate, gradleFilesRoot))
                }
                if (m2Repo != null && jars.size < MAX_DEPENDENCY_JARS) {
                    jars.addAll(m2JarsFor(coordinate, m2Repo))
                }
            }
            jars.sorted().take(MAX_DEPENDENCY_JARS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun gradleJarsFor(coordinate: MavenCoordinate, filesRoot: Path): List<String> {
        return try {
            val versionDir = filesRoot.resolve(coordinate.group)
                .resolve(coordinate.name)
                .resolve(coordinate.version)
            if (!Files.isDirectory(versionDir)) return emptyList()
            val found = mutableListOf<String>()
            Files.walk(versionDir, 2).use { walk ->
                walk.filter { Files.isRegularFile(it) }
                    .map { it.toAbsolutePath().normalize().toString() }
                    .filter { it.endsWith(".jar") && !isSourcesOrJavadocJar(it) }
                    .sorted()
                    .forEach { found.add(it) }
            }
            found
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun m2JarsFor(coordinate: MavenCoordinate, m2Repo: Path): List<String> {
        return try {
            val versionDir = coordinate.group.split(".").fold(m2Repo) { dir, segment -> dir.resolve(segment) }
                .resolve(coordinate.name)
                .resolve(coordinate.version)
            if (!Files.isDirectory(versionDir)) return emptyList()
            val prefix = "${coordinate.name}-${coordinate.version}"
            val found = mutableListOf<String>()
            Files.list(versionDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it.toAbsolutePath().normalize() }
                    .filter { path ->
                        val file = path.fileName.toString()
                        file.startsWith(prefix) && file.endsWith(".jar") && !isSourcesOrJavadocJar(file)
                    }
                    .map { it.toString() }
                    .sorted()
                    .forEach { found.add(it) }
            }
            found
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isSourcesOrJavadocJar(fileName: String): Boolean =
        fileName.endsWith("-sources.jar") || fileName.endsWith("-javadoc.jar")

    /**
     * The `group:name:version` coordinates a project's lock/metadata files reference
     * (Gradle lock/scripts plus `pom.xml`), sorted and deduplicated. Empty when the
     * project names no dependencies — or when its files cannot be read. Never throws.
     */
    public fun coordinatesOf(projectRoot: Path): List<MavenCoordinate> {
        return try {
            val root = projectRoot.toAbsolutePath().normalize()
            (
                COORDINATE_FILES.flatMap { file ->
                    val path = root.resolve(file)
                    if (!Files.isRegularFile(path)) return@flatMap emptyList()
                    parseLockCoordinates(runCatching { Files.readString(path) }.getOrDefault(""))
                } + parsePomCoordinates(root.resolve("pom.xml"))
                ).toSortedSet(compareBy({ it.group }, { it.name }, { it.version })).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The [WarningCode.PROJECT_DISCOVERY_FALLBACK] warning for a coordinate-less project. */
    public fun fallbackWarning(projectRoot: Path): Warning = Warning(
        code = WarningCode.PROJECT_DISCOVERY_FALLBACK,
        message = "auto-discovered project at ${projectRoot.toAbsolutePath().normalize()}: " +
            "no dependency coordinates found in lock/metadata files — using project classes only " +
            "(pass --jars for dependencies)",
    )

    /**
     * Derives the binary roots for [projectRoot]: existing class-output dirs first, then
     * the dependency jars referenced by lock/metadata files. When no coordinates are
     * found, dependency jars are omitted and [DerivedRoots.fallbackWarning] explains why
     * (D-030). Only existing paths are returned. Never throws.
     */
    public fun deriveBinaryRoots(
        projectRoot: Path,
        gradleFilesRoot: Path? = null,
        m2Repo: Path? = null,
    ): DerivedRoots {
        return try {
            val root = projectRoot.toAbsolutePath().normalize()
            val existing = CLASS_DIR_CANDIDATES
                .map { root.resolve(it).toString() }
                .filter { Files.isDirectory(Path.of(it)) }
            val classDirs = existing
                // Keep deepest only: `build/classes/java/main` is the package root, while
                // plain `build/classes` above it would list `java/main/…`-prefixed garbage.
                .filter { candidate -> existing.none { other -> other != candidate && other.startsWith("$candidate/") } }
                .sorted()
            val coordinates = coordinatesOf(root)
            val dependencyJars = resolveDependencyJars(coordinates, gradleFilesRoot, m2Repo)
            val fallback = if (coordinates.isEmpty()) fallbackWarning(root) else null
            DerivedRoots(jars = classDirs + dependencyJars, fallbackWarning = fallback)
        } catch (e: Exception) {
            DerivedRoots(
                jars = emptyList(),
                fallbackWarning = Warning(
                    code = WarningCode.PROJECT_DISCOVERY_FALLBACK,
                    message = "auto-discovered project at $projectRoot could not be read: " +
                        "${e.message ?: e.javaClass.simpleName} — using no project roots",
                ),
            )
        }
    }

    /**
     * The cache key for a project root: SHA-256/128 of the normalised absolute path
     * (32 lowercase hex chars — a valid workspace file stem). Content changes are
     * detected by [computeFingerprint], not by this key.
     */
    public fun projectHash(projectRoot: Path): String =
        ArtifactHash.shortHashHex(projectRoot.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))

    /**
     * Content fingerprint of the project's build files (every existing marker, lock and
     * pom file contributes its relative path plus bytes; `.idea` contributes its file
     * listing). Stored beside the cached workspace; a mismatch invalidates the cache.
     * Returns `"unavailable"` when the files cannot be read — the cache then always
     * misses and the project is re-derived, never trusted stale.
     */
    public fun computeFingerprint(projectRoot: Path): String {
        return try {
            val root = projectRoot.toAbsolutePath().normalize()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val watched = (BUILD_MARKERS + COORDINATE_FILES).distinct().sorted()
            for (relative in watched) {
                val file = root.resolve(relative)
                if (relative == ".idea") {
                    if (Files.isDirectory(file)) {
                        Files.walk(file).use { walk ->
                            walk.filter { Files.isRegularFile(it) }
                                .map { root.relativize(it).toString().replace('\\', '/') }
                                .sorted()
                                .forEach {
                                    digest.update(it.toByteArray(Charsets.UTF_8))
                                    digest.update(0)
                                }
                        }
                    } else if (Files.isRegularFile(file)) {
                        digest.update(relative.toByteArray(Charsets.UTF_8))
                        digest.update(0)
                        digest.update(Files.readAllBytes(file))
                        digest.update(0)
                    }
                    continue
                }
                if (!Files.isRegularFile(file)) continue
                digest.update(relative.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(Files.readAllBytes(file))
                digest.update(0)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unavailable"
        }
    }
}

/**
 * On-disk cache for derived project workspaces (PROPOSAL.md §13 step 4).
 *
 * Layout under [cacheBase] (production: `~/.cache/jdx/auto`): `<hash>.toml` holds the
 * derived [WorkspaceDefinition] (name == hash, per the [WorkspaceToml] stem rule) and
 * `<hash>.fingerprint` holds [ProjectDiscovery.computeFingerprint]. A cache entry loads
 * only when the fingerprint still matches — any build-file change invalidates it.
 * Total: corrupt/missing/stale entries read as `null` (re-derive), never as throws.
 */
public object ProjectCache {

    /** Suffix of the derived-workspace file for a project hash. */
    public const val WORKSPACE_SUFFIX: String = ".toml"

    /** Suffix of the fingerprint sidecar for a project hash. */
    public const val FINGERPRINT_SUFFIX: String = ".fingerprint"

    public fun cacheFile(cacheBase: Path, projectHash: String): Path =
        cacheBase.resolve("$projectHash$WORKSPACE_SUFFIX")

    public fun fingerprintFile(cacheBase: Path, projectHash: String): Path =
        cacheBase.resolve("$projectHash$FINGERPRINT_SUFFIX")

    /** Loads the cached workspace when its fingerprint still matches [projectRoot]. */
    public fun load(cacheBase: Path, projectHash: String, projectRoot: Path): WorkspaceDefinition? {
        return try {
            val fingerprintPath = fingerprintFile(cacheBase, projectHash)
            val workspacePath = cacheFile(cacheBase, projectHash)
            if (!Files.isRegularFile(fingerprintPath) || !Files.isRegularFile(workspacePath)) return null
            val stored = Files.readString(fingerprintPath).trim()
            if (stored.isEmpty() || stored != ProjectDiscovery.computeFingerprint(projectRoot)) return null
            val decoded = WorkspaceToml.decode(Files.readString(workspacePath), "$projectHash$WORKSPACE_SUFFIX")
            decoded.getOrNull()?.takeIf { it.name == projectHash }
        } catch (e: Exception) {
            null
        }
    }

    /** Stores [definition] with [fingerprint]. Directories are created on first write. */
    public fun save(cacheBase: Path, definition: WorkspaceDefinition, fingerprint: String) {
        try {
            Files.createDirectories(cacheBase)
            Files.writeString(fingerprintFile(cacheBase, definition.name), "$fingerprint\n")
            Files.writeString(cacheFile(cacheBase, definition.name), WorkspaceToml.encode(definition))
        } catch (e: Exception) {
            // Cache writes are best-effort: a query must never fail for want of a cache.
        }
    }
}
