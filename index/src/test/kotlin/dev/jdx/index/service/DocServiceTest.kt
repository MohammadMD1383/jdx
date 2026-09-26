package dev.jdx.index.service

import dev.jdx.index.service.JdxService.DocOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `doc` against real artifacts (T-025, tier 2): the fixture corpus
 * binary jar with its sibling `-sources.jar` plus a crafted three-level
 * hierarchy ([buildDocCaseJars]) for the inheritance paths fixtures cannot pin
 * (fixture members are deliberately undocumented).
 *
 * Rendering itself is pinned by `DocGoldenTest` in the render package; here the
 * assertions are structural — resolution, inheritance, exit codes,
 * degradation, determinism and text⊆JSON.
 */
@Tag("tier2")
class DocServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun caseRoots(jars: DocCaseJars): RootsSpec =
        RootsSpec(jarSpecs = listOf(jars.binary.toString()), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    // -- fixture types ---------------------------------------------------------

    @Test
    fun `type doc renders with provenance`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "dev.jdx.fixtures.Generics"
        text shouldContain "break naive descriptor-only readers"
        text shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:"
        text.lines().last() shouldBe "next: jdx show dev.jdx.fixtures.Generics"
    }

    @Test
    fun `undocumented fixture member exits 1 naming the query`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no javadoc comment for 'dev.jdx.fixtures.Generics#identity(U)'"
    }

    @Test
    fun `under-specified overloads exit 2 with candidates`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.CovariantOverrides\$Child#copy", fixtureRoots())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
        textOf(outcome) shouldContain "hint: re-run with one of the refs above"
    }

    @Test
    fun `unknown member exits 1 with did-you-mean`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics#identityy", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found:"
        textOf(outcome) shouldContain "did you mean:"
        textOf(outcome) shouldContain "identity"
    }

    @Test
    fun `unknown type exits 1 with did-you-mean`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.Generic", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found:"
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics"
    }

    // -- crafted hierarchy -----------------------------------------------------

    @Test
    fun `direct member doc renders tags as a tidy block`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Base#greet(java.lang.String)", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "doc.Base#greet(java.lang.String)"
        text shouldContain "Greets warmly."
        text shouldContain "Uses Widget and one-arg greet."
        text shouldContain "@param name who to greet"
        text shouldContain "@return the greeting"
        text shouldNotContain "{@code"
        text shouldNotContain "inherited from"
    }

    @Test
    fun `undocumented override inherits the supertype doc labelled`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Child#greet(java.lang.String)", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "doc.Child#greet(java.lang.String)"
        text shouldContain "Greets warmly."
        text shouldContain "(inherited from doc.Base)"
    }

    @Test
    fun `inherit-doc substitutes the supertype paragraph transitively`(@TempDir tempDir: Path) {
        // GrandChild documents greet with `{@inheritDoc}` only: the walk still
        // passes through the undocumented Child to Base (transitivity), and
        // the tag is replaced with Base's first paragraph — a direct doc, so
        // no inheritance label is attached.
        val outcome = JdxService.doc(
            "doc.GrandChild#greet(java.lang.String)",
            caseRoots(buildDocCaseJars(tempDir)),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "Greets warmly."
        text shouldContain "With grandchild emphasis."
        text shouldNotContain "{@inheritDoc}"
        text shouldNotContain "inherited from"
    }

    @Test
    fun `no-inherited disables the fallback`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc(
            "doc.Child#greet(java.lang.String)",
            caseRoots(buildDocCaseJars(tempDir)),
            DocOptions(inherit = false),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no javadoc comment"
        textOf(outcome) shouldNotContain "inherited from"
    }

    @Test
    fun `fields do not inherit docs`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Child#name", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no javadoc comment for 'doc.Child#name'"
        textOf(outcome) shouldNotContain "inherited from"
    }

    @Test
    fun `direct field doc answers`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Base#name", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "The shared name."
    }

    @Test
    fun `types do not inherit docs`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.GrandChild", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no javadoc comment for 'doc.GrandChild'"
    }

    @Test
    fun `raw serves the verbatim comment`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc(
            "doc.Base#greet(java.lang.String)",
            caseRoots(buildDocCaseJars(tempDir)),
            DocOptions(raw = true),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "{@code Widget}"
        textOf(outcome) shouldContain "@param name who to greet"
    }

    @Test
    fun `max-lines truncates with a footer`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc(
            "doc.Base#greet(java.lang.String)",
            caseRoots(buildDocCaseJars(tempDir)),
            DocOptions(maxLines = 2),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Greets warmly."
        textOf(outcome) shouldNotContain "@return the greeting"
        textOf(outcome) shouldContain "2 of 5 lines shown (--max-lines 5 to see more)"
    }

    @Test
    fun `constructor doc answers`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Base#<init>()", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Builds a Base."
    }

    @Test
    fun `enum entry doc answers`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc("doc.Traffic#RED", caseRoots(buildDocCaseJars(tempDir)))
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Stop now."
    }

    @Test
    fun `short names matching two artifacts exit 2`(@TempDir tempDir: Path) {
        // Two different paths, one simple name: shading stays per-provider
        // (T-068 dedupes identical paths only), so the short name is ambiguous.
        val jars = buildDocCaseJars(tempDir)
        val baseBytes = ZipFile(jars.binary.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("doc/Base.class")).readBytes()
        }
        val shaded = tempDir.resolve("other.jar")
        writeDocJar(shaded, mapOf("other/Base.class" to baseBytes))
        val roots = RootsSpec(jarSpecs = listOf(jars.binary.toString(), shaded.toString()), includeJdk = false)
        val outcome = JdxService.doc("Base", roots)
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
    }

    @Test
    fun `duplicate providers warn once and answer`(@TempDir tempDir: Path) {
        // Same jar content under two paths: first-provider-wins with a
        // DUPLICATE_FQN warning (classpath shadowing, T-015).
        val jars = buildDocCaseJars(tempDir)
        val first = tempDir.resolve("a.jar")
        val second = tempDir.resolve("b.jar")
        Files.copy(jars.binary, first)
        Files.copy(jars.binary, second)
        Files.copy(jars.sources, tempDir.resolve("a-sources.jar"))
        Files.copy(jars.sources, tempDir.resolve("b-sources.jar"))
        val roots = RootsSpec(jarSpecs = listOf(first.toString(), second.toString()), includeJdk = false)
        val outcome = JdxService.doc("doc.Base", roots)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Base widgets: the documented supertype."
        textOf(outcome) shouldContain "DUPLICATE_FQN"
    }

    @Test
    fun `specified bridge overloads exit 2`() {
        // `copy()` names both the override and its bridge in bytecode.
        val outcome = JdxService.doc("dev.jdx.fixtures.CovariantOverrides\$Child#copy()", fixtureRoots())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
    }

    @Test
    fun `return-qualified bridge resolves past ambiguity`() {
        // The `:return` qualifier picks one bridge sibling: resolution proceeds
        // (both share one undocumented source declaration) to exit 1, not 2.
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.CovariantOverrides\$Child#copy():" +
                "dev.jdx.fixtures.CovariantOverrides\$Child",
            fixtureRoots(),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no javadoc comment"
    }

    @Test
    fun `member on an unpaired binary degrades naming T-026`(@TempDir tempDir: Path) {
        val binary = tempDir.resolve("bare.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeDocJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics#identity(U)", roots)
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "T-026"
    }

    @Test
    fun `member on kotlin-only sources without a sidecar names the install hint`(@TempDir tempDir: Path) {
        // `doc` has no decompiled path, so a missing sidecar is exit 1 with
        // the `~`-relative hint (T-039) — never an absolute home path.
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeDocJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeDocJar(sources, mapOf("dev/jdx/fixtures/Generics.kt" to "fun dummy(): Int = 1\n".toByteArray()))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        // `tempDir` doubles as the Kotlin home: it holds no sidecar, so the
        // unavailable path pins hermetically even on machines with a real
        // sidecar installed.
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.Generics#identity(U)",
            roots,
            DocOptions(kotlinUserHome = tempDir),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "kotlin-compiler-embeddable-2.4.20.jar not installed under <cache-dir>/kotlin"
    }

    @Test
    fun `member whose file is missing from sources names T-026`(@TempDir tempDir: Path) {
        val jars = partialJars(tempDir, sourcesEntries = mapOf("doc/Child.java" to childSource()))
        val outcome = JdxService.doc("doc.Base#greet(java.lang.String)", caseRoots(jars))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no sources for doc.Base"
    }

    @Test
    fun `type whose file is missing from sources names T-026`(@TempDir tempDir: Path) {
        val jars = partialJars(tempDir, sourcesEntries = mapOf("doc/Child.java" to childSource()))
        val outcome = JdxService.doc("doc.Base", caseRoots(jars))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no sources for doc.Base"
    }

    @Test
    fun `truncated sources are an artifact error`(@TempDir tempDir: Path) {
        // Pair the crafted binary with a half-written sources entry: both the
        // member and the type path must exit 5 naming the file, never throw.
        val jars = buildDocCaseJars(tempDir)
        val full = ZipFile(jars.sources.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("doc/Base.java")).readBytes()
        }
        val binary = tempDir.resolve("pair.jar")
        Files.copy(jars.binary, binary)
        val sources = tempDir.resolve("pair-sources.jar")
        writeDocJar(sources, mapOf("doc/Base.java" to full.copyOf(full.size / 2)))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val member = JdxService.doc("doc.Base#greet(java.lang.String)", roots)
        member.exitCode shouldBe 5
        textOf(member) shouldContain "does not parse"
        val type = JdxService.doc("doc.Base", roots)
        type.exitCode shouldBe 5
        textOf(type) shouldContain "does not parse"
    }

    @Test
    fun `member absent from sources names a version mismatch`(@TempDir tempDir: Path) {
        val stripped = """
            package doc;
            /** Base widgets: the documented supertype. */
            public abstract class Base {
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val jars = partialJars(tempDir, sourcesEntries = mapOf("doc/Base.java" to stripped))
        val outcome = JdxService.doc("doc.Base#greet(java.lang.String)", caseRoots(jars))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `type renamed in sources names a version mismatch`(@TempDir tempDir: Path) {
        val renamed = """
            package doc;
            /** Some other widget. */
            public abstract class Renamed {
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val jars = partialJars(tempDir, sourcesEntries = mapOf("doc/Base.java" to renamed))
        val outcome = JdxService.doc("doc.Base", caseRoots(jars))
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `corrupt target class is an artifact error`(@TempDir tempDir: Path) {
        val binary = tempDir.resolve("corrupt.jar")
        writeDocJar(binary, mapOf("doc/Base.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x00)))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.doc("doc.Base", roots)
        outcome.exitCode shouldBe 5
        textOf(outcome) shouldContain "cannot be parsed"
    }

    @Test
    fun `unreadable artifact path is an artifact error`() {
        val outcome = JdxService.doc(
            "doc.Base",
            RootsSpec(jarSpecs = listOf("/nonexistent-jdx-doc-test.jar"), includeJdk = false),
        )
        outcome.exitCode shouldBe 5
    }

    @Test
    fun `coordinate prefix scopes doc to the artifact`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.doc(
            "com.example:demo:1.0/dev.jdx.fixtures.Generics",
            docRootsFor(repos, includeJdk = false),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "break naive descriptor-only readers"
    }

    @Test
    fun `unresolvable coordinate prefix is an artifact error`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.doc(
            "com.example:missing:9.9.9/dev.jdx.fixtures.Generics",
            docRootsFor(repos, includeJdk = false),
        )
        outcome.exitCode shouldBe 5
    }

    @Test
    fun `throwing coordinate resolution is an internal error`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val roots = docRootsFor(repos, includeJdk = false).copy(
            mavenResolve = { _, _ -> throw IllegalStateException("boom") },
        )
        val outcome = JdxService.doc("com.example:demo:1.0/dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 6
        textOf(outcome) shouldContain "boom"
    }

    private fun childSource(): ByteArray =
        """
        package doc;
        /** Child widgets. */
        public abstract class Child extends Base {
        }
        """.trimIndent().toByteArray(Charsets.UTF_8)

    /**
     * The crafted binary beside a custom sources jar: sources pairing follows
     * the `<stem>-sources.jar` sibling rule, so any sources content can be
     * staged (missing files, truncated entries, renamed types).
     */
    private fun partialJars(tempDir: Path, sourcesEntries: Map<String, ByteArray>): DocCaseJars {
        val full = buildDocCaseJars(tempDir)
        val binary = tempDir.resolve("partial.jar")
        Files.copy(full.binary, binary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val sources = tempDir.resolve("partial-sources.jar")
        writeDocJar(sources, sourcesEntries)
        return DocCaseJars(binary, sources)
    }

    private fun m2WithDemo(root: Path): dev.jdx.index.maven.MavenResolver.Repositories {
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        val fixture = FixtureJars.binaryJar()
        Files.copy(fixture.toPath(), versionDir.resolve("demo-1.0.jar"))
        // Stage the sources jar alongside: doc answers from paired sources,
        // and the m2 layout pairs `<artifact>-<version>-sources.jar` by stem.
        val fixtureSources = FixtureJars.sourcesJar()
        Files.copy(fixtureSources.toPath(), versionDir.resolve("demo-1.0-sources.jar"))
        return dev.jdx.index.maven.MavenResolver.Repositories(
            gradleFilesRoot = null,
            m2Repo = root.resolve("m2"),
            fetchCacheRoot = null,
        )
    }

    private fun docRootsFor(
        repos: dev.jdx.index.maven.MavenResolver.Repositories,
        includeJdk: Boolean,
    ): RootsSpec = RootsSpec(
        jarSpecs = emptyList(),
        includeJdk = includeJdk,
        mavenResolve = { text, fetch -> dev.jdx.index.maven.MavenResolver.resolve(text, fetch, repos) },
    )

    // -- usage and degradation -------------------------------------------------

    @Test
    fun `static initialisers are a usage error`() {
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics#<clinit>", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "static initialisers have no documentation"
    }

    @Test
    fun `invalid references exit 3`() {
        val outcome = JdxService.doc("not a ref((((", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "usage error:"
    }

    @Test
    fun `negative max-lines is a usage error`() {
        JdxService.doc("dev.jdx.fixtures.Generics", fixtureRoots(), DocOptions(maxLines = -1))
            .exitCode shouldBe 3
    }

    @Test
    fun `no roots at all exits 4`() {
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.Generics",
            RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        )
        outcome.exitCode shouldBe 4
        textOf(outcome) shouldContain "no workspace"
    }

    @Test
    fun `kotlin-only type without a sidecar names the install hint`(@TempDir tempDir: Path) {
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeDocJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeDocJar(sources, mapOf("dev/jdx/fixtures/Generics.kt" to "fun dummy(): Int = 1\n".toByteArray()))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        // `tempDir` doubles as the Kotlin home: it holds no sidecar, so the
        // unavailable path pins hermetically even on machines with a real
        // sidecar installed.
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.Generics",
            roots,
            DocOptions(kotlinUserHome = tempDir),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "kotlin-compiler-embeddable-2.4.20.jar not installed under <cache-dir>/kotlin"
    }

    @Test
    fun `unpaired binary degrades naming T-026`(@TempDir tempDir: Path) {
        val binary = tempDir.resolve("bare.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeDocJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.doc("dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "T-026"
    }

    @Test
    fun `jdk member without src zip or with sources answers honestly`() {
        // The JDK ships src.zip on this machine: ArrayList answers from sources.
        // Wherever src.zip is absent this is exit 1 naming T-026 — both honest.
        val outcome = JdxService.doc(
            "java.util.ArrayList",
            RootsSpec(jarSpecs = emptyList(), includeJdk = true),
        )
        if (outcome.exitCode == 0) {
            textOf(outcome) shouldContain "source: "
        } else {
            outcome.exitCode shouldBe 1
            textOf(outcome) shouldContain "T-026"
        }
    }

    // -- invariants ------------------------------------------------------------

    @Test
    fun `running twice yields identical bytes`(@TempDir tempDir: Path) {
        val roots = caseRoots(buildDocCaseJars(tempDir))
        val ref = "doc.Child#greet(java.lang.String)"
        textOf(JdxService.doc(ref, roots)) shouldBe textOf(JdxService.doc(ref, roots))
        JdxService.doc(ref, roots).toJson("doc") shouldBe JdxService.doc(ref, roots).toJson("doc")
    }

    @Test
    fun `doc json carries the envelope with sources provenance and covers the text`(@TempDir tempDir: Path) {
        val outcome = JdxService.doc(
            "doc.Child#greet(java.lang.String)",
            caseRoots(buildDocCaseJars(tempDir)),
        )
        outcome.exitCode shouldBe 0
        val json = outcome.toJson("doc")
        json shouldContain "\"command\":\"doc\""
        json shouldContain "\"ok\":true"
        json shouldContain "\"query\":\"doc.Child#greet(java.lang.String)\""
        json shouldContain "\"ref\":\"doc.Child#greet(java.lang.String)\""
        json shouldContain "\"declaring\":\"doc.Child\""
        json shouldContain "\"kind\":\"method\""
        json shouldContain "\"inheritedFrom\":\"doc.Base\""
        json shouldContain "\"file\":\"doc/Base.java\""
        json shouldContain "\"lines\":["
        json shouldContain "\"artifact\":\"case-sources.jar\""
        json shouldContain "\"origin\":\"sources\""
        json shouldContain "Greets warmly."
        json shouldContain "\"warnings\":["
        json shouldContain "\"provenance\":["
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = java.io.File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val outcome = JdxService.doc("dev.jdx.fixtures.StaticInitMarker", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "source: "
        marker.exists() shouldBe false
    }
}
