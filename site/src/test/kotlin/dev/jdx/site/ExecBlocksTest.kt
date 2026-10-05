package dev.jdx.site

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.junit.jupiter.api.Test

/** The anti-drift core: examples are replaced by real runs, and a broken example fails the build. */
class ExecBlocksTest {
    private val calls = mutableListOf<List<String>>()

    private fun runner(exit: Int = 0, output: String = "real output") = JdxRunner { arguments, _ ->
        calls += arguments
        JdxResult(exit, output, "")
    }

    private fun blocks(node: Node): List<FencedCodeBlock> {
        val result = mutableListOf<FencedCodeBlock>()
        node.accept(object : AbstractVisitor() {
            override fun visit(fencedCodeBlock: FencedCodeBlock) { result += fencedCodeBlock }
        })
        return result
    }

    @Test
    fun `stale sample output is replaced by the real run`() {
        val document = Markdown.parse("# T\n\n```console exec\n$ jdx show Foo\nstale sample\n```\n")
        ExecBlocks.execute(document, runner(), "doc.md")
        val block = blocks(document).single()
        block.literal shouldBe "$ jdx show Foo\nreal output\n"
        block.info shouldBe "console ${ExecBlocks.EXECUTED_MARKER}"
        calls shouldBe listOf(listOf("show", "Foo"))
    }

    @Test
    fun `an unexpected exit code fails the build and names the doc`() {
        val document = Markdown.parse("```console exec\n$ jdx show Foo\n```\n")
        val failure = runCatching { ExecBlocks.execute(document, runner(exit = 1), "docs/guide/x.md") }.exceptionOrNull()
        (failure is SiteBuildException) shouldBe true
        failure!!.message!! shouldContain "docs/guide/x.md"
        failure.message!! shouldContain "exited 1"
    }

    @Test
    fun `a promised non-zero exit is checked and shown`() {
        val document = Markdown.parse("```console exec\n$ jdx body Foo#bar   # exit 2\n```\n")
        ExecBlocks.execute(document, runner(exit = 2, output = "ambiguous"), "doc.md")
        blocks(document).single().literal shouldBe "$ jdx body Foo#bar\nambiguous\n[exit 2]\n"
    }

    @Test
    fun `max-lines caps output and says so`() {
        val document = Markdown.parse("```console exec max-lines=2\n$ jdx ls\n```\n")
        ExecBlocks.execute(document, runner(output = "a\nb\nc\nd"), "doc.md")
        blocks(document).single().literal shouldContain "a\nb\n… 2 more lines"
    }

    @Test
    fun `plain console blocks are never executed`() {
        val document = Markdown.parse("```console\n$ jdx show Foo\n```\n\n```bash\njdx show Foo\n```\n")
        ExecBlocks.execute(document, runner(), "doc.md")
        calls shouldBe emptyList()
    }
}
