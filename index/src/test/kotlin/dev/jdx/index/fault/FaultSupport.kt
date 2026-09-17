package dev.jdx.index.fault

import dev.jdx.index.service.JdxService
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Shared machinery for the fault-injection suite (T-057, TESTING.md §7).
 *
 * Every fault test asserts the same three laws:
 * 1. the query returns a documented D-015 exit code (never a throw),
 * 2. the rendered output names the problem (artifact, class, or cap),
 * 3. no JVM stack trace reaches `stdout`, `stderr`, or either rendering.
 *
 * Queries run inside [captureStreams] so a regression that prints a trace fails
 * the test that caused it instead of polluting the build log. `jdx` never prints
 * traces itself — the buffers are normally empty — but the assertion is the point:
 * an agent receiving `at dev.jdx.index...` lines cannot act on them (D-015).
 */
internal object FaultSupport {

    /** One query's captured side channels plus its return value. */
    internal data class Captured<T>(
        val value: T,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Runs [block] with `System.out`/`System.err` captured. Restores both even
     * when [block] throws, so one leaking test cannot blind the rest of the suite.
     */
    internal fun <T> captureStreams(block: () -> T): Captured<T> {
        val originalOut = System.out
        val originalErr = System.err
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))
        System.setErr(PrintStream(errBuffer))
        try {
            val value = block()
            return Captured(value, outBuffer.toString(Charsets.UTF_8), errBuffer.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    /** Fragments that prove a JVM stack trace leaked into user-facing output. */
    private val TRACE_MARKERS: List<String> = listOf(
        "\tat ",
        "at dev.jdx.",
        "at java.",
        "at kotlin.",
        "at org.",
        "Exception in thread",
        "Caused by:",
    )

    /**
     * Fails when [text] carries any stack-trace fragment. [context] names the
     * fault under test so the failure points at the leaking case, not the helper.
     */
    internal fun assertNoStackTrace(text: String, context: String) {
        for (marker in TRACE_MARKERS) {
            withClue("$context: output leaked a stack trace via '$marker'") {
                text shouldNotContain marker
            }
        }
    }

    /** Both renderings of one outcome, for the text⊆JSON spot-checks. */
    internal data class Rendered(
        val text: String,
        val json: String,
    )

    /**
     * Renders [outcome] both ways inside the caller's stream capture and asserts
     * neither rendering throws nor leaks a trace. Returns both strings.
     */
    internal fun renderBoth(outcome: JdxService.ServiceOutcome, command: String): Rendered {
        val text = outcome.renderText(false)
        val json = outcome.toJson(command)
        assertNoStackTrace(text, "$command text rendering")
        assertNoStackTrace(json, "$command JSON rendering")
        return Rendered(text, json)
    }

    /** Roots over exactly [jars], never the ambient JDK: faults stay hermetic. */
    internal fun rootsOf(vararg jars: Path): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = jars.map { it.toString() }, includeJdk = false)

    /**
     * Runs `show` inside stream capture, renders both ways, and asserts the
     * three fault laws: [expectedExit], every [names] string present in *both*
     * renderings, and silence on both streams. Returns the renderings.
     */
    internal fun checkShow(
        ref: String,
        roots: JdxService.RootsSpec,
        expectedExit: Int,
        vararg names: String,
    ): Rendered {
        val captured = captureStreams { JdxService.show(ref, roots) }
        assertNoStackTrace(captured.stdout, "show($ref) stdout")
        assertNoStackTrace(captured.stderr, "show($ref) stderr")
        val outcome = captured.value
        withClue("show($ref): expected exit $expectedExit, got ${outcome.exitCode}") {
            outcome.exitCode shouldBe expectedExit
        }
        val rendered = renderBoth(outcome, "show")
        for (name in names) {
            withClue("show($ref) text names '$name'") { rendered.text shouldContain name }
            withClue("show($ref) JSON names '$name'") { rendered.json shouldContain name }
        }
        return rendered
    }

    /**
     * The `members` twin of [checkShow]: same three laws for the listing path,
     * which resolves supertypes and therefore faults differently from `show`.
     */
    internal fun checkMembers(
        ref: String,
        roots: JdxService.RootsSpec,
        expectedExit: Int,
        vararg names: String,
    ): Rendered {
        val captured = captureStreams { JdxService.members(ref, roots) }
        assertNoStackTrace(captured.stdout, "members($ref) stdout")
        assertNoStackTrace(captured.stderr, "members($ref) stderr")
        val outcome = captured.value
        withClue("members($ref): expected exit $expectedExit, got ${outcome.exitCode}") {
            outcome.exitCode shouldBe expectedExit
        }
        val rendered = renderBoth(outcome, "members")
        for (name in names) {
            withClue("members($ref) text names '$name'") { rendered.text shouldContain name }
            withClue("members($ref) JSON names '$name'") { rendered.json shouldContain name }
        }
        return rendered
    }
}
