package dev.jdx.cli.commands

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `USERPROFILE` home fallback for the minecraft probe (phase 5, tier 1):
 * `HOME` wins, `USERPROFILE` answers Windows layouts, `user.home` is last —
 * all over `@TempDir`, never the real home.
 */
class BenchProbeTest {

    private fun loomWithClient(root: Path): Path {
        val version = root.resolve("fakehome/.gradle/caches/fabric-loom/1.0")
        Files.createDirectories(version)
        Files.write(version.resolve("minecraft-client.jar"), byteArrayOf(0x50, 0x4b))
        return root.resolve("fakehome")
    }

    @Test
    fun `HOME wins when set`(@TempDir tmp: Path) {
        val home = loomWithClient(tmp)
        val probe = defaultMinecraftProbe { name -> if (name == "HOME") home.toString() else null }

        probe shouldBe home.resolve(".gradle/caches/fabric-loom/1.0/minecraft-client.jar")
    }

    @Test
    fun `USERPROFILE answers when HOME is unset`(@TempDir tmp: Path) {
        val home = loomWithClient(tmp)
        val probe = defaultMinecraftProbe { name -> if (name == "USERPROFILE") home.toString() else null }

        probe shouldBe home.resolve(".gradle/caches/fabric-loom/1.0/minecraft-client.jar")
    }

    @Test
    fun `a missing loom cache reads as no default jar`(@TempDir tmp: Path) {
        val probe = defaultMinecraftProbe { name ->
            if (name == "HOME" || name == "USERPROFILE") tmp.resolve("no-such-home").toString() else null
        }

        probe shouldBe null
    }
}
