package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `jdx diff <old> <new>` over real artifacts (issue #23, tier 2).
 *
 * The report's exact bytes are pinned by [DiffCommandsGoldenTest]; what this suite
 * owns is the *disk* half of the adapter: the two real jars resolve, real
 * bytecode is really read, the flags really change what is answered, and a bad
 * spec exits with the code `--coord`/`--jars` already use. The jars are crafted
 * in the temp dir by [DiffCorpus] rather than read from the shared corpus, so
 * another suite's fixture can never move these assertions.
 */
@Tag("tier2")
class DiffCommandsServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private data class Run(val output: String, val exit: Int)

    /** The real command with the real service and a throwing exit. */
    private fun run(args: List<String>): Run {
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
        return Run(buffer.toString(Charsets.UTF_8), exit)
    }

    /** The crafted pair, or a skip when the test runtime has no Java compiler. */
    private fun corpus(dir: Path): Pair<Path, Path> {
        val built = DiffCorpus.build(dir)
        assumeTrue(built != null, "no Java compiler on the test runtime JDK; crafted diff jars skipped")
        return built!!
    }

    /**
     * `severity␠␠RULE␠␠ref` or `severity␠␠RULE␠␠ref: detail`. The lowest
     * severity's wire name is `info` on a finding line but `informational` in the
     * tally — a real inconsistency in the report surface, pinned here as-is.
     */
    private val FINDING_LINE: Regex = Regex("""(breaking|suspicious|info)  (\S+)  (.*)""")

    // -- the happy path --------------------------------------------------------------

    @Test
    fun `two real jars report their differences and exit 0`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val run = run(listOf(old.toString(), new.toString()))

        // Found something is an answer, not a failure: without --fail-on a
        // comparison that ran always exits 0 (D-015).
        run.exit shouldBe 0
        run.output shouldContain "api diff ${DiffCaseJars.OLD_NAME} -> ${DiffCaseJars.NEW_NAME} (public surface)"
        // One line per finding, each naming its rule and a copy-pasteable ref.
        run.output shouldContain "breaking  TYPE_REMOVED  difffix.Extra"
        run.output shouldContain "breaking  MEMBER_REMOVED  difffix.Api#legacy()"
        run.output shouldContain "breaking  MEMBER_MADE_FINAL  difffix.Api#sealedFinal()"
        run.output shouldContain "breaking  STATIC_TO_INSTANCE  difffix.Api#shared()"
        // `int` → `int...` changes the erased descriptor, so the binary break is
        // what reports, and the detail names the new ref.
        run.output shouldContain "breaking  PARAMETER_TYPE_CHANGED  difffix.Api#variadic(int): now: difffix.Api#variadic(int[])"
        run.output shouldContain "breaking  RETURN_TYPE_CHANGED  difffix.Api#width(): int -> long"
        run.output shouldContain "suspicious  CHECKED_EXCEPTION_ADDED  difffix.Api#checked(): java.io.IOException"
        run.output shouldContain "suspicious  GENERIC_SIGNATURE_CHANGED  difffix.Api#echo(java.util.List)"
        run.output shouldContain "info  TYPE_ADDED  difffix.Marker"
        run.output shouldContain "info  SUPERTYPE_ADDED  difffix.Api: difffix.Marker"
        run.output shouldContain "info  MEMBER_ADDED  difffix.Api#fresh()"
        run.output shouldContain "7 breaking · 2 suspicious · 3 informational · 1 type added · 1 type removed"
        // A removal is reported plainly: every class extends `java.lang.Object`, which is
        // never inside a third-party jar, so treating it as an unchecked supertype would
        // put a caveat on every removal in every report. The "could not check" wording is
        // reserved for a supertype outside the artifact that is genuinely unknown, and
        // `ApiDifferTest` pins both halves of that rule.
        run.output shouldContain "breaking  MEMBER_REMOVED  difffix.Api#legacy()\n"
        run.output shouldNotContain "inheritance not checked"
        // Provenance names both inputs; a diff never claims to have read sources.
        run.output shouldContain "source: ${DiffCaseJars.OLD_NAME} (bytecode)"
        run.output shouldContain "source: ${DiffCaseJars.NEW_NAME} (bytecode)"
    }

    @Test
    fun `a public member narrowed to private leaves the public surface entirely`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        // Two honest readings of the same change, and which one you get is the
        // surface flag's whole job. On the public surface `narrowed()` is simply
        // gone; only `--visibility all` can see that it was narrowed rather than
        // deleted.
        val byDefault = run(listOf(old.toString(), new.toString())).output
        byDefault shouldContain "breaking  MEMBER_REMOVED  difffix.Api#narrowed()"
        byDefault shouldNotContain "MEMBER_VISIBILITY_NARROWED"

        val everything = run(listOf(old.toString(), new.toString(), "--visibility", "all")).output
        everything shouldContain "breaking  MEMBER_VISIBILITY_NARROWED  difffix.Api#narrowed()"
        everything shouldNotContain "MEMBER_REMOVED  difffix.Api#narrowed()"
    }

    @Test
    fun `a jar compared against itself reports no differences`(@TempDir dir: Path) {
        val (old, _) = corpus(dir)

        val run = run(listOf(old.toString(), old.toString()))

        run.exit shouldBe 0
        run.output shouldContain "no differences (2 types compared)"
    }

    @Test
    fun `the report never names an absolute path`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val text = run(listOf(old.toString(), new.toString())).output
        val json = run(listOf(old.toString(), new.toString(), "--json")).output

        // Default output must not leak the machine's layout (AGENTS.md §2.5): the
        // caller named both specs, so the file name is all the report may echo.
        text shouldNotContain dir.toString()
        json shouldNotContain dir.toString()
        text shouldContain DiffCaseJars.OLD_NAME
        json shouldContain DiffCaseJars.NEW_NAME
    }

    @Test
    fun `json carries exactly the findings the text shows`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val text = run(listOf(old.toString(), new.toString())).output
        val json = run(listOf(old.toString(), new.toString(), "--json")).output

        val parsed = Json.parseToJsonElement(json.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "diff"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["query"]?.jsonPrimitive?.content shouldBe
            "${DiffCaseJars.OLD_NAME} -> ${DiffCaseJars.NEW_NAME}"
        // Every text finding is structurally in the envelope and vice versa
        // (D-007): a text-only finding would be a gap, not a formatting choice.
        val fromText = text.lines().mapNotNull { line ->
            FINDING_LINE.matchEntire(line)?.let { match ->
                Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3].substringBefore(":"))
            }
        }
        // Pinned so a change to the report layout that stopped matching cannot
        // quietly turn this into a vacuous "both lists are empty" pass.
        fromText.size shouldBe 12
        val fromJson = parsed["result"]?.jsonObject?.get("findings")?.jsonArray.orEmpty().map { finding ->
            val entry = finding.jsonObject
            Triple(
                entry.getValue("severity").jsonPrimitive.content,
                entry.getValue("rule").jsonPrimitive.content,
                entry.getValue("ref").jsonPrimitive.content,
            )
        }
        fromJson shouldBe fromText
    }

    @Test
    fun `running twice yields identical bytes`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        run(listOf(old.toString(), new.toString())).output shouldBe
            run(listOf(old.toString(), new.toString())).output
        run(listOf(old.toString(), new.toString(), "--json")).output shouldBe
            run(listOf(old.toString(), new.toString(), "--json")).output
    }

    // -- flags that change what is answered -------------------------------------------

    @Test
    fun `severity breaking hides the rest without changing the tally`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val run = run(listOf(old.toString(), new.toString(), "--severity", "breaking"))

        run.exit shouldBe 0
        run.output shouldContain "breaking  MEMBER_REMOVED  difffix.Api#legacy()"
        run.output shouldNotContain "MEMBER_ADDED"
        run.output shouldContain "5 findings below --severity breaking"
        // The tally still describes the whole comparison (D-007).
        run.output shouldContain "7 breaking · 2 suspicious · 3 informational · 1 type added · 1 type removed"
    }

    @Test
    fun `limit two cuts the report at a whole finding and names the real total`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val run = run(listOf(old.toString(), new.toString(), "--limit", "2"))

        run.exit shouldBe 0
        run.output shouldContain "2 of 12 findings shown (--limit 12 to see more)"
        // Exactly two finding lines survive the cut — a whole finding each, never
        // a half-printed one.
        run.output.lines().count { FINDING_LINE.matches(it) } shouldBe 2
    }

    @Test
    fun `fail-on breaking turns a breaking change into exit 1`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val run = run(listOf(old.toString(), new.toString(), "--fail-on", "breaking"))

        run.exit shouldBe 1
        run.output shouldContain "fail-on breaking: 7 breaking changes (exit 1)"
    }

    @Test
    fun `fail-on breaking exits 0 on an identical pair`(@TempDir dir: Path) {
        val (old, _) = corpus(dir)

        val run = run(listOf(old.toString(), old.toString(), "--fail-on", "breaking"))

        run.exit shouldBe 0
        run.output shouldContain "fail-on breaking: no breaking changes (exit 0)"
    }

    @Test
    fun `include-synthetic changes nothing when no member is synthetic`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        // javac emits no synthetic or bridge members for this corpus, so the
        // flag's honest answer today is "the same report" — which is exactly what
        // an adapter must forward rather than invent a difference for.
        val withFlag = run(listOf(old.toString(), new.toString(), "--include-synthetic"))
        withFlag.exit shouldBe 0
        withFlag.output shouldContain "7 breaking · 2 suspicious · 3 informational"
    }

    // -- bad specs (tier 2's real exit codes) -------------------------------------------

    @Test
    fun `a path that does not exist exits 5 naming the artifact`(@TempDir dir: Path) {
        val (_, new) = corpus(dir)

        val run = run(listOf(dir.resolve("absent.jar").toString(), new.toString()))

        run.exit shouldBe 5
        run.output shouldContain "artifact read error"
        run.output shouldContain "absent.jar"
    }

    @Test
    fun `a file that is not a jar exits 5`(@TempDir dir: Path) {
        val (_, new) = corpus(dir)
        val notAJar = Files.writeString(dir.resolve("not-a-jar.jar"), "definitely not a zip")

        val run = run(listOf(notAJar.toString(), new.toString()))

        run.exit shouldBe 5
        run.output shouldContain "artifact read error"
    }

    @Test
    fun `a coordinate absent from the local caches exits 5 naming --fetch`(@TempDir dir: Path) {
        val (_, new) = corpus(dir)
        // A coordinate that cannot be in ~/.m2 or ~/.gradle/caches, so the test is
        // hermetic; nothing is fetched, because --fetch was not passed (D-006).
        val absent = "com.example.does-not-exist:demo:9.9.9-jdx-diff-cli"

        val run = run(listOf(absent, new.toString()))

        // The same exit and message `--coord` gives for the same failure.
        run.exit shouldBe 5
        run.output shouldContain "artifact read error:"
        run.output shouldContain "is not in the local caches"
        run.output shouldContain "--fetch"
    }

    @Test
    fun `a spec matching no artifact exits 3 naming the side`(@TempDir dir: Path) {
        val (old, _) = corpus(dir)

        val run = run(listOf(old.toString(), dir.resolve("*.absent").toString()))

        run.exit shouldBe 3
        run.output shouldContain "usage error: diff new:"
        run.output shouldContain "matches no artifact"
    }

    @Test
    fun `a spec matching several jars exits 3`(@TempDir dir: Path) {
        val (old, new) = corpus(dir)

        val run = run(listOf(old.toString(), dir.resolve("*.jar").toString()))

        run.exit shouldBe 3
        run.output shouldContain "usage error: diff compares two artifacts;"
        run.output shouldContain "resolved to 2 jars"
    }

    @Test
    fun `a malformed coordinate exits 3 naming the expected shape`(@TempDir dir: Path) {
        val (old, _) = corpus(dir)

        val run = run(listOf("com.example:demo:1.0:extra", old.toString()))

        run.exit shouldBe 3
        run.output shouldContain "usage error: invalid Maven coordinate"
        run.output shouldContain "group:artifact:version"
    }

    @Test
    fun `a corrupt class degrades to a warning and the rest of the diff still answers`(
        @TempDir dir: Path,
    ) {
        // Fault injection (TESTING.md §7): one entry that exists but is not a
        // class file, next to a real one. The readable half must still diff and
        // the unreadable entry must be named, never crash the comparison.
        val (old, new) = corpus(dir)
        val brokenOld = DiffCaseJars.jarFrom(
            dir.resolve("broken-old.jar"),
            mapOf(
                "difffix/Api.class" to classBytes(old, "difffix/Api.class"),
                "difffix/Broken.class" to byteArrayOf(1, 2, 3),
            ),
        )

        val run = run(listOf(brokenOld.toString(), new.toString()))

        run.exit shouldBe 0
        run.output shouldContain "warning CORRUPT_CLASS"
        // The warning names the entry, not an invented type: the skipped class
        // must not turn into a fake TYPE_REMOVED.
        run.output shouldContain "difffix/Broken.class"
        run.output shouldContain "breaking  MEMBER_REMOVED  difffix.Api#legacy()"
        run.output shouldNotContain "TYPE_REMOVED  difffix.Broken"
    }

    /** One entry's raw bytes out of a jar — a zip read, never a class load (D-017). */
    private fun classBytes(jar: Path, entry: String): ByteArray =
        ZipFile(jar.toFile()).use { zip -> zip.getInputStream(zip.getEntry(entry)).readBytes() }
}
