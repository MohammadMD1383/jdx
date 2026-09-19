package dev.jdx.index.differential

import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.index.service.JdxService
import java.io.File

/**
 * Diffs one class's `javap -p -s` member set against the answer to
 * `jdx members --declared --access all --include-synthetic --json` for the
 * same class (T-056, TESTING.md §5.1).
 *
 * The query flags are the point: `--declared` pins the comparison to the
 * class's own members (no hierarchy walk, so missing supertypes cannot skew
 * it), `--access all` and `--include-synthetic` switch off every filter that
 * could legitimately hide a row. What remains must equal `javap`, minus the
 * [JavapQuirks] allowlist.
 *
 * Two deliberate comparison choices, so the next reader does not "fix" them:
 *
 * 1. The `jdx` side is compared on **canonical refs**, not descriptors. A
 *    listing row's ref erases the return/field type by design — refs are the
 *    agent's copy-paste handles, not the descriptor truth — so a return-type
 *    misread cannot show here. That fidelity is owned by the reader-level
 *    differential (T-008), which compares full `(name, descriptor)` sets; this
 *    comparison owns what the *command path* can break: dropped or added rows
 *    (filtering, synthetic handling, name mapping) and the `--json` carriage.
 * 2. Bridge/covariant siblings share name and erased parameters and differ
 *    only in return type; the renderer disambiguates them with a `:return`
 *    suffix on *both* rows. Matching is therefore by prefix: an expected base
 *    ref matches an actual ref that equals it or extends it with `:<return>`.
 */
internal object ServiceDifferential {

    /** Every visibility: the `--access all` equivalent (PROPOSAL.md §7.1). */
    internal val ALL_VISIBILITIES: Set<Visibility> = setOf(
        Visibility.PUBLIC,
        Visibility.PROTECTED,
        Visibility.PACKAGE_PRIVATE,
        Visibility.PRIVATE,
    )

    /**
     * Row cap for differential queries. Fixture classes hold dozens of
     * members; giant corpus classes hold thousands — the comparison is only
     * meaningful untruncated, so the cap is effectively infinite and a
     * truncated answer is reported as a failure, never silently compared.
     */
    internal const val SERVICE_LIMIT: Int = 1_000_000

    /** The outcome of comparing one class. */
    internal sealed interface Comparison {
        /** Both sides produced a set; [mismatches] is empty on agreement. */
        data class Compared(
            val binaryName: String,
            val javapCount: Int,
            val jdxCount: Int,
            val missing: List<String>,
            val extra: List<String>,
            val countMismatches: List<String>,
            val jsonGaps: List<String>,
            val quirksApplied: List<String>,
        ) : Comparison {
            val agrees: Boolean
                get() = missing.isEmpty() && extra.isEmpty() &&
                    countMismatches.isEmpty() && jsonGaps.isEmpty()
        }

        /** No comparison was possible; the caller decides skip vs failure. */
        data class Skipped(val binaryName: String, val reason: String) : Comparison

        /** `jdx` itself reported an error for the class. */
        data class ServiceError(val binaryName: String, val exitCode: Int, val message: String) :
            Comparison
    }

    /**
     * Runs the comparison for one class in [jar]. Never throws: `javap`
     * failures become [Comparison.Skipped], `jdx` failures become
     * [Comparison.ServiceError], and only a completed both-sides read becomes
     * [Comparison.Compared].
     */
    internal fun compare(jar: File, binaryName: String, javap: String): Comparison {
        // T-065: names the model cannot represent (JFR `A$B$$C` shapes) fail in
        // the ref parser with exit 3 before any bytecode is read — skip them
        // here so the soak counts them as degradation, never as failure. The
        // widening (`$$` anywhere, not only empty segments) is deliberate:
        // `$$`-bearing names never survive `typeNameFromBinaryName` intact, so
        // routing the whole family to Skipped keeps corpus drift out of the red.
        if (binaryName.contains("$$")) {
            return Comparison.Skipped(binaryName, "binary name is not a modellable type: $binaryName")
        }
        val outcome = JdxService.members(
            binaryName,
            JdxService.RootsSpec(jarSpecs = listOf(jar.absolutePath), includeJdk = false),
            JdxService.MemberFilters(access = ALL_VISIBILITIES),
            declaredOnly = true,
            includeSynthetic = true,
            maxMembers = SERVICE_LIMIT,
        )
        if (outcome is JdxService.ServiceOutcome.Failure) {
            return Comparison.ServiceError(binaryName, outcome.exitCode, outcome.renderText(false))
        }
        val listing = (outcome as JdxService.ServiceOutcome.MemberList).listing
        val truncation = listing.truncation
        if (truncation != null) {
            return Comparison.ServiceError(
                binaryName,
                0,
                "jdx truncated the listing at $SERVICE_LIMIT rows " +
                    "(${truncation.total} total): differential is meaningless",
            )
        }
        val actualRefs = listing.groups.flatMap { group -> group.rows }
            .map { it.canonicalRef }.toSet()

        val javapOutput = Javap.tryRun(javap, jar.absolutePath, binaryName)
            ?: return Comparison.Skipped(binaryName, "javap cannot read $binaryName from ${jar.name}")
        val quirks = JavapQuirks.applyToJavapSet(Javap.parseMembers(javapOutput))

        val declaring = try {
            typeNameFromBinaryName(binaryName) as? TypeName.ClassType
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Comparison.Skipped(
            binaryName,
            "binary name is not a modellable type: $binaryName",
        )
        // Expected base refs carrying every full descriptor for the report: a
        // missing row names its member *and* the descriptor `jdx` failed to
        // list. Several `javap` members may share one base ref (a bridge and
        // its target differ only in return type, which refs erase) — the list
        // keeps them all so overload *counts* compare too.
        val expected = mutableListOf<Pair<String, String>>()
        for (member in quirks.members) {
            val parsed = JvmDescriptor.parse(member.descriptor)
                ?: return Comparison.ServiceError(
                    binaryName,
                    0,
                    "unparseable javap descriptor for ${member.name}: ${member.descriptor}",
                )
            val base = when (parsed) {
                is JvmDescriptor.Method -> SymbolRefPrinter.print(
                    MemberSymbolRef(declaring, member.name, parsed.parameters),
                )
                is JvmDescriptor.Field -> SymbolRefPrinter.print(
                    MemberSymbolRef(declaring, member.name),
                )
            }
            expected.add(base to member.descriptor)
        }

        fun matches(actual: String, base: String): Boolean =
            actual == base || actual.startsWith("$base:")
        val missing = expected
            .filter { (base, _) -> actualRefs.none { actual -> matches(actual, base) } }
            .map { (base, descriptor) -> "$base $descriptor" }
            .sorted()
            .distinct()
        val expectedBases = expected.map { it.first }.toSet()
        val extra = actualRefs
            .filter { actual -> expectedBases.none { base -> matches(actual, base) } }
            .sorted()
        // Overload-count agreement per base ref: dropping a bridge (or
        // duplicating a row) keeps presence green but changes the count — and
        // `--include-synthetic` handling is exactly what this harness owns.
        val countMismatches = expectedBases
            .mapNotNull { base ->
                val javapN = expected.count { it.first == base }
                val jdxN = actualRefs.count { matches(it, base) }
                if (javapN != jdxN) {
                    val descriptors = expected.filter { it.first == base }.map { it.second }.sorted()
                    "$base: javap=$javapN (${descriptors.joinToString(", ")}) jdx=$jdxN"
                } else {
                    null
                }
            }
            .sorted()
        // The `--json` half of the command contract (D-007): every listed ref
        // must appear verbatim in the JSON envelope. Refs contain no
        // JSON-escaped characters (`$`, `<`, `>`, `(`, `)`, `,`, `:` all pass
        // through), so a literal containment check is exact.
        val json = outcome.toJson("members")
        val jsonGaps = actualRefs.filter { it !in json }.sorted()

        return Comparison.Compared(
            binaryName = binaryName,
            javapCount = quirks.members.size,
            jdxCount = actualRefs.size,
            missing = missing,
            extra = extra,
            countMismatches = countMismatches,
            jsonGaps = jsonGaps,
            quirksApplied = quirks.applied,
        )
    }

    /**
     * Renders a [Comparison.Compared] disagreement naming **which member and
     * which descriptor** differs on which side — never a bare "sets differ".
     */
    internal fun formatMismatch(compared: Comparison.Compared): String = buildString {
        appendLine(
            "MISMATCH ${compared.binaryName} " +
                "(javap -p -s vs jdx members --declared --access all --include-synthetic, " +
                "javap=${compared.javapCount} jdx=${compared.jdxCount}):",
        )
        compared.missing.forEach { appendLine("- javap only (jdx dropped):  $it") }
        compared.extra.forEach { appendLine("+ jdx only (javap has no such member): $it") }
        compared.countMismatches.forEach { appendLine("~ overload count differs: $it") }
        compared.jsonGaps.forEach { appendLine("! listing row missing from --json: $it") }
        if (compared.quirksApplied.isNotEmpty()) {
            appendLine("  quirks applied: ${compared.quirksApplied.joinToString("; ")}")
        }
    }
}
