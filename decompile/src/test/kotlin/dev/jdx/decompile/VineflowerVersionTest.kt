package dev.jdx.decompile

import io.kotest.matchers.shouldBe
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.junit.jupiter.api.Test

/**
 * The [VINEFLOWER_VERSION] constant is part of every cache key: it must name
 * the engine on the classpath, or upgrades silently serve stale
 * reconstructions. Pinned against the engine's own version string (loading
 * one Vineflower class in a test is fine — the laziness promise is about the
 * query path, not the test path).
 */
class VineflowerVersionTest {

    @Test
    fun `pinned version matches the bundled engine`() {
        VINEFLOWER_VERSION shouldBe ConsoleDecompiler.version()
    }
}
