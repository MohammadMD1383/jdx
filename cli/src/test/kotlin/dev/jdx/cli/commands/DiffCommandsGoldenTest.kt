package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Golden tests for `jdx diff` (issue #23): the crafted two-jar corpus renders
 * once per case as text and once as JSON, pinned to committed files under
 * `src/test/resources/golden/diff/`.
 *
 * Hermetic by construction (D-028 §7): both sides are jars built in the temp dir
 * by [DiffCorpus] with *fixed, unversioned* file names, so the report's own labels
 * are already stable and no JDK, corpus, clock, hash or absolute path can reach
 * the bytes. The absolute-path guard that makes that claim testable lives in
 * `DiffCommandsServiceTest`, not here.
 *
 * Rewrite with `./gradlew :cli:tier2Test -Pgolden.update=true` — then read the diff
 * before committing it. A golden updated without reading is a test deleted.
 */
@Tag("tier2")
class DiffCommandsGoldenTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    /**
     * One pinned case: the golden file's stem, the argv, and the exit code that
     * argv is *supposed* to produce. Capturing a report at an unexpected exit is
     * itself a failure — otherwise a `--fail-on` gate that stopped tripping would
     * quietly rewrite the golden instead of going red.
     */
    private data class Case(val name: String, val args: List<String>, val exit: Int)

    private fun cases(old: Path, new: Path): List<Case> {
        val baseline = old.toString()
        val candidate = new.toString()
        return listOf(
            Case("all", listOf(baseline, candidate), 0),
            Case("severity-breaking", listOf(baseline, candidate, "--severity", "breaking"), 0),
            Case("limit-2", listOf(baseline, candidate, "--limit", "2"), 0),
            Case("identical", listOf(baseline, baseline), 0),
            // The one path a comparison takes to a non-zero exit, and the only
            // report whose own text carries a verdict.
            Case("fail-on-breaking", listOf(baseline, candidate, "--fail-on", "breaking"), 1),
        )
    }

    @Test
    fun `diff goldens pin text and json for every case`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val contents = buildMap {
            for (case in cases(old, new)) {
                for (json in listOf(false, true)) {
                    val args = case.args + if (json) listOf("--json") else emptyList()
                    put("${case.name}.${if (json) "json" else "txt"}", normalize(render(case, args), old, new))
                }
            }
        }
        GoldenFiles.verifyAll(File("src/test/resources/golden/diff"), contents)
    }

    @Test
    fun `every diff case is byte-identical on a repeat run`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        for (case in cases(old, new)) {
            for (json in listOf(false, true)) {
                val args = case.args + if (json) listOf("--json") else emptyList()
                val first = normalize(render(case, args), old, new)
                val second = normalize(render(case, args), old, new)
                if (first != second) {
                    fail("diff ${args.joinToString(" ")} is not deterministic:\n$first\n---\n$second")
                }
            }
        }
    }

    private fun render(case: Case, args: List<String>): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            DiffCommand(terminate = noExit).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        if (exit != case.exit) {
            fail("diff ${args.joinToString(" ")} exited $exit during golden capture (expected ${case.exit})")
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    /**
     * The jars live in a temp dir, so their *paths* are machine-specific even
     * though their names are not. A report must never print a path — that is the
     * real assertion, and it lives in `DiffCommandsServiceTest` — so this rewrite
     * is the safety net: if a path ever did leak, the golden would show the fixed
     * label below instead of `/tmp/junit123/...` and the next machine would fail
     * loudly rather than silently.
     */
    private fun normalize(output: String, old: Path, new: Path): String =
        output
            .replace(old.toAbsolutePath().toString(), DiffCaseJars.OLD_NAME)
            .replace(new.toAbsolutePath().toString(), DiffCaseJars.NEW_NAME)

    private fun corpus(dir: Path): Pair<Path, Path> {
        val built = DiffCorpus.build(dir)
        assumeTrue(built != null, "no Java compiler on the test runtime JDK; diff goldens skipped")
        return built!!
    }
}
