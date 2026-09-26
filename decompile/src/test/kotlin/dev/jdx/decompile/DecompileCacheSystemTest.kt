package dev.jdx.decompile

import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

@Tag("tier2")
class DecompileCacheSystemTest {

    @Test
    fun `explicit cache root wins`() {
        val root = Files.createTempDirectory("jdx-decompile-test")
        DecompileCache.system(root).dir shouldBe root.resolve("decompile")
    }

    @Test
    fun `default system cache ends with decompile`() {
        DecompileCache.system().dir.toString() shouldEndWith "decompile"
    }
}
