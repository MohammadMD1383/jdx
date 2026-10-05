package dev.jdx.site

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Clikt help parsing, and the doc linter built on it. */
class CliFactsTest {
    private val membersHelp = """
        Usage: jdx members [<options>] <ref>

          List the members of a type.

        Options:
          --declared                               Only declared members.
          --kind=(all|method|field)                Member kind.
          -w, --workspace=<text>                   Use a named workspace.
          -h, --help                               Show this message and exit

        Arguments:
          <ref>  Type reference.
    """.trimIndent()

    private val rootHelp = """
        Usage: jdx [<options>] <command> [<args>]...

        Options:
          --json      Emit JSON.
          -h, --help  Show this message and exit

        Commands:
          members  List members.
          ws       Manage workspaces.
    """.trimIndent()

    private val wsHelp = """
        Usage: jdx ws [<options>] <command> [<args>]...

          Manage workspaces.

        Commands:
          create  Create one.
    """.trimIndent()

    private val wsCreateHelp = """
        Usage: jdx ws create [<options>] <name>

          Create one.

        Options:
          --jars=<text>  Roots.
    """.trimIndent()

    private fun facts(): CliFacts {
        val root = CliFacts.parseHelp(emptyList(), rootHelp)
        val commands = listOf(
            CliFacts.parseHelp(listOf("members"), membersHelp),
            CliFacts.parseHelp(listOf("ws"), wsHelp),
            CliFacts.parseHelp(listOf("ws", "create"), wsCreateHelp),
        )
        return CliFacts(root, commands, emptyList(), "", emptyList(), null)
    }

    @Test
    fun `help is parsed into usage, description, options and arguments`() {
        val help = CliFacts.parseHelp(listOf("members"), membersHelp)
        help.usage shouldBe "jdx members [<options>] <ref>"
        help.description shouldBe "List the members of a type."
        help.flagNames shouldContainAll listOf("--declared", "--kind", "-w", "--workspace", "-h", "--help")
        help.arguments shouldBe listOf("<ref>" to "Type reference.")
    }

    @Test
    fun `lint accepts real commands, flags, globals and placeholders`() {
        DocLint(facts()).check(
            listOf(
                "jdx members java.util.Map --declared --kind=method",
                "$ jdx --json members Foo",
                "jdx -w fx members Foo --json",
                "jdx ws create fx --jars 'libs/*.jar'",
                "jdx ws create|list",
                "jdx <command> --help",
                "not a jdx line --nope",
            ),
            "doc.md",
        ).shouldBeEmpty()
    }

    @Test
    fun `lint reports unknown commands and flags`() {
        DocLint(facts()).check(
            listOf("jdx membres Foo", "jdx members Foo --inheritted", "jdx ws create fx --src x"),
            "doc.md",
        ) shouldBe listOf(
            "doc.md: `jdx membres Foo` names unknown command `jdx membres`",
            "doc.md: `jdx members Foo --inheritted` uses `--inheritted`, which `jdx members --help` does not list",
            "doc.md: `jdx ws create fx --src x` uses `--src`, which `jdx ws create --help` does not list",
        )
    }

    @Test
    fun `releases parse newest first, skipping drafts and prereleases`() {
        val json = """[
            {"tag_name":"v1.0.0","name":"","published_at":"2026-01-01T00:00:00Z","body":"a","html_url":"u1","draft":false,"prerelease":false},
            {"tag_name":"v2.0.0-rc1","name":"rc","published_at":"2026-03-01T00:00:00Z","body":"","html_url":"u3","draft":false,"prerelease":true},
            {"tag_name":"v1.1.0","name":"One one","published_at":"2026-02-01T00:00:00Z","body":null,"html_url":"u2","draft":false,"prerelease":false}
        ]"""
        ProjectInfo.parseReleases(json).map { it.tag to it.name } shouldBe listOf("v1.1.0" to "One one", "v1.0.0" to "v1.0.0")
    }
}
