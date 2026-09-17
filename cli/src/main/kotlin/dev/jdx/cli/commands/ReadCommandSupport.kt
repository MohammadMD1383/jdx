package dev.jdx.cli.commands

import dev.jdx.core.model.Warning
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.maven.MavenCoords
import dev.jdx.index.maven.MavenFetch
import dev.jdx.index.maven.MavenResolveFn
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.maven.productionMavenResolve
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.ProjectCache
import dev.jdx.index.workspace.ProjectDiscovery
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceResolver
import dev.jdx.index.workspace.WorkspaceStore
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Shared plumbing for the three read commands (T-011): `show`, `members`, `outline`.
 *
 * Thin by rule (D-004): flag *values* are mapped to [JdxService] inputs here, and flag
 * *combinations* that make no sense are rejected with an exit-3 usage error before any
 * IO happens. Everything about *behaviour* — resolution, filtering, rendering — lives
 * in `core`/`index` behind [JdxService.ServiceOutcome], which already carries its own
 * exit code and both renderings (D-007).
 *
 * Non-zero outcomes terminate the process via [terminate] (default
 * [exitProcess], mirroring `DoctorCommand`). The no-op choice is deliberate:
 * clikt-core's own `exitProcess` hook defaults to `{ }` — the real one lives in
 * the mordant flavor we excluded (L-011) — so throwing `ProgramResult` alone
 * would exit 0 and silently break the D-015 contract. Tests inject a fake that
 * throws instead of dying with the host JVM.
 */
internal object ReadCommandSupport {

    /** Builds the workspace roots both commands read: repeatable `--jars`, JDK unless dropped. */
    internal fun rootsOf(jars: List<String>, noJdk: Boolean): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = jars, includeJdk = !noJdk)

    /**
     * Resolves the roots one read query opens (PROPOSAL.md §13, T-015/T-016): explicit
     * `--jars` merge in front of the selected workspace (`-w` beats `JDX_WORKSPACE` beats
     * `jdx ws use`), project auto-discovery fills the workspace half when no workspace
     * is selected, the JDK stays unless `--no-jdk` or the workspace switches it off.
     *
     * [store] and [getenv] are injectable so command tests resolve without touching the
     * real home directory or the process environment. [workingDir], [cacheBase],
     * [gradleFilesRoot] and [m2Repo] default to the process environment; tests point
     * them at `@TempDir` trees instead.
     */
    internal fun resolveRoots(
        jars: List<String>,
        noJdk: Boolean,
        workspace: String?,
        store: WorkspaceStore = FileWorkspaceStore.system(),
        getenv: (String) -> String? = System::getenv,
        workingDir: Path = Paths.get("").toAbsolutePath(),
        cacheBase: Path = defaultAutoCacheBase(),
        gradleFilesRoot: Path? = defaultGradleFilesRoot(),
        m2Repo: Path? = defaultM2Repo(),
        discover: ProjectDiscoveryFn = ::discoverProject,
        coords: List<String> = emptyList(),
        allowFetch: Boolean = false,
        mavenRepositories: MavenResolver.Repositories? = null,
        mavenFetcher: MavenFetch.Fetcher? = null,
    ): RootsOrFailure {
        val envWorkspace = try {
            getenv("JDX_WORKSPACE")
        } catch (e: SecurityException) {
            null
        }
        val activeWorkspace = try {
            store.activeName()
        } catch (e: Exception) {
            null
        }
        val repositories = mavenRepositories ?: MavenResolver.Repositories(
            gradleFilesRoot = gradleFilesRoot,
            m2Repo = m2Repo,
            fetchCacheRoot = MavenResolver.defaultFetchCacheRoot(),
        )
        val fetcher = mavenFetcher ?: MavenFetch.httpFetcher()
        // Keep the production function reference (not a fresh lambda) when no
        // test seam is injected: `RootsSpec` is a data class, and command tests
        // assert it by value — a new lambda would break equality by identity.
        val mavenResolve: MavenResolveFn =
            if (mavenRepositories == null && mavenFetcher == null) {
                ::productionMavenResolve
            } else {
                { text, fetch -> MavenResolver.resolve(text, fetch, repositories, fetcher) }
            }
        // Explicit `--coord` values are usage input: malformed ones fail fast
        // with exit 3 before any workspace or network IO happens.
        for (text in coords) {
            if (MavenCoords.parse(text) == null) {
                return RootsOrFailure.Failed(
                    JdxService.ServiceOutcome.Failure(
                        ErrorResult.generic("", exitCode = 3, message = "usage error: ${MavenCoords.invalidReason(text)}"),
                    ),
                )
            }
        }
        // Explicit coordinates resolve now so their jars shadow the workspace
        // exactly like `--jars` (PROPOSAL.md §13: explicit flags merge in front).
        val explicitCoordJars = when (
            val resolved = MavenResolver.resolveAll(coords, allowFetch, repositories, fetcher)
        ) {
            is MavenResolver.ResolveAllOutcome.Ok ->
                resolved.artifacts.map { it.binaryJar.toString() }
            is MavenResolver.ResolveAllOutcome.Failed ->
                return RootsOrFailure.Failed(
                    JdxService.ServiceOutcome.Failure(
                        ErrorResult.generic("", exitCode = 5, message = "artifact read error: ${resolved.message}"),
                    ),
                )
        }
        // Auto-discovery runs only when no named workspace is in play; a named
        // workspace always wins (PROPOSAL.md §13). Never throws — discovery failure
        // reads as "no project", never as a failed query.
        val discovered = if (hasNamedSelection(workspace, envWorkspace, activeWorkspace)) {
            null
        } else {
            runCatching { discover(workingDir, cacheBase, gradleFilesRoot, m2Repo) }.getOrNull()
        }
        val outcome = WorkspaceResolver.resolve(
            explicitJars = jars + explicitCoordJars,
            explicitNoJdk = noJdk,
            flagWorkspace = workspace,
            envWorkspace = envWorkspace,
            activeWorkspace = activeWorkspace,
            loadWorkspace = store::load,
            listNames = {
                try {
                    store.listNames()
                } catch (e: Exception) {
                    emptyList()
                }
            },
            discoveredJars = discovered?.jars ?: emptyList(),
            discoveredSelection = discovered?.selection,
            discoveredWarnings = discovered?.warnings ?: emptyList(),
        )
        return when (outcome) {
            is WorkspaceResolver.Result.success -> {
                // Stored workspace coords resolve after the workspace's own jars.
                // A stored coord that no longer resolves fails the query (exit 5),
                // naming the coordinate — the workspace file is user config, and
                // silently dropping a root would mislead worse than an error.
                val stored = when (
                    val resolved = MavenResolver.resolveAll(outcome.value.coords, allowFetch, repositories, fetcher)
                ) {
                    is MavenResolver.ResolveAllOutcome.Ok ->
                        resolved.artifacts.map { it.binaryJar.toString() }
                    is MavenResolver.ResolveAllOutcome.Failed ->
                        return RootsOrFailure.Failed(
                            JdxService.ServiceOutcome.Failure(
                                ErrorResult.generic("", exitCode = 5, message = "artifact read error: ${resolved.message}"),
                            ),
                        )
                }
                val resolved = outcome.value
                RootsOrFailure.Ready(
                    JdxService.RootsSpec(
                        jarSpecs = resolved.jarSpecs + stored,
                        includeJdk = resolved.includeJdk,
                        extraWarnings = resolved.warnings,
                        allowFetch = allowFetch,
                        mavenResolve = mavenResolve,
                    ),
                )
            }
            is WorkspaceResolver.Result.failure ->
                RootsOrFailure.Failed(
                    JdxService.ServiceOutcome.Failure(
                        ErrorResult.generic("", exitCode = 4, message = outcome.error.message),
                    ),
                )
        }
    }

    private fun hasNamedSelection(flag: String?, env: String?, active: String?): Boolean =
        !flag?.trim().orEmpty().isEmpty() || !env?.trim().orEmpty().isEmpty() || !active?.trim().orEmpty().isEmpty()

    /**
     * Finds the enclosing project and derives (or loads from cache) its binary roots.
     * Returns `null` when the working directory sits in no project. Cache entries are
     * keyed by project path hash and invalidated by build-file fingerprint (T-016).
     */
    internal fun discoverProject(
        workingDir: Path,
        cacheBase: Path,
        gradleFilesRoot: Path?,
        m2Repo: Path?,
    ): DiscoveredRoots? {
        val project = ProjectDiscovery.findProjectRoot(workingDir) ?: return null
        val hash = ProjectDiscovery.projectHash(project.root)
        val cached = ProjectCache.load(cacheBase, hash, project.root)
        // The cache stores jars only; the fallback warning is re-derived from the
        // (cheap, no directory walks) coordinate parse so cache hits warn identically.
        val warnings = if (ProjectDiscovery.coordinatesOf(project.root).isEmpty()) {
            listOf(ProjectDiscovery.fallbackWarning(project.root))
        } else {
            emptyList()
        }
        val definition = cached ?: run {
            val derived = ProjectDiscovery.deriveBinaryRoots(project.root, gradleFilesRoot, m2Repo)
            WorkspaceDefinition(name = hash, jars = derived.jars, includeJdk = true).also {
                ProjectCache.save(cacheBase, it, ProjectDiscovery.computeFingerprint(project.root))
            }
        }
        return DiscoveredRoots(
            jars = definition.jars,
            selection = "auto-discovered project at '${project.root}' (nearest build file: ${project.marker})",
            warnings = warnings,
        )
    }

    /** Default `~/.cache/jdx/auto` off the process home. */
    internal fun defaultAutoCacheBase(): Path =
        Paths.get(System.getProperty("user.home")).resolve(".cache/jdx/auto")

    /** Default `~/.gradle/caches/modules-2/files-2.1`, or null when the property is absent. */
    internal fun defaultGradleFilesRoot(): Path? = runCatching {
        Paths.get(System.getProperty("user.home")).resolve(".gradle/caches/modules-2/files-2.1")
    }.getOrNull()

    /** Default `~/.m2/repository`, or null when the property is absent. */
    internal fun defaultM2Repo(): Path? = runCatching {
        Paths.get(System.getProperty("user.home")).resolve(".m2/repository")
    }.getOrNull()

    /** The two ways root resolution ends: roots to query with, or an exit-4 outcome to render. */
    internal sealed interface RootsOrFailure {
        /** Resolution succeeded — query with these roots. */
        data class Ready(val roots: JdxService.RootsSpec) : RootsOrFailure

        /** Resolution failed (unknown/corrupt workspace) — render this instead of querying. */
        data class Failed(val outcome: JdxService.ServiceOutcome.Failure) : RootsOrFailure
    }

    /** TTY-ness for the renderers (D-028): color only on a real console, never when piped. */
    internal fun useColor(noColor: Boolean): Boolean =
        !noColor && System.console() != null

    /** Maps `--access` to the resolver's visibility set; `null` keeps the service default. */
    internal fun accessOf(access: String?): Set<dev.jdx.core.model.Visibility>? = when (access) {
        null -> null
        "all" -> setOf(
            dev.jdx.core.model.Visibility.PUBLIC,
            dev.jdx.core.model.Visibility.PROTECTED,
            dev.jdx.core.model.Visibility.PACKAGE_PRIVATE,
            dev.jdx.core.model.Visibility.PRIVATE,
        )
        "public" -> setOf(dev.jdx.core.model.Visibility.PUBLIC)
        "protected" -> setOf(dev.jdx.core.model.Visibility.PROTECTED)
        "package" -> setOf(dev.jdx.core.model.Visibility.PACKAGE_PRIVATE)
        "private" -> setOf(dev.jdx.core.model.Visibility.PRIVATE)
        else -> null
    }

    /** Maps `--kind` to the service filter; Clikt's `choice()` guarantees the input range. */
    internal fun kindOf(kind: String): JdxService.KindFilter = when (kind) {
        "method" -> JdxService.KindFilter.METHOD
        "field" -> JdxService.KindFilter.FIELD
        "ctor" -> JdxService.KindFilter.CTOR
        else -> JdxService.KindFilter.ALL
    }

    /**
     * Validates the flag combinations that reach the service. Returns the usage error
     * message, or `null` when the flags are coherent. Deferred flags fail here with the
     * owning task named, per the T-011 acceptance rule.
     */
    internal fun validateMemberFlags(
        static: Boolean,
        instance: Boolean,
        grep: String?,
        withDoc: Boolean,
        sort: String,
    ): String? {
        if (static && instance) {
            return "usage error: --static and --instance are mutually exclusive"
        }
        if (grep != null) {
            try {
                Regex(grep)
            } catch (e: java.util.regex.PatternSyntaxException) {
                return "usage error: invalid --grep regex '$grep': ${e.message}"
            }
        }
        if (withDoc) {
            return "usage error: --with-doc is not yet implemented (T-025: javadoc/KDoc rendering)"
        }
        if (sort != "kind") {
            return "usage error: --sort $sort is not yet implemented (T-062: member sort orders)"
        }
        return null
    }

    /** Prints the outcome in the requested rendering and terminates on non-zero exit. */
    internal fun finish(
        outcome: JdxService.ServiceOutcome,
        command: String,
        json: Boolean,
        noColor: Boolean,
        terminate: (Int) -> Nothing = ::exitProcess,
    ) {
        if (json) {
            println(outcome.toJson(command))
        } else {
            println(outcome.renderText(useColor(noColor)))
        }
        if (outcome.exitCode != 0) terminate(outcome.exitCode)
    }
}

/**
 * One auto-discovered project's contribution to root resolution: the derived jars,
 * the human-readable selection trail, and resolution-time warnings. Produced by
 * [ReadCommandSupport.discoverProject], consumed by [WorkspaceResolver].
 */
public data class DiscoveredRoots(
    public val jars: List<String>,
    public val selection: String,
    public val warnings: List<Warning>,
)

/**
 * Project auto-discovery behind root resolution, injectable so command tests run
 * without IO (T-016): `(workingDir, cacheBase, gradleFilesRoot, m2Repo) -> roots?`.
 * Commands take it nullable (null means the real [ReadCommandSupport.discoverProject]);
 * tier-1 tests pass a null-returning lambda.
 */
internal typealias ProjectDiscoveryFn = (
    workingDir: Path,
    cacheBase: Path,
    gradleFilesRoot: Path?,
    m2Repo: Path?,
) -> DiscoveredRoots?

/** Query behind `members`/`outline`, injectable so command tests run without IO (T-011). */
internal typealias MemberQuery = (
    ref: String,
    roots: JdxService.RootsSpec,
    filters: JdxService.MemberFilters,
    declaredOnly: Boolean,
    includeSynthetic: Boolean,
    maxMembers: Int,
) -> JdxService.ServiceOutcome

/** Query behind `show`, injectable so command tests run without IO (T-011). */
internal typealias ShowQuery = (
    ref: String,
    roots: JdxService.RootsSpec,
) -> JdxService.ServiceOutcome

internal fun defaultMemberQuery(
    ref: String,
    roots: JdxService.RootsSpec,
    filters: JdxService.MemberFilters,
    declaredOnly: Boolean,
    includeSynthetic: Boolean,
    maxMembers: Int,
): JdxService.ServiceOutcome =
    JdxService.members(ref, roots, filters, declaredOnly, includeSynthetic, maxMembers)

internal fun defaultShowQuery(ref: String, roots: JdxService.RootsSpec): JdxService.ServiceOutcome =
    JdxService.show(ref, roots)

/** Query behind `search`, injectable so command tests run without IO (T-017). */
internal typealias SearchQuery = (
    pattern: String,
    roots: JdxService.RootsSpec,
    options: JdxService.SearchOptions,
) -> JdxService.ServiceOutcome

/** Query behind `resolve`, injectable so command tests run without IO (T-017). */
internal typealias ResolveQuery = (
    name: String,
    roots: JdxService.RootsSpec,
    limit: Int,
) -> JdxService.ServiceOutcome

/** Query behind `ls`, injectable so command tests run without IO (T-017). */
internal typealias LsQuery = (
    packageGlob: String?,
    roots: JdxService.RootsSpec,
    limit: Int,
) -> JdxService.ServiceOutcome

/** Query behind `tree`, injectable so command tests run without IO (T-017). */
internal typealias TreeQuery = (
    artifactGlob: String?,
    roots: JdxService.RootsSpec,
    depth: Int,
    withCounts: Boolean,
    limit: Int,
) -> JdxService.ServiceOutcome

internal fun defaultSearchQuery(
    pattern: String,
    roots: JdxService.RootsSpec,
    options: JdxService.SearchOptions,
): JdxService.ServiceOutcome = JdxService.search(pattern, roots, options)

internal fun defaultResolveQuery(
    name: String,
    roots: JdxService.RootsSpec,
    limit: Int,
): JdxService.ServiceOutcome = JdxService.resolve(name, roots, limit)

internal fun defaultLsQuery(
    packageGlob: String?,
    roots: JdxService.RootsSpec,
    limit: Int,
): JdxService.ServiceOutcome = JdxService.ls(packageGlob, roots, limit)

internal fun defaultTreeQuery(
    artifactGlob: String?,
    roots: JdxService.RootsSpec,
    depth: Int,
    withCounts: Boolean,
    limit: Int,
): JdxService.ServiceOutcome = JdxService.tree(artifactGlob, roots, depth, withCounts, limit)
