package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Tier 1: `serve` flag handling never binds a socket — the runner is injected. */
class ServeCommandTest {

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
    fun `defaults are localhost-only on the documented port`() {
        var served: Triple<String, Int, String>? = null
        ServeCommand(
            terminate = { throw DaemonExit(it) },
            serve = { bind, port, workspace -> served = Triple(bind, port, workspace) },
        ).parse(emptyList())
        served shouldBe Triple("127.0.0.1", 7070, "default")
    }

    @Test
    fun `flags reach the runner verbatim`() {
        var served: Triple<String, Int, String>? = null
        ServeCommand(
            terminate = { throw DaemonExit(it) },
            serve = { bind, port, workspace -> served = Triple(bind, port, workspace) },
        ).parse(listOf("--bind", "0.0.0.0", "--port", "8080", "-w", "fx"))
        served shouldBe Triple("0.0.0.0", 8080, "fx")
    }

    @Test
    fun `an out-of-range port exits 3 without serving`() {
        var code = -1
        var served = false
        val err = capturedStdout {
            try {
                ServeCommand(
                    terminate = { throw DaemonExit(it) },
                    serve = { _, _, _ -> served = true },
                ).parse(listOf("--port", "0"))
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 3
        served shouldBe false
        err shouldContain "--port"
    }

    @Test
    fun `a bind failure exits 6`() {
        var code = -1
        val err = capturedStdout {
            try {
                ServeCommand(
                    terminate = { throw DaemonExit(it) },
                    serve = { _, _, _ -> throw java.io.IOException("permission denied") },
                ).parse(emptyList())
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        code shouldBe 6
        err shouldContain "cannot serve"
    }
}
