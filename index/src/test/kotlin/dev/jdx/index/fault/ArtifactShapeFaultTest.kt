package dev.jdx.index.fault

import dev.jdx.core.model.WarningCode
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.artifact.SourcesPair
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.service.JdxService
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Malformed-artifact faults (TESTING.md §7): empty jars, resources-only jars,
 * directory entries wearing a `.class` name, corrupt and future-version
 * classes, unreadable/missing/deleted artifacts, duplicate FQNs, conflicting
 * multi-release variants, `-g:none` classes, and mismatched sources jars.
 *
 * Every test asserts the fault law: a documented D-015 exit code plus output
 * naming the problem, with no stack trace on any channel. Success-shaped
 * faults (duplicates, variants, missing debug info) assert the degraded answer
 * still arrives — exit 0 with the honest warning or the synthesised fallback.
 *
 * Two §7 bullets are *not* here: decompiler timeout/crash has no decompiler to
 * fault yet (the engines land in T-026/T-027, which extend this suite), and
 * `SOURCES_VERSION_MISMATCH` detection is T-028 — the sources tests below pin
 * the degrade-to-bytecode contract that T-028 preserves (mismatched sources
 * still answer structural queries from bytecode).
 *
 * Tagged `tier2` (docs/TESTING.md §2): crafts jars on disk and reads them back.
 */
@Tag("tier2")
class ArtifactShapeFaultTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    // -- jars with nothing queryable ------------------------------------------------

    @Test
    fun `an empty jar exits 1 naming the query`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.craftJar(temp.resolve("empty.jar"), emptyMap())
        val rendered = FaultSupport.checkShow("com.acme.Missing", FaultSupport.rootsOf(jar), 1, "com.acme.Missing")
        rendered.json shouldContain "\"code\":1"
    }

    @Test
    fun `a resources-only jar exits 1 naming the query`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("resources.jar"),
            mapOf(
                "README.txt" to "nothing to see here".toByteArray(),
                "data/config.xml" to "<config/>".toByteArray(),
            ),
        )
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe emptyList()
        }
        val rendered = FaultSupport.checkShow("com.acme.Missing", FaultSupport.rootsOf(jar), 1, "com.acme.Missing")
        rendered.json shouldContain "\"code\":1"
    }

    @Test
    fun `a directory named X-dot-class is not a class`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("dir-as-class.jar"),
            mapOf("com/acme/X.class/" to ByteArray(0)),
        )
        ArtifactLoader.openJar(jar).use { root ->
            // The directory entry never becomes a servable class path.
            root.classEntryPaths() shouldBe emptyList()
        }
        FaultSupport.checkShow("com.acme.X", FaultSupport.rootsOf(jar), 1, "com.acme.X")
    }

    @Test
    fun `a jar holding only module-info exits 1 naming the query`(@TempDir temp: Path) {
        val real = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Generics.class")
        val jar = ArtifactTestJars.craftJar(temp.resolve("modular.jar"), mapOf("module-info.class" to real))
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe emptyList()
        }
        FaultSupport.checkShow("com.acme.Missing", FaultSupport.rootsOf(jar), 1, "com.acme.Missing")
    }

    // -- corrupt and future-version classes ------------------------------------------

    @Test
    fun `a corrupt target class exits 5 naming the class`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("broken.jar"),
            mapOf("com/example/Broken.class" to "this is not a class file".toByteArray()),
        )
        val roots = FaultSupport.rootsOf(jar)
        val show = FaultSupport.checkShow("com.example.Broken", roots, 5, "com.example.Broken")
        show.json shouldContain "\"code\":5"
        val members = FaultSupport.checkMembers("com.example.Broken", roots, 5, "com.example.Broken")
        members.json shouldContain "\"code\":5"
    }

    @Test
    fun `a corrupt neighbour does not fail the honest class`(@TempDir temp: Path) {
        val goodEntry = "dev/jdx/fixtures/Generics.class"
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("half-broken.jar"),
            mapOf(
                goodEntry to ArtifactTestJars.fixtureClassBytes(binaryJar, goodEntry),
                "com/example/Broken.class" to "this is not a class file".toByteArray(),
            ),
        )
        // Reads are lazy: the corrupt entry is never parsed for this query, so the
        // honest class answers exit 0. (Indexing the same jar warns CORRUPT_CLASS —
        // see ArtifactIndexerTest — the query path simply never touches it.)
        val roots = FaultSupport.rootsOf(jar)
        FaultSupport.checkShow("dev.jdx.fixtures.Generics", roots, 0, "dev.jdx.fixtures.Generics")
    }

    @Test
    fun `a future-version class exits 5 naming the class`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val future = ArtifactTestJars.fixtureClassBytes(binaryJar, entry).copyOf()
        // Class-file major lives at bytes 6-7; 999 is newer than any running JDK.
        future[6] = 0x03
        future[7] = 0xE7.toByte()
        val jar = temp.resolve("future.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to future))
        // The reader names the version first (unit level) …
        val read = AsmClassReader.read(future, "Generics.class")
        val unsupported = read.shouldBeInstanceOf<ClassReadResult.UnsupportedVersion>()
        unsupported.warning.code shouldBe WarningCode.UNSUPPORTED_CLASS_VERSION
        // … and the query degrades to exit 5 naming the class, never exit 6.
        val rendered = FaultSupport.checkShow("dev.jdx.fixtures.Generics", FaultSupport.rootsOf(jar), 5, "dev.jdx.fixtures.Generics")
        rendered.json shouldContain "\"code\":5"
    }

    @Test
    fun `a JFR-style dollar-dollar target degrades to a usage error naming the ref`(@TempDir temp: Path) {
        // T-065 query path: `A$B$$C` names fail in the ref parser (exit 3)
        // before any bytecode is read — the reader/indexer UNNAMEABLE_CLASS
        // path owns degradation, the query owns the honest usage error.
        // FaultSupport.captureStreams asserts no stack trace on any channel.
        val jar = ArtifactTestJars.craftJar(temp.resolve("empty.jar"), emptyMap())
        FaultSupport.checkShow("com.example.Outer\$JB\$\$Assertion", FaultSupport.rootsOf(jar), 3, "com.example.Outer")
    }

    // -- unreadable, missing, deleted, and unmatched artifacts -------------------------

    @Test
    fun `a missing jar exits 5 naming the path`() {
        val missing = "/no/such/jdx-artifact.jar"
        val rendered = FaultSupport.checkShow(
            "com.acme.Anything",
            JdxService.RootsSpec(jarSpecs = listOf(missing), includeJdk = false),
            5,
            missing,
        )
        rendered.json shouldContain "\"code\":5"
    }

    @Test
    fun `a jar deleted before the query exits 5 naming the jar`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("here-then-gone.jar"),
            mapOf(entry to ArtifactTestJars.fixtureClassBytes(binaryJar, entry)),
        )
        Files.delete(jar)
        val rendered = FaultSupport.checkShow(
            "dev.jdx.fixtures.Generics",
            FaultSupport.rootsOf(jar),
            5,
            "here-then-gone.jar",
        )
        rendered.json shouldContain "\"code\":5"
    }

    @Test
    fun `a glob matching nothing exits 5 naming the spec`(@TempDir temp: Path) {
        val spec = temp.resolve("nothing-matches-*.jar").toString()
        val rendered = FaultSupport.checkShow(
            "com.acme.Anything",
            JdxService.RootsSpec(jarSpecs = listOf(spec), includeJdk = false),
            5,
            spec,
        )
        rendered.json shouldContain "\"code\":5"
    }

    // -- duplicate FQNs and conflicting multi-release variants --------------------------

    @Test
    fun `a duplicated FQN exits 0 warning DUPLICATE_FQN naming both jars`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        val first = ArtifactTestJars.craftJar(temp.resolve("shade-a.jar"), mapOf(entry to bytes))
        val second = ArtifactTestJars.craftJar(temp.resolve("shade-b.jar"), mapOf(entry to bytes))
        val roots = FaultSupport.rootsOf(first, second)
        val rendered = FaultSupport.checkMembers(
            "dev.jdx.fixtures.Generics",
            roots,
            0,
            "DUPLICATE_FQN",
            "shade-a.jar",
            "shade-b.jar",
        )
        // The structured warning carries the code; the first jar wins (classpath order).
        val outcome = FaultSupport.captureStreams { JdxService.members("dev.jdx.fixtures.Generics", roots) }.value
        val listing = (outcome as JdxService.ServiceOutcome.MemberList).listing
        listing.warnings.map { it.code }.contains(WarningCode.DUPLICATE_FQN).shouldBeTrue()
        (listing.warnings.single { it.code == WarningCode.DUPLICATE_FQN }.message.contains("shade-a.jar")).shouldBeTrue()
    }

    @Test
    fun `a conflicting multi-release variant serves the newest applicable bytes`(@TempDir temp: Path) {
        val base = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Generics.class")
        val variant = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Nesting.class")
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("mr-conflict.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(multiRelease = true),
                "com/acme/Thing.class" to base,
                "META-INF/versions/9/com/acme/Thing.class" to variant,
            ),
        )
        val roots = FaultSupport.rootsOf(jar)
        // The versioned bytes win over the base bytes: the card carries the
        // variant's true name (Nesting), not the base's (Generics) — plus the
        // MULTI_RELEASE_VARIANT warning saying why.
        val rendered = FaultSupport.checkShow(
            "com.acme.Thing",
            roots,
            0,
            "MULTI_RELEASE_VARIANT",
            "dev.jdx.fixtures.Nesting",
        )
        val outcome = FaultSupport.captureStreams { JdxService.members("com.acme.Thing", roots) }.value
        val listing = (outcome as JdxService.ServiceOutcome.MemberList).listing
        listing.warnings.map { it.code }.contains(WarningCode.MULTI_RELEASE_VARIANT).shouldBeTrue()
        rendered.json shouldContain "MULTI_RELEASE_VARIANT"
    }

    // -- classes without debug info ------------------------------------------------------

    @Test
    fun `a minus-g-none class exits 0 with synthesised arg names`() {
        // NoDebug is compiled -g:none (testfixtures/build.gradle.kts): no parameter
        // names, no local variable table. The reader reports unknown names …
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/NoDebug.class")
        val info = (AsmClassReader.read(bytes, "NoDebug.class") as ClassReadResult.Ok).info
        val add = info.methods.single { it.name == "add" }
        add.parameterNames shouldBe listOf(null, null)
        // … and the query falls back to argN instead of failing, on both paths.
        val roots = JdxService.RootsSpec(
            jarSpecs = listOf(ArtifactTestJars.binaryJar().toString()),
            includeJdk = false,
        )
        val rendered = FaultSupport.checkMembers("dev.jdx.fixtures.NoDebug", roots, 0, "arg0", "arg1")
        rendered.text shouldContain "public int add(int arg0, int arg1)"
    }

    // -- mismatched and foreign sources jars (T-028 owns the warning) ---------------------

    @Test
    fun `a mismatched sources jar still answers from bytecode`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val jar = temp.resolve("mismatched.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to ArtifactTestJars.fixtureClassBytes(binaryJar, entry)))
        // Same stem, unrelated contents: pairing is by name and never inspects the
        // sources, so this pairs — and the query answers from bytecode regardless.
        // T-028 adds the SOURCES_VERSION_MISMATCH warning on sources-backed
        // answers; structural queries like `show` stay warning-free by design.
        // This test pins the degrade-to-bytecode half of that contract.
        val sources = temp.resolve("mismatched-sources.jar")
        ArtifactTestJars.craftJar(sources, mapOf("notes.txt" to "unrelated".toByteArray()))
        ArtifactLoader.openJar(jar).use { root ->
            (root.sourcesPair is SourcesPair.External).shouldBeTrue()
        }
        FaultSupport.checkShow("dev.jdx.fixtures.Generics", FaultSupport.rootsOf(jar), 0, "dev.jdx.fixtures.Generics")
    }

    @Test
    fun `a foreign sources jar never pairs and never fails`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val jar = temp.resolve("lonely.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to ArtifactTestJars.fixtureClassBytes(binaryJar, entry)))
        // A different stem must never pair: a flat lib/ dir full of unrelated jars
        // stays silent instead of cross-wiring sources.
        val foreign = temp.resolve("other-sources.jar")
        ArtifactTestJars.craftJar(foreign, mapOf("notes.txt" to "unrelated".toByteArray()))
        ArtifactLoader.openJar(jar).use { root ->
            root.sourcesPair shouldBe SourcesPair.Absent
        }
        FaultSupport.checkShow("dev.jdx.fixtures.Generics", FaultSupport.rootsOf(jar), 0, "dev.jdx.fixtures.Generics")
    }
}
