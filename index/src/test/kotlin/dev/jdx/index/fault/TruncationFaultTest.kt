package dev.jdx.index.fault

import dev.jdx.index.artifact.ArtifactTestJars
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Truncated class files (TESTING.md §7, first bullet).
 *
 * The cuts are *generated* at every 10 % boundary of the real fixture bytes —
 * not hand-picked — so this test keeps failing on shapes nobody imagined as
 * long as truncation can break parsing. The invariant is the fault law: every
 * cut answers exit 5 naming the class (never a throw, never exit 6, never a
 * trace), and the uncut control answers exit 0.
 *
 * Tagged `tier2` (docs/TESTING.md §2): crafts jars on disk and reads them back.
 */
@Tag("tier2")
class TruncationFaultTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    private val entry: String = "dev/jdx/fixtures/Generics.class"

    private val binary: String = "dev.jdx.fixtures.Generics"

    @Test
    fun `a class cut at every 10 percent boundary exits 5 naming the class`(@TempDir temp: Path) {
        val full = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        for (percent in 10..90 step 10) {
            val size = (full.size * percent) / 100
            val jar = temp.resolve("cut-$percent.jar")
            ArtifactTestJars.craftJar(jar, mapOf(entry to full.copyOf(size)))
            val roots = FaultSupport.rootsOf(jar)
            // Both read paths fault identically: show parses the target, members
            // parses it plus its supertypes — either way the target is unreadable.
            val show = FaultSupport.checkShow(binary, roots, 5, binary)
            show.json shouldContain "\"code\":5"
            val members = FaultSupport.checkMembers(binary, roots, 5, binary)
            members.json shouldContain "\"code\":5"
        }
    }

    @Test
    fun `the uncut control exits 0 with no warnings`(@TempDir temp: Path) {
        val full = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        val jar = temp.resolve("whole.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to full))
        val roots = FaultSupport.rootsOf(jar)
        // The control proves the cuts above fail because of the truncation, not
        // because a single-class jar is unqueryable on its own.
        FaultSupport.checkShow(binary, roots, 0, binary)
        FaultSupport.checkMembers(binary, roots, 0, binary)
    }

    @Test
    fun `an empty class file exits 5 naming the class`(@TempDir temp: Path) {
        val jar = temp.resolve("empty-class.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to ByteArray(0)))
        val roots = FaultSupport.rootsOf(jar)
        FaultSupport.checkShow(binary, roots, 5, binary)
    }
}
