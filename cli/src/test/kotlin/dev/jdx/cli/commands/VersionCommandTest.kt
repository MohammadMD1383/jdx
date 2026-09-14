package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.BuildInfo
import dev.jdx.cli.JdxCli
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.VersionResult
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import dev.jdx.cli.service.DoctorEnvironment
import dev.jdx.cli.service.DoctorReport
import dev.jdx.cli.service.DoctorService
import dev.jdx.cli.service.fakeEnvironment
import dev.jdx.cli.service.tempDir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class VersionCommandTest {

    @Test
    fun `version text is the same line --version prints`() {
        VersionResult("9.9.9-test").renderText() shouldBe "jdx version 9.9.9-test"
    }

    @Test
    fun `version json carries the envelope with the build version`() {
        val parsed = Json.parseToJsonElement(VersionResult(BuildInfo.version).toJson()).jsonObject

        parsed["jdx"]?.jsonPrimitive?.content shouldBe "1"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["command"]?.jsonPrimitive?.content shouldBe "version"
        parsed["result"]?.jsonObject?.get("version")?.jsonPrimitive?.content shouldBe BuildInfo.version
    }

    @Test
    fun `version run prints the text line by default`() {
        // Note: Clikt 5's parse() already invokes run() (parseAndRun) — calling run()
        // explicitly as well would print twice (L-013).
        val output = captureStdout {
            VersionCommand().parse(emptyList())
        }

        output.trim() shouldBe "jdx version ${BuildInfo.version}"
    }

    @Test
    fun `doctor --json after the subcommand prints the json report`() {
        val doctor = DoctorCommand(okService())

        val output = captureStdout {
            JdxCli().subcommands(doctor).parse(listOf("doctor", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "doctor"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `--json before the subcommand flows to the subcommand`() {
        val doctor = DoctorCommand(okService())

        val output = captureStdout {
            JdxCli().subcommands(doctor).parse(listOf("--json", "doctor"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "doctor"
    }

    @Test
    fun `no --json anywhere prints text`() {
        val doctor = DoctorCommand(okService())

        val output = captureStdout {
            JdxCli().subcommands(doctor).parse(listOf("doctor"))
        }

        output.trim().lines().first() shouldBe "jdx doctor"
    }

    @Test
    fun `effectiveJson is false when neither position sets the flag`() {
        val doctor = DoctorCommand(okService())
        JdxCli().subcommands(doctor).parse(listOf("doctor"))

        doctor.effectiveJson(false) shouldBe false
    }

    private fun okService(): DoctorService {
        val root = tempDir("jdx-version-test-")
        return DoctorService(fakeEnvironment(root))
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
