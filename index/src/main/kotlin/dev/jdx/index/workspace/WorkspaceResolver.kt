package dev.jdx.index.workspace

import dev.jdx.core.model.Warning

/**
 * Resolves the ordered roots one query reads (PROPOSAL.md §13, T-015).
 *
 * Precedence, first match wins for *workspace selection* — while `--jars` values always
 * merge *in front of* the selected workspace's roots (§13: "`--jars`/`--src` always merge
 * in"):
 *
 * 1. explicit `--jars` flags (highest precedence — they shadow the workspace, like a
 *    classpath entry listed first);
 * 2. `-w/--workspace <name>`;
 * 3. `JDX_WORKSPACE`;
 * 4. the `jdx ws use` selection;
 * 5. project auto-discovery (T-016 — [discoveredJars] when no workspace is selected);
 * 6. the JDK stdlib unless switched off.
 *
 * Pure: workspace lookup is an injected lambda, so tier-1 tests resolve against a map
 * without touching disk. The CLI passes `store::load` (plus `listNames` for the
 * not-found hint); `doctor` passes an environment-backed variant.
 */
public object WorkspaceResolver {

    /** The resolved roots behind one query, ready for `JdxService.RootsSpec`. */
    public data class ResolvedRoots(
        /** Explicit jars first, then the workspace's jars — order is shadowing order. */
        public val jarSpecs: List<String>,
        /** False only when `--no-jdk` was passed or the workspace switches the JDK off. */
        public val includeJdk: Boolean,
        /** Where the workspace half came from: `none`, `workspace '<name>'`, with source. */
        public val workspaceName: String? = null,
        /** Human-readable selection trail for `ws info`/`doctor` (e.g. `flag -w 'mc'`). */
        public val selection: String = "none",
        /** Warnings contributed by root resolution itself (e.g. discovery fallback). */
        public val warnings: List<Warning> = emptyList(),
        /**
         * Maven coordinates still to resolve to jars: explicit `--coord` values
         * first, then the selected workspace's stored coords (T-019). The caller
         * ([ReadCommandSupport]) resolves them via `MavenResolver` and appends
         * the jars after [jarSpecs] — kept separate here so this pure resolver
         * never does IO.
         */
        public val coords: List<String> = emptyList(),
        /**
         * Remote Maven repository base URLs (T-069): explicit `--repo` values
         * first, then the selected workspace's stored repos. The caller builds
         * `MavenResolver.Repositories` from these, with Central last when the
         * combined list is empty.
         */
        public val repos: List<String> = emptyList(),
        /**
         * Project source dirs (T-031): explicit `--src` values first, then the
         * selected workspace's stored `srcs`. The caller scans them textually
         * in `usages`; other readers ignore them.
         */
        public val srcSpecs: List<String> = emptyList(),
    )

    /** A resolution failure: the message already names the problem and the fix. */
    public data class ResolutionFailure(val message: String)

    /**
     * Resolves roots. [loadWorkspace] returns the definition or `null` when absent; it may
     * throw [java.io.IOException] for corrupt/unreadable files, which reads as a failure
     * (never a throw). [listNames] powers the did-you-mean hint on a miss.
     */
    public fun resolve(
        explicitJars: List<String> = emptyList(),
        explicitNoJdk: Boolean = false,
        flagWorkspace: String? = null,
        envWorkspace: String? = null,
        activeWorkspace: String? = null,
        loadWorkspace: (String) -> WorkspaceDefinition? = { null },
        listNames: () -> List<String> = { emptyList() },
        discoveredJars: List<String> = emptyList(),
        discoveredSelection: String? = null,
        discoveredWarnings: List<Warning> = emptyList(),
        explicitCoords: List<String> = emptyList(),
        explicitRepos: List<String> = emptyList(),
        explicitSrcs: List<String> = emptyList(),
    ): Result<ResolvedRoots, ResolutionFailure> {
        val flag = flagWorkspace?.trim().orEmpty().ifEmpty { null }
        val env = envWorkspace?.trim().orEmpty().ifEmpty { null }
        val active = activeWorkspace?.trim().orEmpty().ifEmpty { null }
        val selected = flag ?: (env ?: active)
        val selection = when {
            flag != null -> "flag -w '$flag'"
            env != null -> "JDX_WORKSPACE='$env'"
            active != null -> "default workspace '$active' (jdx ws use)"
            else -> "none"
        }
        if (flag != null) {
            validateWorkspaceName(flag)?.let {
                return Result.failure(ResolutionFailure("invalid workspace name '$flag': $it"))
            }
        }
        if (selected == null) {
            // No named workspace anywhere: project auto-discovery (T-016) fills the
            // workspace half, if the caller found a project. A named workspace always
            // wins over discovery — discovery is consulted only here, never alongside.
            if (discoveredSelection != null) {
                return Result.success(
                    ResolvedRoots(
                        jarSpecs = explicitJars + discoveredJars,
                        includeJdk = !explicitNoJdk,
                        workspaceName = null,
                        selection = discoveredSelection,
                        warnings = discoveredWarnings,
                        coords = explicitCoords,
                        repos = explicitRepos,
                        srcSpecs = explicitSrcs,
                    ),
                )
            }
            return Result.success(
                ResolvedRoots(
                    jarSpecs = explicitJars,
                    includeJdk = !explicitNoJdk,
                    workspaceName = null,
                    selection = if (explicitJars.isNotEmpty() || explicitNoJdk || explicitCoords.isNotEmpty() || explicitRepos.isNotEmpty() || explicitSrcs.isNotEmpty()) {
                        "flags"
                    } else {
                        "none"
                    },
                    coords = explicitCoords,
                    repos = explicitRepos,
                    srcSpecs = explicitSrcs,
                ),
            )
        }
        if (env != null && flag == null) {
            validateWorkspaceName(selected)?.let {
                return Result.failure(ResolutionFailure("invalid workspace name in JDX_WORKSPACE='$selected': $it"))
            }
        }
        val definition = try {
            loadWorkspace(selected)
        } catch (e: Exception) {
            return Result.failure(ResolutionFailure("cannot load workspace '$selected': ${e.message}"))
        } ?: return Result.failure(
            ResolutionFailure(
                "no such workspace '$selected' (${selectionDescription(flag, env)}). " +
                    didYouMean(selected, listNames()) +
                    "Create it with: jdx ws create $selected --jars <path> — or `jdx ws list` to see all.",
            ),
        )
        return Result.success(
            ResolvedRoots(
                jarSpecs = explicitJars + definition.jars,
                includeJdk = if (explicitNoJdk) false else definition.includeJdk,
                workspaceName = definition.name,
                selection = selection,
                coords = explicitCoords + definition.coords,
                repos = explicitRepos + definition.repos,
                srcSpecs = explicitSrcs + definition.srcs,
            ),
        )
    }

    private fun selectionDescription(flag: String?, env: String?): String = when {
        flag != null -> "from -w '$flag'"
        env != null -> "from JDX_WORKSPACE='$env'"
        else -> "from jdx ws use"
    }

    /**
     * Near-name suggestions for a missed workspace: Levenshtein-near names first, then
     * the first three sorted names — capped, sorted, deterministic. Shared by the
     * resolver's own miss message and `jdx ws info`'s, so both suggest identically.
     * Pure — unit-tested directly.
     */
    public fun suggestSimilar(missed: String, names: List<String>, cap: Int = 5): List<String> {
        val sorted = names.sorted()
        val near = sorted.filter { levenshtein(it, missed) <= 2 }
        return (near.ifEmpty { sorted }.take(cap))
    }

    private fun didYouMean(missed: String, names: List<String>): String {
        if (names.isEmpty()) return ""
        return "Did you mean: ${suggestSimilar(missed, names, cap = 3).joinToString(", ")}. "
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * Two-element [Result]: `Ok`-style success carrying [S], `Failure`-style error carrying
     * [F]. `kotlin.Result` cannot carry a typed failure value (it wraps `Throwable`), and
     * resolution failures are *values* an agent branches on (exit 4), not exceptions —
     * so the resolver returns its own sum instead of throwing (CONTRIBUTING.md).
     */
    public sealed interface Result<out S, out F> {
        public data class success<S>(val value: S) : Result<S, Nothing>
        public data class failure<F>(val error: F) : Result<Nothing, F>
    }
}
