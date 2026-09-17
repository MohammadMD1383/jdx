package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for the hand-rolled workspace TOML codec (T-015). Pure string work — tier 1.
 *
 * The codec is intentionally minimal (three keys); these tests pin the exact file shape
 * so a hand-editing user and the writer can never disagree about what a workspace file
 * looks like.
 */
class WorkspaceTomlTest {

    @Test
    fun `encode writes the canonical shape`() {
        val text = WorkspaceToml.encode(WorkspaceDefinition("mc", listOf("a.jar", "b/*.jar"), true))
        text shouldBe "# Managed by `jdx ws`. Human-editable: keep `name` equal to the file name.\n" +
            "name = \"mc\"\n" +
            "jars = [\"a.jar\", \"b/*.jar\"]\n" +
            "coords = []\n" +
            "include_jdk = true\n"
    }

    @Test
    fun `coords round-trip through encode`() {
        val original = WorkspaceDefinition(
            "mc",
            listOf("a.jar"),
            false,
            listOf("com.google.code.gson:gson:2.14.0", "org.example:lib:1.0"),
        )
        val decoded = WorkspaceToml.decode(WorkspaceToml.encode(original), "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow() shouldBe original
    }

    @Test
    fun `coords default to empty for pre-coords files`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\njars = [\"a.jar\"]\ninclude_jdk = true\n", "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow().coords shouldBe emptyList()
    }

    @Test
    fun `encode escapes hostile strings`() {
        val text = WorkspaceToml.encode(WorkspaceDefinition("mc", listOf("a\"b\\c"), false))
        val decoded = WorkspaceToml.decode(text, "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow().jars shouldBe listOf("a\"b\\c")
        decoded.getOrThrow().includeJdk shouldBe false
    }

    @Test
    fun `decode round-trips encode`() {
        val original = WorkspaceDefinition("my-app", listOf("~/.gradle/caches/**/*.jar", "./build/classes"), true)
        val decoded = WorkspaceToml.decode(WorkspaceToml.encode(original), "my-app.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow() shouldBe original
    }

    @Test
    fun `missing keys default sanely`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\n", "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow() shouldBe WorkspaceDefinition("mc", emptyList(), true)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val text = "# a comment\n\nname = \"mc\" # trailing comment\njars = [] # empty\n"
        val decoded = WorkspaceToml.decode(text, "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow() shouldBe WorkspaceDefinition("mc", emptyList(), true)
    }

    @Test
    fun `a hash inside a string is not a comment`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\njars = [\"a#b.jar\"]\n", "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow().jars shouldBe listOf("a#b.jar")
    }

    @Test
    fun `camelCase includeJdk is an accepted alias`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\nincludeJdk = false\n", "mc.toml")
        (decoded.isSuccess) shouldBe true
        decoded.getOrThrow().includeJdk shouldBe false
    }

    @Test
    fun `both jdk spellings are a duplicate key`() {
        val decoded = WorkspaceToml.decode(
            "name = \"mc\"\ninclude_jdk = true\nincludeJdk = false\n",
            "mc.toml",
        )
        (decoded.isFailure) shouldBe true
        decoded.exceptionOrNull()?.message shouldContain "duplicate key"
    }

    @Test
    fun `a name disagreeing with the file stem is rejected`() {
        val decoded = WorkspaceToml.decode("name = \"other\"\n", "mc.toml")
        (decoded.isFailure) shouldBe true
        decoded.exceptionOrNull()?.message shouldContain "must match"
    }

    @Test
    fun `unknown keys are rejected, not ignored`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\nsrc = [\"x\"]\n", "mc.toml")
        (decoded.isFailure) shouldBe true
        decoded.exceptionOrNull()?.message shouldContain "unknown key 'src'"
    }

    @Test
    fun `duplicate keys are rejected`() {
        val decoded = WorkspaceToml.decode("name = \"mc\"\nname = \"mc\"\n", "mc.toml")
        (decoded.isFailure) shouldBe true
        decoded.exceptionOrNull()?.message shouldContain "duplicate key"
    }

    @Test
    fun `malformed values name the line`() {
        val badJars = WorkspaceToml.decode("name = \"mc\"\njars = a.jar\n", "mc.toml")
        (badJars.isFailure) shouldBe true
        badJars.exceptionOrNull()?.message shouldContain "mc.toml:2"

        val badBool = WorkspaceToml.decode("name = \"mc\"\ninclude_jdk = yes\n", "mc.toml")
        (badBool.isFailure) shouldBe true
        badBool.exceptionOrNull()?.message shouldContain "must be true or false"

        val noEquals = WorkspaceToml.decode("name \"mc\"\n", "mc.toml")
        (noEquals.isFailure) shouldBe true
        noEquals.exceptionOrNull()?.message shouldContain "expected `key = value`"

        val missing = WorkspaceToml.decode("jars = []\n", "mc.toml")
        (missing.isFailure) shouldBe true
        missing.exceptionOrNull()?.message shouldContain "missing required key `name`"
    }

    @Test
    fun `stripComment honours strings and escapes`() {
        WorkspaceToml.stripComment("jars = [\"a#b\"] # real comment") shouldBe "jars = [\"a#b\"] "
        WorkspaceToml.stripComment("name = \"a\\\"#b\" # c") shouldBe "name = \"a\\\"#b\" "
        WorkspaceToml.stripComment("# whole line") shouldBe ""
    }
}
