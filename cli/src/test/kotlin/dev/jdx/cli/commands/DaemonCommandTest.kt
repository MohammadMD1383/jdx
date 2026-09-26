package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** Tier 1: daemon flag handling never touches a real socket — temp runtime dir, stub spawner. */
class DaemonCommandTest {

    @TempDir
    lateinit var tempDir: Path

    private fun env(): DaemonEnv = DaemonEnv(tempDir, "java-stub", "cp-stub")

    /** Runs [block] with stdout captured; returns what it printed. */
    private fun capturedStdout(block: () -> Unit): String {
        val sink = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(sink))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return sink.toString(Charsets.UTF_8)
    }

    @Test
    fun `formatUptime stays short`() {
        formatUptime(0) shouldBe "0s"
        formatUptime(12) shouldBe "12s"
        formatUptime(90) shouldBe "1m 30s"
        formatUptime(5 * 60L) shouldBe "5m 00s"
        formatUptime(2 * 3600 + 5 * 60) shouldBe "2h 05m"
        formatUptime(-3) shouldBe "0s"
    }

    @Test
    fun `status on a stopped daemon exits 1`() {
        var code = -1
        val out = capturedStdout {
            try {
                DaemonStatusCommand(env()) { throw DaemonExit(it) }.parse(listOf("--workspace", "ws1"))
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 1
        out shouldContain "not running"
        out shouldContain "ws1"
    }

    @Test
    fun `stop on a stopped daemon exits 1`() {
        var code = -1
        val out = capturedStdout {
            try {
                DaemonStopCommand(env()) { throw DaemonExit(it) }.parse(emptyList())
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 1
        out shouldContain "not running"
    }

    @Test
    fun `status json on a stopped daemon is an ok-false envelope`() {
        var code = -1
        val out = capturedStdout {
            try {
                DaemonStatusCommand(env()) { throw DaemonExit(it) }.parse(listOf("--json"))
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 1
        val root = Json.parseToJsonElement(out.trim()).jsonObject
        root["ok"]!!.jsonPrimitive.content shouldBe "false"
        root["command"]!!.jsonPrimitive.content shouldBe "daemon"
        root["result"]!!.jsonObject["running"]!!.jsonPrimitive.content shouldBe "false"
    }

    @Test
    fun `start without a runtime dir exits 3`() {
        var code = -1
        val out = capturedStdout {
            try {
                val noRuntime = DaemonEnv(null, "java-stub", "cp-stub")
                DaemonStartCommand(noRuntime) { throw DaemonExit(it) }.parse(emptyList())
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 3
        out shouldContain "socket directory"
    }

    @Test
    fun `start with a bad idle exits 3`() {
        var code = -1
        val out = capturedStdout {
            try {
                DaemonStartCommand(env()) { throw DaemonExit(it) }
                    .parse(listOf("--idle", "soon"))
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 3
        out shouldContain "invalid --idle"
    }

    @Test
    fun `a failed spawn exits 6`() {
        var code = -1
        val out = capturedStdout {
            try {
                // The default test spawner returns null: the spawn always fails.
                testDaemonGroup(tempDir) { throw DaemonExit(it) }
                    .let { group ->
                        group.parse(listOf("start", "--workspace", "ws2", "--idle", "0"))
                    }
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 6
        out shouldContain "spawner failed"
    }

    @Test
    fun `the null device is NUL on Windows and dev-null elsewhere`() {
        nullDeviceName("Windows 11") shouldBe "NUL"
        nullDeviceName("Windows 10") shouldBe "NUL"
        nullDeviceName("Linux") shouldBe "/dev/null"
        nullDeviceName("Mac OS X") shouldBe "/dev/null"
    }
}
