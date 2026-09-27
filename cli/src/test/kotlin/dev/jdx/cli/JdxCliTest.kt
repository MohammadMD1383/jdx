package dev.jdx.cli

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
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

    // ------------------------------------------------------------------ exit codes
    //
    // Clikt-level usage errors never reach JdxService: clikt hands the status to the
    // Context.exitProcess seam owned by buildRoot. These go through main() (not
    // parse(), which throws instead of invoking the seam) with a recording
    // terminate so the host JVM survives. The launcher-level round-trip lives
    // in :app (LauncherScriptTest).

    private fun mainExitCodes(argv: List<String>): List<Int> {
        val codes = mutableListOf<Int>()
        buildRoot(terminate = codes::add).main(argv)
        return codes
    }

    @Test
    fun `missing argument exits 3`() {
        mainExitCodes(listOf("members")) shouldBe listOf(3)
    }

    @Test
    fun `unknown command exits 3`() {
        mainExitCodes(listOf("nosuchcommand")) shouldBe listOf(3)
    }

    @Test
    fun `unknown option exits 3`() {
        mainExitCodes(listOf("members", "java.lang.String", "--nope")) shouldBe listOf(3)
    }

    @Test
    fun `--version still exits 0`() {
        mainExitCodes(listOf("--version")) shouldBe listOf(0)
    }

    @Test
    fun `--help still exits 0`() {
        mainExitCodes(listOf("--help")) shouldBe listOf(0)
    }
}
