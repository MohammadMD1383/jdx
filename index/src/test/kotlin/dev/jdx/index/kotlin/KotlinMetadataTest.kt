package dev.jdx.index.kotlin

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.objectweb.asm.tree.AnnotationNode

/**
 * Tier-1 tests for [KotlinMetadataReader] (T-035): annotations are built in memory,
 * so no disk, no jars — the fast loop stays fast. Decoding against real Kotlin
 * class files lives in `KotlinClassesTest` (tier 2).
 */
class KotlinMetadataTest {

    private fun metadataNode(vararg pairs: Any?): AnnotationNode =
        AnnotationNode("Lkotlin/Metadata;").apply {
            val flat = mutableListOf<Any?>()
            var index = 0
            while (index + 1 < pairs.size) {
                flat.add(pairs[index] as String)
                flat.add(pairs[index + 1])
                index += 2
            }
            values = flat
        }

    @Test
    fun `no metadata annotation means not kotlin`() {
        KotlinMetadataReader.read(null, null) shouldBe null
        KotlinMetadataReader.read(emptyList(), emptyList()) shouldBe null
    }

    @Test
    fun `a non-metadata annotation is ignored`() {
        val nodes = listOf(AnnotationNode("Ljava/lang/Deprecated;"))
        KotlinMetadataReader.read(nodes, null) shouldBe null
    }

    @Test
    fun `a metadata annotation with garbage values degrades to null`() {
        // Never throws: corrupt metadata is a missing signal, not a failure (D-017).
        val garbage = metadataNode("k", "not-an-int", "mv", listOf("x"), "d1", 42)
        KotlinMetadataReader.read(listOf(garbage), null) shouldBe null
    }

    @Test
    fun `a metadata annotation with no values degrades to null`() {
        val empty = AnnotationNode("Lkotlin/Metadata;")
        KotlinMetadataReader.read(listOf(empty), null) shouldBe null
    }
}
