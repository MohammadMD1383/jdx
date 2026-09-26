package dev.jdx.index.workspace

/**
 * A named, ordered list of roots plus its resolution settings (PROPOSAL.md §5.3).
 *
 * Ordering is semantic, not cosmetic: earlier roots shadow later ones exactly like a JVM
 * classpath — [JdxService][dev.jdx.index.service.JdxService] reports the class the JVM
 * would actually load and warns `DUPLICATE_FQN` on every further provider. Explicit
 * `--jars` values always merge *in front of* these roots (PROPOSAL.md §13), so a flag
 * beats the workspace for the same class.
 *
 * Persisted as TOML in `<config-dir>/workspaces/<name>.toml` (see [WorkspaceToml]);
 * read and written only through [WorkspaceStore]. Produced by `jdx ws create`, consumed
 * by `jdx -w <name>` / `JDX_WORKSPACE` / `jdx ws use` via [WorkspaceResolver].
 *
 * v1 holds binary roots, Maven coordinates and the JDK switch. Source dirs
 * (`--src`, T-031) are stored here and scanned textually by `usages`
 * (whole-word mentions, `ref` kind); other readers ignore them.
 * Coordinates (`--coord`, T-019) resolve to jars at query time (local caches
 * first, remotes only with `--fetch`), ordered after [jars].
 * Remote repositories (`--repo`, T-069) are stored alongside and prepended in
 * flag order with Maven Central kept last when fetching.
 * Project auto-discovery (T-016) derives these same binary roots from Gradle/Maven
 * projects; it never stores source dirs either.
 */
public data class WorkspaceDefinition(
    /** Workspace name; always equals the file stem. Validated by [validateWorkspaceName]. */
    public val name: String,
    /** Ordered binary roots: jar files, class directories or globs, as passed to `--jars`. */
    public val jars: List<String>,
    /** Whether the running JDK's stdlib is appended after these roots (default true). */
    public val includeJdk: Boolean = true,
    /** Maven coordinates (`group:artifact:version`, as passed to `--coord`), in order. */
    public val coords: List<String> = emptyList(),
    /** Remote Maven repository base URLs (`--repo`, as passed to `ws create`), in order. */
    public val repos: List<String> = emptyList(),
    /** Project source dirs (`--src`, as passed to `ws create`), in order (T-031). */
    public val srcs: List<String> = emptyList(),
)

/**
 * Workspace names are file stems under `workspaces/`, so they must be portable file names
 * that cannot traverse directories: leading alphanumerics, then alphanumerics plus
 * `.`, `_`, `-`, at most 64 characters. Returns the error, or `null` when valid.
 * Pure — unit-tested directly.
 */
public fun validateWorkspaceName(name: String): String? {
    if (name.isEmpty()) return "workspace name is empty"
    if (name.length > 64) return "workspace name '$name' is longer than 64 characters"
    if (!name[0].isLetterOrDigit()) return "workspace name '$name' must start with a letter or digit"
    if (name.any { !(it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') }) {
        return "workspace name '$name' may only contain letters, digits, '.', '_' and '-'"
    }
    if (name == "." || name == "..") return "workspace name '$name' is reserved"
    return null
}
