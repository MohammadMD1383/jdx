package dev.jdx.cli

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

/** In-process tests of the root command; the launcher-level round-trip lives in `:app`. */
class JdxCliTest {

    @Test
    fun `--version prints the build version and exits zero`() {
        val thrown = shouldThrow<PrintMessage> { JdxCli().parse(listOf("--version")) }

        thrown.message?.trim() shouldBe "jdx version ${BuildInfo.version}"
        thrown.printError shouldBe false
        thrown.statusCode shouldBe 0
    }

    @Test
    fun `build version comes from the build, not a hard-coded string`() {
        // "dev" is the missing-resource fallback; seeing it here would mean the Gradle
        // generateBuildProperties wiring is broken (cli/build.gradle.kts).
        BuildInfo.version shouldNotBe "dev"
    }
}
