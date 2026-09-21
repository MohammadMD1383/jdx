package dev.jdx.index.kotlin

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode

/**
 * Generative tests for [KotlinMetadataReader] (T-035, TESTING.md §4): whatever shapes
 * the fuzzer invents for annotation values, reading never throws and is deterministic.
 * Real `@Metadata` payloads decode in `KotlinClassesTest` (tier 2).
 */
class KotlinMetadataPropertyTest {

    private val keysArb: Arb<List<String>> = Arb.list(Arb.string(0..4), 0..6)

    private val numbersArb: Arb<List<Int>> = Arb.list(Arb.int(-100..300), 0..6)

    @Test
    fun `reading never throws on arbitrary annotation values`() = runBlocking<Unit> {
        checkAll(1_000, keysArb, numbersArb) { keys, numbers ->
            val node = AnnotationNode("Lkotlin/Metadata;").apply {
                val flat = mutableListOf<Any?>()
                for (key in keys) {
                    flat.add(key)
                    flat.add(numbers)
                }
                values = flat.ifEmpty { null }
            }
            // The assertion is the absence of a throw: any result (null or decoded) is fine.
            KotlinMetadataReader.read(listOf(node), null)?.metadataKind
        }
    }

    @Test
    fun `reading is deterministic`() = runBlocking<Unit> {
        checkAll(1_000, numbersArb) { numbers ->
            val node = AnnotationNode("Lkotlin/Metadata;").apply {
                values = mutableListOf("k", 1, "mv", numbers, "d1", listOf("a"), "d2", listOf("b"))
            }
            val first = KotlinMetadataReader.read(listOf(node), null)
            val second = KotlinMetadataReader.read(listOf(node), null)
            first shouldBe second
        }
    }
}
