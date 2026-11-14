package dev.jdx.index.differential

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * Pins the issue #81 oracle timeout seam: a hung `javap` must surface as
 * [Javap.RunOutcome.TimedOut] (hard failure), never as [Javap.RunOutcome.Unreadable]
 * (soft skip) and never hang until the PIT mutant budget kills the minion.
 *
 * Tier 2: spawns a real subprocess. POSIX-only — the fixture is a shell script.
 */
@Tag("tier2")
@DisabledOnOs(OS.WINDOWS)
class JavapTimeoutTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `a hung oracle spawn reports TimedOut rather than hanging or Unreadable`() {
        val sleeper = writeSleeper("hung-javap")

        val started = System.nanoTime()
        val outcome = Javap.run(
            executable = sleeper.toString(),
            args = listOf("-p", "-s", "-classpath", ".", "dev.jdx.DoesNotMatter"),
            binaryName = "dev.jdx.DoesNotMatter",
            timeoutMs = 200L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        val timedOut = outcome.shouldBeInstanceOf<Javap.RunOutcome.TimedOut>()
        timedOut.timeoutMs shouldBe 200L
        timedOut.binaryName shouldBe "dev.jdx.DoesNotMatter"
        // Must return near the budget, not after the full sleep (30 s).
        (elapsedMs < 5_000L) shouldBe true
    }

    @Test
    fun `a missing executable is Unreadable not TimedOut`() {
        val outcome = Javap.run(
            executable = tempDir.resolve("no-such-javap").toString(),
            args = listOf("-version"),
            binaryName = "dev.jdx.Missing",
            timeoutMs = 200L,
        )
        val unreadable = outcome.shouldBeInstanceOf<Javap.RunOutcome.Unreadable>()
        unreadable.detail shouldContain "cannot start javap"
    }

    private fun writeSleeper(name: String): Path {
        val sleeper = tempDir.resolve(name)
        Files.writeString(sleeper, "#!/bin/sh\nexec sleep 30\n")
        Files.setPosixFilePermissions(sleeper, PosixFilePermissions.fromString("rwxr-xr-x"))
        return sleeper
    }
}
