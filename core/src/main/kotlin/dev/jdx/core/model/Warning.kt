package dev.jdx.core.model

/**
 * The closed set of `jdx` warning codes. Warnings are structural — agents and tests
 * branch on the code, never on message text. Adding a code is a public API change:
 * update `docs/PROPOSAL.md` §16 and the JSON envelope docs in the same commit.
 */
public enum class WarningCode {
    /** A sources jar disagrees with its binary jar's API surface (D-009). */
    SOURCES_VERSION_MISMATCH,

    /** The same FQN appears in more than one artifact (shading/relocation). */
    DUPLICATE_FQN,

    /** A class file's major version is newer than the running JDK understands. */
    UNSUPPORTED_CLASS_VERSION,

    /** A class file failed to parse — skipped, the rest of the artifact survives. */
    CORRUPT_CLASS,

    /** A multi-release jar served a version-specific variant of a class. */
    MULTI_RELEASE_VARIANT,

    /** A supertype named by a class file is not in the workspace (T-010). */
    UNRESOLVED_SUPERTYPE,

    /**
     * Project auto-discovery (T-016) found the project but could not determine its
     * dependency set from lock/metadata files, so dependency jars were omitted.
     * The project's own `build/classes`/`target/classes` roots still apply.
     */
    PROJECT_DISCOVERY_FALLBACK,
}

/**
 * One warning emitted with a command's result. [subject] is the canonical ref of the
 * symbol the warning is about, when there is one — copy-pasteable (D-016).
 */
public data class Warning(
    public val code: WarningCode,
    public val message: String,
    public val subject: String? = null,
)
