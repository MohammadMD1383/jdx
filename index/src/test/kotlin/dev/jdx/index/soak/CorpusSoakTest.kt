package dev.jdx.index.soak

import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.JsonEscape
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.index.ArtifactIndexer
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.random.Random
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The corpus soak harness (T-059, docs/TESTING.md §8).
 *
 * Runs **every implemented command** (`show`, `members`, `outline`, `search`,
 * `resolve`, `ls`, `tree`) over a seeded random sample of the real local jar
 * corpus and asserts **invariants, not values**: it cannot know the right
 * answer for ~2,183 jars, but it knows `jdx` must never crash, never emit
 * invalid JSON, and never be non-deterministic.
 *
 * Per query the harness asserts:
 * - exit code ∈ {0, 1, 2} (never 3, 4 or 6);
 * - no JVM stack-trace fragment on `stdout`, `stderr`, or either rendering;
 * - the `--json` rendering validates against the envelope schema
 *   (`{"jdx":1,"ok","command","query","warnings","provenance"}`, `ok:false`
 *   carrying `error:{code,message}` + `candidates[]`);
 * - text and JSON carry the same entity set (every entity of the outcome
 *   appears in *both* renderings — the D-007 direction that matters);
 * - running the query twice yields byte-identical text and JSON.
 *
 * Exit 5 (honest "cannot read this artifact": corrupt entry, class version
 * newer than the running JDK, unmodellable name) is a **counted skip**, not a
 * failure — the same precedent the T-056 differential soak sets. Anything else
 * outside {0, 1, 2} fails. A subset of jars is also indexed (proving the
 * indexer never throws on real jars) and every warning feeds the
 * warning-code histogram, which is both printed and written to
 * `build/soak/CorpusSoak-histogram.txt` — "247 jars emitted
 * `MULTI_RELEASE_VARIANT`" is how we learn which shapes matter.
 *
 * Sampling is deterministic for a fixed seed: a failure prints the seed, and
 * re-running with `-PsoakSeed=<n>` reproduces the same sample (default
 * [DEFAULT_SEED]). The corpus comes from `-Djdx.corpusDir` (default
 * `~/.gradle/caches`, override with `-Pcorpus=<dir>`).
 *
 * Tagged `soak`, never `tier2` (docs/TESTING.md §2): needs the local jar
 * corpus and takes minutes. Runs via `./gradlew soak`; skips — never fails —
 * when the corpus dir is absent, so `check` stays green on fresh machines.
 */
@Tag("soak")
class CorpusSoakTest {

    private companion object {
        const val DEFAULT_SEED: Long = 20260917L
        const val MAX_JARS: Int = 30
        const val CLASSES_PER_JAR: Int = 3

        /**
         * Jars also pushed through the persistent indexer. Kept small: full
         * indexing is owned by [ArtifactIndexerSoakTest]; here it only proves
         * the indexer never throws on real-world jars.
         */
        const val MAX_INDEX_JARS: Int = 5
    }

    @Test
    fun `every command upholds its invariants over a seeded corpus sample`(@TempDir tempDir: Path) {
        val corpusDir = File(
            System.getProperty("jdx.corpusDir")
                ?: "${System.getProperty("user.home")}/.gradle/caches",
        )
        assumeTrue(
            corpusDir.isDirectory,
            "corpus dir ${corpusDir.absolutePath} absent: skipping corpus soak " +
                "(TESTING.md §8); point it at real jars with -Pcorpus=<dir>",
        )
        val seed = System.getProperty("jdx.soakSeed")?.toLongOrNull() ?: DEFAULT_SEED
        val random = Random(seed)

        val jars = collectJars(corpusDir, random)
        assumeTrue(
            jars.isNotEmpty(),
            "no readable jars under ${corpusDir.absolutePath}: skipping corpus soak (-Pcorpus=<dir>)",
        )

        val harness = Harness()
        val storeFile = tempDir.resolve("corpus-soak.db")
        SqliteIndexStore.open(storeFile).use { store ->
            for ((jarIndex, jar) in jars.withIndex()) {
                if (jarIndex < MAX_INDEX_JARS) harness.indexJar(store, jar)
                harness.soakJar(jar, random)
            }
        }

        harness.report(seed, jars.size)
        (harness.compared > 0) shouldBe true
        harness.failures shouldBe emptyList()
    }

    /** Per-run mutable state: counts, the histogram, and the failure list. */
    private inner class Harness {
        var compared: Int = 0
        val skips: MutableMap<String, Int> = mutableMapOf()
        val histogram: MutableMap<WarningCode, Int> = mutableMapOf()
        val failures: MutableList<String> = mutableListOf()

        fun skip(reason: String) {
            skips[reason] = (skips[reason] ?: 0) + 1
        }

        fun tallyWarnings(codes: List<WarningCode>) {
            for (code in codes) histogram[code] = (histogram[code] ?: 0) + 1
        }

        fun indexJar(store: SqliteIndexStore, jar: File) {
            try {
                val result = ArtifactIndexer.indexOne(store, jar.toPath())
                tallyWarnings(result.warnings.map { it.code })
            } catch (thrown: Exception) {
                failures.add(
                    "INDEX THREW ${jar.name}: ${thrown.javaClass.simpleName}: ${thrown.message}",
                )
            }
        }

        fun soakJar(jar: File, random: Random) {
            // The JDK rides along (the default query shape, D-006): supertypes
            // resolve for real, so the histogram records genuine jar shapes
            // instead of self-inflicted UNRESOLVED_SUPERTYPE noise.
            val roots = RootsSpec(jarSpecs = listOf(jar.absolutePath), includeJdk = true)
            val binaries = sampleClasses(jar, random)
            if (binaries.isEmpty()) {
                skip("jar opens with no classes")
                return
            }
            for (binary in binaries) {
                if (binary.contains("$$") || isUnnameable(binary)) {
                    skip("unnameable-name (T-065)")
                    continue
                }
                val simple = binary.substringAfterLast('.').substringAfterLast('$')
                check("show", binary) { JdxService.show(binary, roots) }
                check("members", binary) { JdxService.members(binary, roots) }
                check("outline", binary) { JdxService.outline(binary, roots) }
                check("search", binary) { JdxService.search(binary, roots) }
                check("resolve", simple) { JdxService.resolve(simple, roots) }
            }
            check("ls", "*") { JdxService.ls(null, roots) }
            check("tree", "*") { JdxService.tree(null, roots) }
        }

        /**
         * Runs one query inside stream capture and asserts every soak
         * invariant. Exit 5 becomes a counted skip; 0/1/2 run the full
         * battery; anything else is a failure.
         */
        fun check(command: String, query: String, run: () -> ServiceOutcome) {
            val captured = captureStreams(run)
            assertNoTrace(captured.stdout, "$command($query) stdout")
            assertNoTrace(captured.stderr, "$command($query) stderr")
            val outcome = captured.value
            when (outcome.exitCode) {
                0 -> checkSuccess(command, query, outcome, run)
                1, 2 -> checkFailure(command, query, outcome, run)
                5 -> skip("unreadable (exit 5)")
                else -> failures.add(
                    "$command($query): exit ${outcome.exitCode}, expected 0/1/2 " +
                        "(5 skips): ${oneLine(outcome.renderText(false))}",
                )
            }
        }

        private fun checkSuccess(
            command: String,
            query: String,
            outcome: ServiceOutcome,
            rerun: () -> ServiceOutcome,
        ) {
            val text: String
            val json: String
            try {
                text = outcome.renderText(false)
                json = outcome.toJson(command)
            } catch (thrown: Exception) {
                failures.add(
                    "$command($query): rendering threw " +
                        "${thrown.javaClass.simpleName}: ${thrown.message}",
                )
                return
            }
            assertNoTrace(text, "$command($query) text")
            assertNoTrace(json, "$command($query) JSON")
            val envelopeFault = validateEnvelope(json, command, exitCode = 0)
            if (envelopeFault != null) {
                failures.add("$command($query): invalid JSON envelope: $envelopeFault")
                return
            }
            tallyWarnings(warningCodesOf(outcome))
            for (entity in entitiesOf(outcome)) {
                if (!text.contains(entity)) {
                    failures.add("$command($query): text is missing entity '$entity'")
                }
                if (!jsonContains(json, entity)) {
                    failures.add("$command($query): JSON is missing entity '$entity'")
                }
            }
            val repeat = rerun()
            if (repeat.renderText(false) != text || repeat.toJson(command) != json) {
                failures.add("$command($query): re-run produced different bytes (determinism)")
                return
            }
            compared++
        }

        private fun checkFailure(
            command: String,
            query: String,
            outcome: ServiceOutcome,
            rerun: () -> ServiceOutcome,
        ) {
            val failure = outcome as? ServiceOutcome.Failure
            if (failure == null) {
                failures.add("$command($query): exit ${outcome.exitCode} without a failure body")
                return
            }
            val text = failure.renderText(false)
            val json = failure.toJson(command)
            assertNoTrace(text, "$command($query) text")
            assertNoTrace(json, "$command($query) JSON")
            val envelopeFault = validateEnvelope(json, command, outcome.exitCode)
            if (envelopeFault != null) {
                failures.add("$command($query): invalid error envelope: $envelopeFault")
                return
            }
            for (candidate in failure.error.candidates) {
                if (!text.contains(candidate)) {
                    failures.add("$command($query): text is missing candidate '$candidate'")
                }
                if (!jsonContains(json, candidate)) {
                    failures.add("$command($query): JSON is missing candidate '$candidate'")
                }
            }
            val repeat = rerun()
            if (repeat.renderText(false) != text || repeat.toJson(command) != json) {
                failures.add("$command($query): re-run produced different bytes (determinism)")
                return
            }
            compared++
        }

        private fun assertNoTrace(text: String, context: String) {
            for (marker in TRACE_MARKERS) {
                if (text.contains(marker)) {
                    failures.add("$context leaked a stack trace via '$marker'")
                    return
                }
            }
        }

        fun report(seed: Long, jarCount: Int) {
            println(
                "SOAK corpus seed=$seed jars=$jarCount compared=$compared " +
                    "skipped=${skips.values.sum()} failed=${failures.size}",
            )
            skips.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                println("SOAK skip [$count x] $reason")
            }
            println("SOAK warning histogram over $compared successful queries:")
            if (histogram.isEmpty()) {
                println("SOAK warning (none emitted)")
            } else {
                histogram.entries.sortedByDescending { it.value }.forEach { (code, count) ->
                    println("SOAK warning [$count x] $code")
                }
            }
            writeHistogramArtifact(seed, jarCount)
        }

        /**
         * Best-effort build artifact: the histogram as a file under `build/`
         * next to the test reports. A write failure is reported, never fatal —
         * the stdout histogram above is the primary record.
         */
        private fun writeHistogramArtifact(seed: Long, jarCount: Int) {
            try {
                val dir = File(System.getProperty("user.dir"), "build/soak")
                dir.mkdirs()
                val out = File(dir, "CorpusSoak-histogram.txt")
                out.writeText(
                    buildString {
                        appendLine("seed=$seed jars=$jarCount compared=$compared skipped=${skips.values.sum()}")
                        skips.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                            appendLine("skip [$count x] $reason")
                        }
                        histogram.entries.sortedByDescending { it.value }.forEach { (code, count) ->
                            appendLine("warning [$count x] $code")
                        }
                        if (histogram.isEmpty()) appendLine("warning (none emitted)")
                    },
                )
                println("SOAK histogram artifact: ${out.absolutePath}")
            } catch (thrown: Exception) {
                println("SOAK histogram artifact unwritten: ${thrown.javaClass.simpleName}")
            }
        }
    }

    private fun isUnnameable(binaryName: String): Boolean =
        runCatching { typeNameFromBinaryName(binaryName) }.isFailure

    private fun collectJars(corpusDir: File, random: Random): List<File> {
        val found = mutableListOf<File>()
        Files.walk(corpusDir.toPath()).use { walk ->
            walk.filter { path ->
                path.isRegularFile() && path.fileName.toString().endsWith(".jar") &&
                    !path.fileName.toString().endsWith("-sources.jar") &&
                    !path.fileName.toString().endsWith("-javadoc.jar")
            }.forEach { found.add(it.toFile()) }
        }
        return found.sortedBy { it.absolutePath }.shuffled(random).take(MAX_JARS)
    }

    private fun sampleClasses(jar: File, random: Random): List<String> {
        return try {
            ArtifactLoader.openJar(jar.toPath()).use { root ->
                root.classEntryPaths()
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .shuffled(random)
                    .take(CLASSES_PER_JAR)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Fragments proving a JVM stack trace leaked into user-facing output. */
private val TRACE_MARKERS: List<String> = listOf(
    "\tat ",
    "at dev.jdx.",
    "at java.",
    "at kotlin.",
    "at org.",
    "Exception in thread",
    "Caused by:",
)

private fun <T> captureStreams(block: () -> T): CorpusSoakTestCaptured<T> {
    val originalOut = System.out
    val originalErr = System.err
    val outBuffer = ByteArrayOutputStream()
    val errBuffer = ByteArrayOutputStream()
    System.setOut(PrintStream(outBuffer))
    System.setErr(PrintStream(errBuffer))
    try {
        val value = block()
        return CorpusSoakTestCaptured(value, outBuffer.toString(Charsets.UTF_8), errBuffer.toString(Charsets.UTF_8))
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
}

private data class CorpusSoakTestCaptured<T>(val value: T, val stdout: String, val stderr: String)

/**
 * Structural validation of the `--json` envelope (PROPOSAL.md §8.2, T-010):
 * `{"jdx":1,"ok","command","query","warnings","provenance"}`, with `ok:false`
 * carrying `error:{code,message}` plus `candidates[]`. Returns the fault, or
 * null when the envelope is well-formed. Key presence (not full JSON parsing —
 * `core` stays dependency-free and so does this check).
 */
private fun validateEnvelope(json: String, command: String, exitCode: Int): String? {
    val trimmed = json.trim()
    if (!trimmed.startsWith("{\"jdx\":1")) return "missing {\"jdx\":1 header"
    if (!trimmed.endsWith("}")) return "unbalanced trailing brace"
    if (trimmed.count { it == '{' } != trimmed.count { it == '}' }) return "unbalanced braces"
    if (trimmed.count { it == '[' } != trimmed.count { it == ']' }) return "unbalanced brackets"
    if (!json.contains("\"command\":\"$command\"")) return "missing \"command\":\"$command\""
    if (!json.contains("\"query\":")) return "missing \"query\""
    if (!json.contains("\"warnings\":[")) return "missing \"warnings\" array"
    if (!json.contains("\"provenance\":[")) return "missing \"provenance\" array"
    if (exitCode == 0) {
        if (!json.contains("\"ok\":true")) return "exit 0 without \"ok\":true"
    } else {
        if (!json.contains("\"ok\":false")) return "exit $exitCode without \"ok\":false"
        if (!json.contains("\"error\":{\"code\":$exitCode")) return "missing error.code $exitCode"
        if (!json.contains("\"message\":")) return "missing error.message"
        if (!json.contains("\"candidates\":[")) return "missing \"candidates\" array"
    }
    return null
}

/** Warning codes carried by one successful outcome, for the histogram. */
private fun warningCodesOf(outcome: ServiceOutcome): List<WarningCode> = when (outcome) {
    is ServiceOutcome.MemberList -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.Card -> outcome.card.warnings.map { it.code }
    is ServiceOutcome.Body -> outcome.block.warnings.map { it.code }
    is ServiceOutcome.Source -> outcome.block.warnings.map { it.code }
    is ServiceOutcome.SignatureList -> outcome.block.warnings.map { it.code }
    is ServiceOutcome.Doc -> outcome.block.warnings.map { it.code }
    is ServiceOutcome.SearchList -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.LsList -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.TreeList -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.UsageList -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.Hierarchy -> outcome.listing.warnings.map { it.code }
    is ServiceOutcome.Failure -> emptyList()
}

/**
 * Entities that must appear in *both* renderings (the D-007 text⊆JSON
 * direction, checked both ways). Chosen conservatively per outcome type:
 * member texts print signatures (not refs), search/ls/tree texts print refs,
 * and every listing prints its warning codes and provider labels.
 */
private fun entitiesOf(outcome: ServiceOutcome): List<String> = when (outcome) {
    is ServiceOutcome.MemberList -> buildList {
        for (group in outcome.listing.groups) {
            for (row in group.rows) add(row.signature)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
        for (provenance in outcome.listing.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.Card -> buildList {
        add(outcome.card.target.name.binaryName)
        val superclass = outcome.card.target.superclass?.binaryName
        if (superclass != null) add(superclass)
        for (iface in outcome.card.target.interfaces) add(iface.binaryName)
        for (warning in outcome.card.warnings) add(warning.code.name)
        for (provenance in outcome.card.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.Body -> buildList {
        add(outcome.block.canonicalRef)
        add(outcome.block.file)
        for (line in outcome.block.lines) add(line)
        for (warning in outcome.block.warnings) add(warning.code.name)
        for (provenance in outcome.block.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.Source -> buildList {
        add(outcome.block.canonicalRef)
        add(outcome.block.file)
        for (line in outcome.block.lines) add(line)
        for (warning in outcome.block.warnings) add(warning.code.name)
        for (provenance in outcome.block.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.SignatureList -> buildList {
        add(outcome.block.query)
        for (entry in outcome.block.signatures) {
            add(entry.canonicalRef)
            add(entry.signature)
        }
        for (warning in outcome.block.warnings) add(warning.code.name)
        for (provenance in outcome.block.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.Doc -> buildList {
        add(outcome.block.canonicalRef)
        add(outcome.block.file)
        for (line in outcome.block.lines) add(line)
        for (warning in outcome.block.warnings) add(warning.code.name)
        for (provenance in outcome.block.provenance) add(provenance.artifact)
    }
    is ServiceOutcome.SearchList -> buildList {
        for (hit in outcome.listing.hits) {
            add(hit.ref)
            add(hit.artifact)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
    }
    is ServiceOutcome.LsList -> buildList {
        for (pkg in outcome.listing.packages) add(pkg.name)
        for (type in outcome.listing.types) {
            add(type.ref)
            add(type.artifact)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
    }
    is ServiceOutcome.TreeList -> buildList {
        for (artifact in outcome.listing.artifacts) {
            add(artifact.artifact)
            collectPackages(artifact.roots, this)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
    }
    is ServiceOutcome.UsageList -> buildList {
        add(outcome.listing.targetRef)
        for (hit in outcome.listing.hits) {
            add(hit.fromRef)
            add(hit.artifact)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
    }
    is ServiceOutcome.Hierarchy -> buildList {
        add(outcome.listing.targetRef)
        for (entry in outcome.listing.supertypes) {
            add(entry.binary)
            entry.artifact?.let { add(it) }
        }
        for (entry in outcome.listing.subtypes) {
            add(entry.binary)
            add(entry.artifact)
        }
        for (warning in outcome.listing.warnings) add(warning.code.name)
    }
    is ServiceOutcome.Failure -> outcome.error.candidates
}

private fun collectPackages(
    nodes: List<dev.jdx.core.render.TreeNode>,
    into: MutableList<String>,
) {
    for (node in nodes) {
        into.add(node.packageName)
        collectPackages(node.children, into)
    }
}

private fun oneLine(text: String): String = text.lineSequence().firstOrNull().orEmpty().take(220)

/**
 * JSON containment modulo string escaping: a signature like
 * `String X = ":status"` renders in JSON as `"signature":"… = \":status\""`,
 * so the raw text is never a substring. Comparing against the escaped form
 * keeps the text⊆JSON check honest for constant values, `default "..."`
 * clauses, and any other `"`-carrying entity.
 */
private fun jsonContains(json: String, entity: String): Boolean =
    json.contains(JsonEscape.quote(entity).removeSurrounding("\""))
