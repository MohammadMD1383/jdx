package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Examples for the call-hierarchy renderer (T-033, PROPOSAL.md §7.3): a
 * depth-bounded tree of canonical method refs, indented by depth, kind-led
 * lines (§8.1), cycle-safe with `…(cycle)` markers.
 */
class CallsTest {

    private fun node(
        ref: String,
        artifact: String? = "calls-case.jar",
        cycle: Boolean = false,
        children: List<CallNode> = emptyList(),
    ): CallNode = CallNode(ref = ref, artifact = artifact, cycle = cycle, children = children)

    @Test
    fun `callers text nests children by depth with artifact labels`() {
        val listing = buildCallListing(
            query = "c.Lib#greet(java.lang.String)",
            targetRef = "c.Lib#greet(java.lang.String)",
            direction = CallDirection.CALLERS,
            roots = listOf(
                node("c.App#run()", children = listOf(node("c.Main#main(java.lang.String[])"))),
                node("c.Other#work()"),
            ),
        )
        listing.renderText() shouldBe (
            "callers of 'c.Lib#greet(java.lang.String)'\n" +
                "  call c.App#run()  calls-case.jar\n" +
                "    call c.Main#main(java.lang.String[])  calls-case.jar\n" +
                "  call c.Other#work()  calls-case.jar"
            )
    }

    @Test
    fun `calls text uses the from header`() {
        val listing = buildCallListing(
            query = "c.App#run()",
            targetRef = "c.App#run()",
            direction = CallDirection.CALLS,
            roots = listOf(node("c.Lib#greet(java.lang.String)")),
        )
        listing.renderText() shouldBe (
            "calls from 'c.App#run()'\n" +
                "  call c.Lib#greet(java.lang.String)  calls-case.jar"
            )
    }

    @Test
    fun `re-entrant rows carry the cycle marker and drop their subtree`() {
        val listing = buildCallListing(
            query = "c.A#ping()",
            targetRef = "c.A#ping()",
            direction = CallDirection.CALLERS,
            roots = listOf(
                node(
                    "c.B#pong()",
                    children = listOf(
                        // A cycle row is a leaf by definition: even a malformed
                        // node carrying children renders without descending.
                        node("c.A#ping()", cycle = true, children = listOf(node("c.B#pong()"))),
                    ),
                ),
            ),
        )
        val text = listing.renderText()
        text shouldContain "  call c.B#pong()  calls-case.jar"
        text shouldContain "    call c.A#ping()  calls-case.jar …(cycle)"
        text.lines().size shouldBe 3
    }

    @Test
    fun `rows without a known artifact omit the label`() {
        val listing = buildCallListing(
            query = "c.App#run()",
            targetRef = "c.App#run()",
            direction = CallDirection.CALLS,
            roots = listOf(node("jdk.Missing#gone()", artifact = null)),
        )
        listing.renderText() shouldBe (
            "calls from 'c.App#run()'\n" +
                "  call jdk.Missing#gone()"
            )
    }

    @Test
    fun `truncation footer names the total`() {
        val roots = (1..7).map { node("c.App#m$it()") }
        val listing = buildCallListing("q", "c.App#run()", CallDirection.CALLERS, roots, limit = 5)
        val text = listing.renderText()
        text shouldContain "5 of 7 calls shown (--limit 7 to see more)"
        text shouldNotContain "c.App#m6()"
        buildCallListing("q", "t", CallDirection.CALLERS, roots.take(5), limit = 5).truncation shouldBe null
    }

    @Test
    fun `truncation keeps a connected pre-order prefix`() {
        val roots = listOf(
            node("c.A#a()", children = listOf(node("c.B#b()", children = listOf(node("c.C#c()"))))),
            node("c.D#d()"),
        )
        // Four nodes total; keeping three must keep A, B, C — never an
        // orphaned child without its parent.
        val listing = buildCallListing("q", "t", CallDirection.CALLS, roots, limit = 3)
        listing.rows.map { it.ref } shouldBe listOf("c.A#a()", "c.B#b()", "c.C#c()")
        listing.rows.map { it.depth } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `empty listings name the direction`() {
        buildCallListing("q", "t", CallDirection.CALLERS, emptyList()).renderText() shouldBe "no callers of 'q'"
        buildCallListing("q", "t", CallDirection.CALLS, emptyList()).renderText() shouldBe "no calls from 'q'"
    }

    @Test
    fun `json carries every text fact`() {
        val listing = buildCallListing(
            query = "c.Lib#greet",
            targetRef = "c.Lib#greet",
            direction = CallDirection.CALLERS,
            roots = listOf(
                node(
                    "c.App#run()",
                    children = listOf(node("c.A#ping()", cycle = true)),
                ),
            ),
        )
        val json = listing.toJson("callers")
        json shouldContain "\"direction\":\"callers\""
        json shouldContain "\"target\":\"c.Lib#greet\""
        json shouldContain "\"ref\":\"c.App#run()\""
        json shouldContain "\"artifact\":\"calls-case.jar\""
        json shouldContain "\"depth\":2"
        json shouldContain "\"cycle\":true"
        // The artifact-less row omits the key rather than printing null.
        val noArtifact = buildCallListing(
            "q",
            "t",
            CallDirection.CALLS,
            listOf(node("jdk.Missing#gone()", artifact = null)),
        ).toJson("calls")
        noArtifact shouldNotContain "\"artifact\""
        noArtifact shouldContain "\"direction\":\"calls\""
    }
}
