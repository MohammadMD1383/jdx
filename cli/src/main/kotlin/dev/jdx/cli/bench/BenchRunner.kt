package dev.jdx.cli.bench

import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.service.JdxService
import java.nio.file.Path

/**
 * The `jdx bench` workload (T-050, PROPOSAL.md §15).
 *
 * A fixed set of cases in deterministic order over resolved roots, sampling
 * the first/middle/last sorted class names from the jars at runtime — no
 * hard-coded symbols, since the default target (`minecraft-client.jar`) is
 * obfuscated. Each case runs [iterations] times and reports the median.
 *
 * §15 targets are advisory: rows print `ok`/`OVER` but the report never
 * fails — machine variance gates nothing (the `verifyTier1Budget`
 * precedent). `usages` stays out: the live scan has no indexed path yet
 * (D-043), so a `≤ 150 ms` row would only document the known gap.
 *
 * Timings are inherently non-deterministic (the disciplined exception to
 * AGENTS.md, like `doctor` sizes); case order, labels and targets are
 * byte-stable. Pure timing math ([median]) and name mapping ([binaryName])
 * are total and unit-tested; jar IO throws [dev.jdx.index.artifact.ArtifactReadException],
 * which the command maps to exit 5.
 */
public object BenchRunner {

    /** One benchmarked operation and its §15 target in milliseconds. */
    public data class Case(val name: String, val targetMs: Long)

    /** The fixed workload, in presentation order. */
    public val CASES: List<Case> = listOf(
        Case("load", 8000),
        Case("show", 250),
        Case("members", 250),
        Case("search", 250),
        Case("hierarchy", 250),
    )

    /** One timed case: the median over the iterations and whether it met its target. */
    public data class Row(
        val name: String,
        val targetMs: Long,
        val medianMs: Long,
        val withinTarget: Boolean,
    )

    /** The full report: what was measured and how many classes it covered. */
    public data class Report(
        val label: String,
        val classCount: Int,
        val iterations: Int,
        val rows: List<Row>,
    )

    /** Median of [samples] (lower middle for even counts); empty reads as 0. Never throws. */
    public fun median(samples: List<Long>): Long {
        if (samples.isEmpty()) return 0
        val sorted = samples.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    /**
     * Maps a normalised `com/foo/Bar$Baz.class` entry path to its binary name,
     * or `null` for entries that name no servable type (`package-info`).
     * `module-info` never reaches here (already excluded by the artifact).
     */
    public fun binaryName(entryPath: String): String? {
        if (!entryPath.endsWith(".class")) return null
        val dotted = entryPath.dropLast(".class".length).replace('/', '.')
        if (dotted == "package-info" || dotted.endsWith(".package-info")) return null
        return dotted
    }

    /**
     * Lists every servable binary name across [jarSpecs] (`--jars` values:
     * plain paths or globs, expanded by the same rule the queries read),
     * sorted. Opens each artifact read-only and closes it; throws on an
     * unreadable artifact.
     */
    public fun listClassNames(jarSpecs: List<String>): List<String> {
        val names = ArrayList<String>()
        for (jar in expandSpecs(jarSpecs)) {
            ArtifactLoader.open(jar).use { root ->
                for (entry in root.classEntryPaths()) {
                    binaryName(entry)?.let(names::add)
                }
            }
        }
        return names.sorted()
    }

    /**
     * Expands `--jars` specs to plain artifact paths (deduplicated, sorted)
     * by the same rule the queries read ([JdxService.expandJarSpec]).
     * Throws on an unreadable spec.
     */
    public fun expandSpecs(jarSpecs: List<String>): List<Path> =
        jarSpecs.flatMap { JdxService.expandJarSpec(it) }.distinct().sorted()

    /** Deterministic workload samples: first, middle and last of the sorted names. */
    public fun sampleRefs(sortedNames: List<String>): List<String> {
        if (sortedNames.isEmpty()) return emptyList()
        return listOf(
            sortedNames.first(),
            sortedNames[sortedNames.size / 2],
            sortedNames.last(),
        ).distinct()
    }

    /** Times [block] once in milliseconds with [clock] (nanoseconds). */
    public fun timeOnce(clock: () -> Long, block: () -> Any?): Long {
        val start = clock()
        block()
        return (clock() - start) / 1_000_000
    }

    /** Runs one case [iterations] times and folds the timings into a [Row]. */
    public fun runCase(name: String, targetMs: Long, iterations: Int, block: () -> Any?): Row {
        val timings = ArrayList<Long>(iterations)
        repeat(iterations) {
            timings.add(timeOnce(System::nanoTime, block))
        }
        val medianMs = median(timings)
        return Row(name, targetMs, medianMs, medianMs <= targetMs)
    }

    /**
     * Runs the full workload: `load` re-opens the [jarSpecs] artifacts and
     * ASM-reads every class (the "first index" proxy); the query cases run
     * through [service] over [roots]. Returns the report; throws on
     * unreadable artifacts.
     */
    public fun run(
        label: String,
        jarSpecs: List<String>,
        roots: JdxService.RootsSpec,
        service: JdxService = JdxService,
        iterations: Int = 3,
    ): Report {
        val jarPaths = expandSpecs(jarSpecs)
        val names = listClassNames(jarSpecs)
        require(names.isNotEmpty()) { "no classes to benchmark in $label" }
        val refs = sampleRefs(names)
        val showRef = refs[0]
        val rows = ArrayList<Row>(CASES.size)
        for (case in CASES) {
            val row = when (case.name) {
                "load" -> runCase(case.name, case.targetMs, iterations) {
                    for (jar in jarPaths) {
                        ArtifactLoader.open(jar).use { root ->
                            for (entry in root.classEntryPaths()) {
                                root.openClass(entry).use { stream ->
                                    AsmClassReader.read(stream, entry)
                                }
                            }
                        }
                    }
                }
                "show" -> runCase(case.name, case.targetMs, iterations) {
                    service.show(showRef, roots)
                }
                "members" -> runCase(case.name, case.targetMs, iterations) {
                    service.members(showRef, roots)
                }
                "search" -> runCase(case.name, case.targetMs, iterations) {
                    service.search("*a*", roots, JdxService.SearchOptions(limit = 50))
                }
                "hierarchy" -> runCase(case.name, case.targetMs, iterations) {
                    service.hierarchy(showRef, roots)
                }
                else -> runCase(case.name, case.targetMs, iterations) { }
            }
            rows.add(row)
        }
        return Report(label, names.size, iterations, rows)
    }
}
