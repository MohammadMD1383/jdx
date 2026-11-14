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

    @Test
    fun `absent d2 is empty not corrupt for multi-file facades`() {
        // Issue #12: MultiFileClassFacade annotations often omit `d2` (annotation
        // default `{}`); requiring it made every multifile umbrella look non-Kotlin.
        // A mistyped d2 is still corrupt:
        val mistyped = metadataNode(
            "k", 2,
            "mv", listOf(2, 2, 0),
            "d1", listOf("x"),
            "d2", 42,
        )
        KotlinMetadataReader.read(listOf(mistyped), null) shouldBe null
        // Omitted d2 reaches readLenient (may still be null on hostile d1, but
        // must not fail the absent-d2 gate). Real multifile decode is in
        // KotlinClassesTest.
        val omitted = metadataNode(
            "k", 2,
            "mv", listOf(2, 2, 0),
            "d1", listOf("x"),
            "xi", 0,
        )
        // Hostile d1 → null from readLenient, not from the d2 gate:
        KotlinMetadataReader.read(listOf(omitted), null) shouldBe null
    }
}
