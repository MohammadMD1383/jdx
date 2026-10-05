package dev.jdx.site

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Doc commands must run exactly as a reader would type them into a POSIX shell. */
class ShellWordsTest {

    @Test
    fun `quotes, escapes and comments split like a shell`() {
        ShellWords.split("jdx body 'Gson#toJson(Object)' --coord a:b:1") shouldBe
            listOf("jdx", "body", "Gson#toJson(Object)", "--coord", "a:b:1")
        ShellWords.split("jdx show \"java.util.Map\\\$Entry\"") shouldBe listOf("jdx", "show", "java.util.Map\$Entry")
        ShellWords.split("jdx show Foo   # exit 2") shouldBe listOf("jdx", "show", "Foo")
        ShellWords.split("jdx show A#b") shouldBe listOf("jdx", "show", "A#b")
    }

    @Test
    fun `single-quoting any text without a quote round-trips`() = runBlocking<Unit> {
        checkAll(300, Arb.string(0..40).filter { '\'' !in it }) { text ->
            ShellWords.split("jdx show '$text'") shouldBe listOf("jdx", "show", text)
        }
    }

    @Test
    fun `echo pipe feeds stdin`() {
        val command = ShellWords.parseJdxCommand("echo '{\"command\":\"show\"}' | jdx batch")
        command.arguments shouldBe listOf("batch")
        command.stdin shouldBe "{\"command\":\"show\"}\n"
    }

    @Test
    fun `anything that is not a plain jdx invocation is refused`() {
        listOf("ls -la", "jdx show A > out.txt", "jdx show A && rm -rf x", "cat f | jdx batch").forEach { line ->
            (runCatching { ShellWords.parseJdxCommand(line) }.exceptionOrNull() is SiteBuildException) shouldBe true
        }
    }

    @Test
    fun `exit annotation is metadata, not part of the displayed command`() {
        ShellWords.expectedExit("jdx show Foo   # exit 2") shouldBe 2
        ShellWords.expectedExit("jdx show Foo") shouldBe null
        ShellWords.displayForm("jdx show Foo   # exit 2") shouldBe "jdx show Foo"
    }
}
