package dev.jdx.core.diff

import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.TypeName

/**
 * One difference between two artifacts, as a value.
 *
 * The model is deliberately **flat**: a finding names the type it happened in, the
 * canonical reference of the subject, one [CompatRule] and a human-readable
 * [detail]. Nesting findings under types would buy nothing an agent can act on and
 * would make `--severity`/`--limit` filtering depend on tree shape. Grouping is a
 * *rendering* decision (see [dev.jdx.core.render.DiffReport]), not a model one.
 *
 * [ref] is the subject, spelled canonically in the artifact where it was read
 * (`com.example.Foo#bar(java.lang.String)`, or the type's own binary name for a
 * type-level finding), so the next `jdx` command is a copy-paste. [type] repeats
 * the declaring type for grouping and is equal to [ref] for type-level findings —
 * kept as a field because it cannot be derived back out of a member ref.
 */
public data class DiffFinding(
    public val rule: CompatRule,
    public val type: String,
    public val ref: String,
    public val detail: String = "",
) {
    /** Derived from the rule, never stored twice: a rule has exactly one severity. */
    public val severity: DiffSeverity get() = rule.severity
}

/**
 * The total order findings are printed in: most severe first, then by rule, then by
 * type, ref and detail. Every field is a total tiebreak, so two runs over the same
 * pair of artifacts produce byte-identical output and `--limit N` always keeps the
 * N most valuable findings (AGENTS.md §2.1).
 */
public val DIFF_FINDING_ORDER: Comparator<DiffFinding> = compareBy(
    { it.severity },
    { it.rule },
    { it.type },
    { it.ref },
    { it.detail },
)

/** How many types each side contributed, and how many findings each severity holds. */
public data class DiffCounts(
    public val typesOld: Int,
    public val typesNew: Int,
    public val breaking: Int,
    public val suspicious: Int,
    public val informational: Int,
    public val typesAdded: Int,
    public val typesRemoved: Int,
) {
    /** Every finding, all severities. */
    public val total: Int get() = breaking + suspicious + informational

    public companion object {
        /**
         * Counted from the findings, never supplied by the caller: a count that
         * disagrees with the list it describes is a lie an agent would act on.
         */
        public fun of(diff: ApiDiff): DiffCounts = DiffCounts(
            typesOld = diff.oldTypeCount,
            typesNew = diff.newTypeCount,
            breaking = diff.findings.count { it.severity == DiffSeverity.BREAKING },
            suspicious = diff.findings.count { it.severity == DiffSeverity.SUSPICIOUS },
            informational = diff.findings.count { it.severity == DiffSeverity.INFO },
            typesAdded = diff.findings.count { it.rule == CompatRule.TYPE_ADDED },
            typesRemoved = diff.findings.count { it.rule == CompatRule.TYPE_REMOVED },
        )
    }
}

/**
 * `--fail-on`: the CI gate. [NONE] (the default) always exits 0 — the comparison
 * succeeded, and "found something" is the answer, not a failure, so an agent
 * branches on the report rather than on the process status. `BREAKING` and `ANY`
 * turn the report into a status: they exit 1 when [tripped], which is the only
 * way a diff participates in a pipeline.
 *
 * Reusing exit 1 for the gate is a deliberate, owner-approved reading of the
 * AGENTS.md §6 table ("valid query, no results"): `diff` has no "not found"
 * outcome, and the gate's answer is carried in the envelope as `result.gate` too,
 * so a consumer that cannot see exit codes still sees the verdict.
 */
public enum class FailOn {
    /** Never gate: exit 0 whenever the comparison ran. */
    NONE,

    /** Exit 1 when at least one `BREAKING` finding is present. */
    BREAKING,

    /** Exit 1 when there is any difference at all, including purely additive ones. */
    ANY,
    ;

    public companion object {
        /** The `--fail-on` flag value, or `null` when the text is not a known mode. */
        public fun fromFlag(flag: String): FailOn? = when (flag.lowercase()) {
            "none" -> NONE
            "breaking" -> BREAKING
            "any" -> ANY
            else -> null
        }
    }
}

/**
 * The gate plus the numbers it decides on, so `tripped` and [exitCode] are always
 * consistent with the counts printed beside them. The counts are inputs rather
 * than a stored verdict — a stored boolean would be able to disagree with the
 * report it gates.
 */
public data class DiffGate(
    public val mode: FailOn,
    public val breakingCount: Int,
    public val findingCount: Int,
) {
    /** Whether this gate would fail the run. Always false in [FailOn.NONE]. */
    public val tripped: Boolean
        get() = when (mode) {
            FailOn.NONE -> false
            FailOn.BREAKING -> breakingCount > 0
            FailOn.ANY -> findingCount > 0
        }

    /** The process exit code this gate reports: 1 when tripped, else 0. */
    public val exitCode: Int get() = if (tripped) 1 else 0
}

/**
 * The result of comparing two [ApiSnapshot]s: labels, scope, and every finding.
 * Holds no verdict of its own — [counts] and [identical] are derived, and the
 * gate lives with the report in [dev.jdx.core.render.DiffReport], where the
 * presentation flags are known too.
 */
public data class ApiDiff(
    public val oldArtifact: String,
    public val newArtifact: String,
    public val oldTypeCount: Int,
    public val newTypeCount: Int,
    public val findings: List<DiffFinding>,
) {
    /** True when the two snapshots agree completely. */
    public val identical: Boolean get() = findings.isEmpty()

    /** Findings in print order ([DIFF_FINDING_ORDER]); sorting here, not in the differ. */
    public val ordered: List<DiffFinding> get() = findings.sortedWith(DIFF_FINDING_ORDER)
}

/**
 * Type-level findings shared by types and members: the `throws` clause, the
 * annotation set and `@Deprecated`. Kept in one place so a type and a member
 * cannot drift on what counts as the same annotation.
 *
 * [INTERNAL_ANNOTATIONS] are skipped: they are the compiler's own bookkeeping, and
 * they change on *every* edit to a Kotlin declaration. Reporting
 * `ANNOTATION_VALUES_CHANGED kotlin.Metadata` on a diff of two Kotlin artifacts would
 * bury a real finding under a line that is always true, so it is noise by construction
 * — and unlike a JetBrains `@Nullable` (which is a real, declared API), nobody compiles
 * against it.
 */
internal fun annotationFindings(
    before: List<AnnotationInfo>,
    after: List<AnnotationInfo>,
    type: String,
    ref: String,
): List<DiffFinding> {
    val out = mutableListOf<DiffFinding>()
    val oldByType = before.filterNot { it.type.binaryName in INTERNAL_ANNOTATIONS }
        .associateBy { it.type.binaryName }
    val newByType = after.filterNot { it.type.binaryName in INTERNAL_ANNOTATIONS }
        .associateBy { it.type.binaryName }
    for ((annotationType, annotation) in newByType) {
        val previous = oldByType[annotationType]
        val rule = when {
            previous == null -> CompatRule.ANNOTATION_ADDED
            previous.values != annotation.values -> CompatRule.ANNOTATION_VALUES_CHANGED
            else -> continue
        }
        out.add(DiffFinding(rule, type, ref, annotationType))
    }
    for (annotationType in oldByType.keys) {
        if (annotationType !in newByType) {
            out.add(DiffFinding(CompatRule.ANNOTATION_REMOVED, type, ref, annotationType))
        }
    }
    return out
}

/** Annotations the compiler writes, that no consumer compiles against. */
internal val INTERNAL_ANNOTATIONS: Set<String> = setOf(
    "kotlin.Metadata",
    "kotlin.jvm.internal.TypeTable",
    "kotlin.jvm.internal.JavaTypeParameters",
)

/**
 * Whether a `throws` entry is *possibly* checked. The two unchecked roots are
 * matched textually — nothing is ever loaded (D-017) — so a subclass of
 * `RuntimeException` is reported as possibly checked. That is the safe direction:
 * [CompatRule.CHECKED_EXCEPTION_ADDED] is `SUSPICIOUS`, and over-reporting it costs
 * an agent one look, while under-reporting a real break costs a runtime failure.
 */
internal fun isPossiblyChecked(type: TypeName): Boolean {
    val binary = type.binaryName
    return binary != UNCHECKED_RUNTIME_ROOT && binary != UNCHECKED_ERROR_ROOT
}

/** `java.lang.RuntimeException` — the root of the unchecked hierarchy. */
internal const val UNCHECKED_RUNTIME_ROOT: String = "java.lang.RuntimeException"

/** `java.lang.Error` — the other unchecked root. */
internal const val UNCHECKED_ERROR_ROOT: String = "java.lang.Error"
