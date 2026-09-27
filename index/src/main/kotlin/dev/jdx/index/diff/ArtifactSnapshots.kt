package dev.jdx.index.diff

import dev.jdx.core.diff.ApiSnapshot
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.Warning
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult

/**
 * One side of a diff, flattened: the artifact's whole [ApiSnapshot] plus every
 * warning reading it produced.
 *
 * Warnings travel beside the snapshot rather than inside it because a diff
 * compares *structure* and reports *degradation* separately: a corrupt class is
 * not a difference between the two artifacts, it is a fact about one of them
 * that belongs in the envelope (D-007, D-015).
 */
internal data class ApiSnapshotResult(
    val snapshot: ApiSnapshot,
    val warnings: List<Warning>,
)

/**
 * Turns one opened [ArtifactRoot] into an [ApiSnapshot] — the byte-reading half
 * of `jdx diff`, and the only place a diff touches the filesystem.
 *
 * **Bytecode only, by design.** A diff has no "flesh" to read: bodies, parameter
 * docs and javadoc are not part of the question, and the truth model
 * (structure-from-bytecode, D-009) has nothing here to reconcile them against.
 * So this never pairs sources (no [dev.jdx.index.artifact.SourcesPairing]) and
 * never decompiles; both sides report `Origin.BYTECODE` provenance. A diff that
 * read sources would be answering a different question.
 *
 * **Degrade, never fail** (AGENTS.md §2.8, D-015): a class that will not parse,
 * or whose major version is from the future, is skipped with its warning and the
 * rest of the artifact still diffs. A corrupt entry is a fact about the jar, not
 * a reason to refuse to compare it — the warning names the class that was
 * dropped, so the report is honest about what it could not see.
 *
 * **Deterministic** (AGENTS.md §2.5): the class list comes from
 * [ArtifactRoot.classEntryPaths], already sorted, and the returned warnings are
 * sorted by code then subject — the order [ArtifactRoot.warnings] already uses,
 * so a diff's warning block reads like every other command's.
 *
 * [label] is what the report calls the artifact: a file name, a directory name,
 * or `group:artifact:version` for a coordinate. Never an absolute path and never
 * a content hash — output must not leak the machine's layout.
 *
 * A snapshot with zero classes is legal and warns about nothing; deciding
 * whether an empty artifact is a failure belongs to the caller, the only layer
 * that knows whether an empty comparison is meaningful.
 */
internal object ArtifactSnapshots {

    fun of(
        root: ArtifactRoot,
        label: String,
        surface: ApiSurface,
        includeSynthetic: Boolean,
    ): ApiSnapshotResult {
        val classes = mutableListOf<ClassInfo>()
        val warnings = mutableListOf<Warning>()
        warnings.addAll(root.warnings)
        for (path in root.classEntryPaths()) {
            when (val read = root.openClass(path).use { AsmClassReader.read(it, path) }) {
                is ClassReadResult.Ok -> {
                    classes.add(read.info)
                    warnings.addAll(read.warnings)
                }
                is ClassReadResult.UnsupportedVersion ->
                    warnings.add(read.warning.withSubjectIfAbsent(binaryNameOf(path)))
                is ClassReadResult.Corrupt ->
                    warnings.add(read.warning.withSubjectIfAbsent(binaryNameOf(path)))
            }
        }
        return ApiSnapshotResult(
            snapshot = ApiSnapshot.of(
                artifact = label,
                classes = classes,
                surface = surface,
                includeSynthetic = includeSynthetic,
            ),
            warnings = sortedDiffWarnings(warnings),
        )
    }

    /**
     * The class name an entry path denotes (`com/foo/Bar.class` → `com.foo.Bar`),
     * used as a warning's subject when the reader did not set one, so the report
     * names the class an agent would type rather than the zip path.
     *
     * Pure string math on purpose: `typeNameFromBinaryName` *validates*, and this
     * runs on exactly the hostile inputs a corrupt jar produces, where a throw
     * would turn a warning into the exit-6 a diff must never produce.
     */
    private fun binaryNameOf(entryPath: String): String =
        entryPath.removeSuffix(".class").replace('/', '.')

    /**
     * Fills in [fallback] only when the reader left `subject` null: a reader that
     * names the class better keeps its own answer.
     */
    private fun Warning.withSubjectIfAbsent(fallback: String): Warning =
        if (subject == null) copy(subject = fallback) else this
}

/**
 * The one warning order every diff report uses: by code, then subject, then
 * message. A total order, so two runs over the same pair of artifacts emit the
 * same warning block byte for byte (AGENTS.md §2.5).
 *
 * Public within the module because a report's warning block is the *union* of
 * both sides' warnings, and that union must be ordered by the same rule as each
 * half — one order, spelled once.
 */
internal fun sortedDiffWarnings(warnings: List<Warning>): List<Warning> =
    warnings.sortedWith(compareBy({ it.code }, { it.subject ?: "" }, { it.message }))
