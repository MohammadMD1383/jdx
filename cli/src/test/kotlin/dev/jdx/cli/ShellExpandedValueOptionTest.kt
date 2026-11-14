package dev.jdx.cli

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the unquoted `--jars`/`--src` glob detector (issue #86).
 * The CLI round-trip lives in [JdxCliTest].
 */
class ShellExpandedValueOptionTest {

    @Test
    fun `shell-expanded jars after a positional is a usage error naming the flag`() {
        val message = ShellExpandedValueOption.detect(
            listOf("outline", "PluginUtils", "--jars", "a.jar", "b.jar", "c.jar"),
        )

        message.shouldNotBeNull()
        message shouldContain "usage error: --jars got 3 paths"
        message shouldContain "quote the glob"
        message shouldContain "--jars '.../lib/*.jar'"
        message shouldContain "repeat --jars per path"
    }

    @Test
    fun `shell-expanded jars before the positional is also caught`() {
        val message = ShellExpandedValueOption.detect(
            listOf("outline", "--jars", "a.jar", "b.jar", "com.example.Foo"),
        )

        message.shouldNotBeNull()
        message shouldContain "--jars got 2 paths"
    }

    @Test
    fun `shell-expanded src dirs are caught the same way`() {
        val message = ShellExpandedValueOption.detect(
            listOf("usages", "com.example.Foo", "--src", "src/main/java", "src/main/kotlin"),
        )

        message.shouldNotBeNull()
        message shouldContain "usage error: --src got 2 paths"
        message shouldContain "quote the glob"
    }

    @Test
    fun `one jars value plus a symbol positional is legitimate`() {
        ShellExpandedValueOption.detect(
            listOf("outline", "--jars", "gson.jar", "com.google.gson.Gson"),
        ).shouldBeNull()
    }

    @Test
    fun `repeated jars flags are legitimate`() {
        ShellExpandedValueOption.detect(
            listOf(
                "outline",
                "com.google.gson.Gson",
                "--jars",
                "a.jar",
                "--jars",
                "b.jar",
            ),
        ).shouldBeNull()
    }

    @Test
    fun `quoted-style single glob token is legitimate`() {
        ShellExpandedValueOption.detect(
            listOf("outline", "PluginUtils", "--jars", "lib/*.jar"),
        ).shouldBeNull()
    }

    @Test
    fun `equals form cannot be shell-split into multiple values`() {
        ShellExpandedValueOption.detect(
            listOf("outline", "PluginUtils", "--jars=a.jar", "b.jar"),
        ).shouldBeNull()
    }

    @Test
    fun `path-like heuristic accepts jars zips globs and separators`() {
        ShellExpandedValueOption.looksLikePathOrGlob("a.jar") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("Lib.JAR") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("x.zip") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("java.base.jmod") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("lib/*.jar") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("foo/bar") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("C:\\libs\\a.jar") shouldBe true
        ShellExpandedValueOption.looksLikePathOrGlob("com.example.Foo") shouldBe false
        ShellExpandedValueOption.looksLikePathOrGlob("PluginUtils") shouldBe false
    }
}
