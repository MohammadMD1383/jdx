package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative selection for [MultiRelease.resolve] (issue #29).
 *
 * The hand-written table in [MultiReleaseTest] covers the shapes the author
 * thought of. This one invents release numbers and demands the winner is
 * always the highest applicable variant, and that [MultiRelease.releaseOf]
 * names that variant.
 */
class MultiReleasePropertyTest {

    @Test
    fun `the winner is the highest release at or below the selection`() {
        runBlocking {
            checkAll(80, Arb.int(8..40), Arb.list(Arb.int(1..40), 0..6)) { selected, rawReleases ->
                val releases = rawReleases.toSet()
                val entries = buildSet {
                    add(BASE)
                    releases.forEach { add("META-INF/versions/$it/$BASE") }
                }
                val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = selected)
                val applicable = releases.filter { it in 9..selected }
                val winner = resolved[BASE]
                if (applicable.isEmpty()) {
                    winner shouldBe BASE
                    MultiRelease.releaseOf(winner!!) shouldBe null
                } else {
                    val best = applicable.max()
                    winner shouldBe "META-INF/versions/$best/$BASE"
                    MultiRelease.releaseOf(winner!!) shouldBe best
                }
                // A second call is the same map. Selection is a pure function.
                MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = selected) shouldBe resolved
            }
        }
    }

    @Test
    fun `a version-only class above the selection is absent and one below is served`() {
        runBlocking {
            checkAll(40, Arb.int(8..30)) { selected ->
                val entries = setOf(
                    "META-INF/versions/${selected + 5}/com/foo/Future.class",
                    "META-INF/versions/11/com/foo/Since.class",
                )
                val resolved = MultiRelease.resolve(entries, multiRelease = true, runtimeVersion = selected)
                resolved.containsKey("com/foo/Future.class") shouldBe false
                if (selected >= 11) {
                    resolved["com/foo/Since.class"] shouldBe "META-INF/versions/11/com/foo/Since.class"
                } else {
                    resolved.containsKey("com/foo/Since.class") shouldBe false
                }
            }
        }
    }

    private companion object {
        const val BASE: String = "com/foo/Bar.class"
    }
}
