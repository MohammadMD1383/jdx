package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Tier-1 tests for lockfile coordinate parsing (T-016, TESTING.md §4 — the task's
 * *generating* family). Pure string work: no disk, no subprocesses.
 */
class ProjectDiscoveryParseTest {

    @Test
    fun `gradle lockfile lines yield coordinates`() {
        val lockfile = """
            # This is a Gradle generated file for dependency locking.
            # Manual edits can break the build and are not advised.
            empty=
            com.google.code.gson:gson:2.10.1=runtimeClasspath
            org.ow2.asm:asm:9.6=annotationProcessor
        """.trimIndent()
        ProjectDiscovery.parseLockCoordinates(lockfile) shouldBe listOf(
            ProjectDiscovery.MavenCoordinate("com.google.code.gson", "gson", "2.10.1"),
            ProjectDiscovery.MavenCoordinate("org.ow2.asm", "asm", "9.6"),
        )
    }

    @Test
    fun `quoted build-script literals yield coordinates`() {
        val script = """
            dependencies {
                implementation("com.google.code.gson:gson:2.10.1")
                testImplementation('junit:junit:4.13.2')
            }
        """.trimIndent()
        ProjectDiscovery.parseLockCoordinates(script) shouldBe listOf(
            ProjectDiscovery.MavenCoordinate("com.google.code.gson", "gson", "2.10.1"),
            ProjectDiscovery.MavenCoordinate("junit", "junit", "4.13.2"),
        )
    }

    @Test
    fun `coordinates sort and deduplicate`() {
        val text = "b:a:2\na:b:1\nb:a:2\nb:a:10\n"
        ProjectDiscovery.parseLockCoordinates(text) shouldBe listOf(
            ProjectDiscovery.MavenCoordinate("a", "b", "1"),
            ProjectDiscovery.MavenCoordinate("b", "a", "10"),
            ProjectDiscovery.MavenCoordinate("b", "a", "2"),
        )
    }

    @Test
    fun `garbage yields no coordinates, never a throw`() {
        ProjectDiscovery.parseLockCoordinates("") shouldBe emptyList()
        ProjectDiscovery.parseLockCoordinates("no:colons\n::: \n:leading:1\ntrailing:1:\n") shouldBe emptyList()
        ProjectDiscovery.parseLockCoordinates("a:.:1\n.:b:1\na:b:..\n") shouldBe emptyList()
    }

    @Test
    fun `two-part plugin ids are not coordinates`() {
        // `java-library` / `org.jetbrains.kotlin.jvm` look coordinate-ish but lack versions.
        ProjectDiscovery.parseLockCoordinates("plugins { id(\"java-library\") }\n") shouldBe emptyList()
    }

    @Test
    fun `parsing never throws and is sorted unique deterministic`() = runBlocking<Unit> {
        checkAll(1_000, Arb.string(0..48)) { text ->
            val first = ProjectDiscovery.parseLockCoordinates(text)
            val second = ProjectDiscovery.parseLockCoordinates(text)
            first shouldBe second
            first shouldBe first.distinct().sortedWith(compareBy({ it.group }, { it.name }, { it.version }))
            first.forEach { coordinate ->
                (coordinate.group.isNotEmpty() && coordinate.name.isNotEmpty() && coordinate.version.isNotEmpty()) shouldBe true
            }
        }
    }
}
