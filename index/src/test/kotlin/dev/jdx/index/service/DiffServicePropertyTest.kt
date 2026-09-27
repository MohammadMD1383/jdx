package dev.jdx.index.service

import dev.jdx.index.service.JdxService.ArtifactSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes

/**
 * The generating families behind `diff` (issue #23, tier 2).
 *
 * Examples cannot cover the combinations that matter here, so this suite grows
 * its own inputs. Three relations, none of which a per-command example would
 * have thought to assert:
 *
 * - **Determinism** (AGENTS.md §2.5): the same pair of artifacts answers
 *   byte-identically, in text and in `--json` alike.
 * - **Label invariance**: the jar *file names* contribute labels and nothing
 *   else. Renaming both sides must not move one finding, which is what proves the
 *   report describes the classes rather than the paths they were read from.
 * - **Totality under hostile input** (TESTING.md §7): a spec nobody can satisfy
 *   still answers with one exit-coded line, on either side, and never a throw.
 *
 * Every generated spec is anchored under a directory (or a group id) that cannot
 * exist on any machine, so the suite stays hermetic: a hostile string that
 * happened to name a real file would turn a totality check into a slow walk of
 * somebody's home directory.
 */
@Tag("tier2")
class DiffServicePropertyTest {

    // -- generated corpora ---------------------------------------------------------

    private val descriptors = listOf(
        "()V",
        "()I",
        "(I)V",
        "(Ljava/lang/String;)V",
        "(Ljava/lang/String;)Ljava/lang/String;",
    )

    private val accessFlags = listOf(
        Opcodes.ACC_PUBLIC,
        Opcodes.ACC_PRIVATE,
        Opcodes.ACC_PROTECTED,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
    )

    /**
     * One crafted member, derived from a single index so names are unique inside
     * a type: two members sharing a key would make the snapshot's own map drop
     * one, and the test would be measuring that instead of the service.
     */
    private fun arbMember(): Arb<DiffMember> = Arb.int(0..14).map { index ->
        DiffMember(
            name = "m$index",
            descriptor = descriptors[index % descriptors.size],
            access = accessFlags[index % accessFlags.size],
        )
    }

    private fun arbType(): Arb<DiffType> = Arb.bind(
        Arb.int(0..2),
        Arb.list(arbMember(), 0..4),
        Arb.list(arbMember(), 0..2),
    ) { index, methods, fields ->
        DiffType("d.Gen$index", methods, fields)
    }

    private fun arbCorpus(): Arb<List<DiffType>> = Arb.list(arbType(), 0..3)

    /**
     * Hostile spec characters. `.` and `~` are excluded on purpose: `.` names the
     * test's own working directory and `~` expands to the real home, and either
     * would turn a hostile string into a legitimate (and enormous) artifact. The
     * glob characters are excluded too, so no generated spec triggers a directory
     * walk; the glob branch is pinned by hand in `DiffServiceTest`.
     */
    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', ' ', ':', ',', '#', '(', ')', '-', '+', '=',
        'a', 'Z', '0', '9', '!', '@', 'é', ' ',
    )

    private fun arbHostileBody(): Arb<String> =
        Arb.list(Arb.of(hostileChars), 0..16).map { chars -> chars.joinToString("") }

    /** Either a path under a missing directory, or a coordinate under a missing group. */
    private fun arbHostileSpec(): Arb<String> = Arb.bind(
        Arb.int(0..1),
        arbHostileBody(),
    ) { shape, body ->
        if (shape == 0) "$ABSENT_GROUP:$body:$ABSENT_VERSION" else "$ABSENT_DIR/$body"
    }

    private fun spec(jar: Path): ArtifactSpec = ArtifactSpec(spec = jar.toString())

    private fun reportOf(outcome: ServiceOutcome): ServiceOutcome.Diff {
        (outcome is ServiceOutcome.Diff) shouldBe true
        return outcome as ServiceOutcome.Diff
    }

    // -- the families -----------------------------------------------------------------

    @Test
    fun `the same pair of artifacts answers byte-identically every time`(@TempDir temp: Path): Unit =
        runBlocking {
            checkAll(20, arbCorpus(), arbCorpus()) { old, new ->
                val oldJar = diffJar(temp.resolve("old.jar"), old)
                val newJar = diffJar(temp.resolve("new.jar"), new)

                val first = JdxService.diff(spec(oldJar), spec(newJar))
                val second = JdxService.diff(spec(oldJar), spec(newJar))

                first.exitCode shouldBe second.exitCode
                first.renderText(false) shouldBe second.renderText(false)
                first.toJson("diff") shouldBe second.toJson("diff")
            }
        }

    @Test
    fun `renaming the jars changes the labels and nothing else`(@TempDir temp: Path): Unit = runBlocking {
        checkAll(20, arbCorpus(), arbCorpus()) { old, new ->
            val first = reportOf(
                JdxService.diff(
                    spec(diffJar(temp.resolve("old-a.jar"), old)),
                    spec(diffJar(temp.resolve("new-a.jar"), new)),
                ),
            ).report
            val second = reportOf(
                JdxService.diff(
                    spec(diffJar(temp.resolve("old-b.jar"), old)),
                    spec(diffJar(temp.resolve("new-b.jar"), new)),
                ),
            ).report

            // Same classes in, same differences out: a finding names a class and
            // its member, never the file it was read from.
            first.findings.map { it.rule to it.ref } shouldBe second.findings.map { it.rule to it.ref }
            first.counts shouldBe second.counts
            first.oldArtifact shouldBe "old-a.jar"
            second.oldArtifact shouldBe "old-b.jar"
        }
    }

    @Test
    fun `a hostile old spec answers one exit-coded line and never throws`(@TempDir temp: Path): Unit =
        runBlocking {
            val newJar = diffJar(temp.resolve("new.jar"), listOf(DiffType("d.Gen0")))
            checkAll(60, arbHostileSpec()) { hostile ->
                assertTotality(JdxService.diff(ArtifactSpec(spec = hostile), spec(newJar)))
            }
        }

    @Test
    fun `a hostile new spec answers one exit-coded line and never throws`(@TempDir temp: Path): Unit =
        runBlocking {
            val oldJar = diffJar(temp.resolve("old.jar"), listOf(DiffType("d.Gen0")))
            checkAll(60, arbHostileSpec()) { hostile ->
                assertTotality(JdxService.diff(spec(oldJar), ArtifactSpec(spec = hostile)))
            }
        }

    /**
     * A spec that cannot name an artifact fails before any comparison, so the
     * answer is an error envelope: one line, an exit code in the usage/artifact
     * range, a message with the standard prefix, and never a trace (D-015,
     * TESTING.md §7).
     */
    private fun assertTotality(outcome: ServiceOutcome) {
        (outcome.exitCode in 3..5) shouldBe true
        val line = outcome.toJson("diff")
        line.contains("\n") shouldBe false
        line.contains("\r") shouldBe false
        line.contains("\"ok\":false") shouldBe true
        // The two shapes a bad side can have: a spec that names nothing (usage)
        // and a spec that names something unreadable (artifact). One of the two
        // prefixes, always — never a bare refusal and never a trace.
        (line.contains("usage error:") || line.contains("artifact read error:")) shouldBe true
    }

    private companion object {
        /** A directory no machine has; see the class KDoc. */
        const val ABSENT_DIR: String = "jdx-diff-absent-9c1f"

        /** A group id no repository has; keeps a generated coordinate unresolvable. */
        const val ABSENT_GROUP: String = "jdx.diff.absent"

        const val ABSENT_VERSION: String = "9c1f-jdx-diff"
    }
}
