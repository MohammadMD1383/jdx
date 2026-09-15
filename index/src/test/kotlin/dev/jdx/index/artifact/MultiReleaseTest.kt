package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 unit tests for [MultiRelease.resolve]: variant selection is pure name logic, so
 * the whole truth table lives here without touching a jar.
 */
class MultiReleaseTest {

    private val base = setOf("com/foo/Bar.class", "com/foo/Util.class")

    @Test
    fun `without the manifest flag versioned trees are ignored`() {
        val entries = base + "META-INF/versions/21/com/foo/Bar.class"
        MultiRelease.resolve(entries, multiRelease = false, runtimeVersion = 26) shouldBe
            mapOf("com/foo/Bar.class" to "com/foo/Bar.class", "com/foo/Util.class" to "com/foo/Util.class")
    }

    @Test
    fun `highest applicable variant wins`() {
        val entries = base +
            "META-INF/versions/9/com/foo/Bar.class" +
            "META-INF/versions/17/com/foo/Bar.class" +
            "META-INF/versions/21/com/foo/Bar.class"
        val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = 21)
        resolved["com/foo/Bar.class"] shouldBe "META-INF/versions/21/com/foo/Bar.class"
        resolved["com/foo/Util.class"] shouldBe "com/foo/Util.class"
    }

    @Test
    fun `variants newer than the runtime are skipped`() {
        val entries = setOf(
            "com/foo/Bar.class",
            "META-INF/versions/99/com/foo/Bar.class",
            "META-INF/versions/9/com/foo/Bar.class",
        )
        val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = 21)
        resolved["com/foo/Bar.class"] shouldBe "META-INF/versions/9/com/foo/Bar.class"
    }

    @Test
    fun `below-9 versions are never valid releases`() {
        val entries = setOf(
            "com/foo/Bar.class",
            "META-INF/versions/8/com/foo/Bar.class",
        )
        val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = 21)
        resolved["com/foo/Bar.class"] shouldBe "com/foo/Bar.class"
    }

    @Test
    fun `variant-only classes are servable under their base path`() {
        val entries = setOf("META-INF/versions/17/com/foo/New.class")
        val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = 21)
        resolved["com/foo/New.class"] shouldBe "META-INF/versions/17/com/foo/New.class"
    }

    @Test
    fun `non-class versioned resources never leak into resolution`() {
        val entries = setOf(
            "com/foo/Bar.class",
            "META-INF/versions/21/com/foo/notes.txt",
            "META-INF/MANIFEST.MF",
        )
        val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = 26)
        resolved shouldBe mapOf("com/foo/Bar.class" to "com/foo/Bar.class")
    }

    @Test
    fun `versioned selection is reported exactly when a variant won`() {
        val plain = mapOf("A.class" to "A.class")
        MultiRelease.hasVersionedSelection(plain) shouldBe false
        val versioned = mapOf("A.class" to "META-INF/versions/17/A.class")
        MultiRelease.hasVersionedSelection(versioned) shouldBe true
    }
}
