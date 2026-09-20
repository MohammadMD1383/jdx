package dev.jdx.decompile

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure cache-key math behind [DecompileCache] (T-026): deterministic,
 * collision-free across bytes, and filename-safe for hostile engine labels.
 * No IO here — reads and writes are tier 2.
 */
class DecompileCacheKeyTest {

    private val cache = DecompileCache(Path.of("unused"))

    @Test
    fun `same bytes give the same entry file`() {
        val bytes = "class-bytes".toByteArray()
        cache.entryFile(bytes, "vineflower", "1.12.0") shouldBe
            cache.entryFile(bytes, "vineflower", "1.12.0")
    }

    @Test
    fun `different bytes or versions give different entry files`() {
        val first = cache.entryFile("a".toByteArray(), "vineflower", "1.12.0")
        first shouldNotBe cache.entryFile("b".toByteArray(), "vineflower", "1.12.0")
        first shouldNotBe cache.entryFile("a".toByteArray(), "vineflower", "9.9.9")
        first shouldNotBe cache.entryFile("a".toByteArray(), "javap", "1.12.0")
    }

    @Test
    fun `hostile engine labels stay one safe filename segment`() {
        val file = cache.entryFile("a".toByteArray(), "../../etc/evil", "1.0\n../x")
        file.parent shouldBe Path.of("unused")
        file.fileName.toString() shouldNotContain "/"
        file.fileName.toString() shouldNotContain ".."
    }

    @Test
    fun `entry file math never throws`() = runBlocking<Unit> {
        checkAll(500, Arb.string(0, 40)) { label ->
            cache.entryFile(label.toByteArray(), label, label)
        }
    }
}
