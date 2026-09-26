package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.paths.JdxOs
import dev.jdx.testsupport.paths.symlinkOrCopy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Kotlin PSI parsing against the real `kotlin-compiler-embeddable` (T-039,
 * tier 2): the sidecar is symlinked from the Gradle cache into a temp home,
 * so no test writes to the real `~/.cache`, and the suite skips when the
 * compiler jar is absent. Pins real PSI ranges over the T-006 fixture
 * `KotlinShapes.kt`: declarations, suspend/default/alias shapes, bodies and
 * KDoc.
 */
@Tag("tier2")
class KotlinBodiesPsiTest {

    private fun ref(
        type: String,
        name: String,
        params: List<String>? = null,
        returns: String? = null,
    ): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
        parameterTypes = params?.map { typeNameFromBinaryName(it) },
        returnType = returns?.let { typeNameFromBinaryName(it) },
    )

    private fun fixturesDir(): File {
        System.getProperty("jdx.fixturesDir")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixture jars not found: build :testfixtures first")
    }

    /** The 2.4.20 embeddable compiler from the Gradle module cache, or null. */
    private fun cachedCompilerJar(): Path? =
        cachedRuntimeJars()[KOTLIN_COMPILER_JAR]

    /** The compiler plus its runtime jars from the Gradle module cache. */
    /** Borrowed from the Gradle module cache via the shared [SidecarJars] helper. */
    private fun cachedRuntimeJars(): Map<String, Path> =
        dev.jdx.testsupport.fixtures.SidecarJars.cachedRuntimeJars()

    // Pinned hermetic sidecar context (same seam as the index suites):
    // LINUX + empty env resolves under the temp home on every host OS.
    private val ktOs: JdxOs = JdxOs.LINUX
    private val ktEnv: Map<String, String> = emptyMap()

    /** A temp home with the sidecar set staged in, plus an opened parser. */
    private fun withParser(home: Path, block: (KotlinSourceParser) -> Unit) {
        val jars = cachedRuntimeJars()
        assumeTrue(
            jars.containsKey(KOTLIN_COMPILER_JAR),
            "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION not in the Gradle cache",
        )
        val dir = kotlinSidecarDir(home, ktOs, ktEnv)
        Files.createDirectories(dir)
        // Link, copying where the host cannot symlink (Windows without
        // Developer Mode): skipping there would hollow the suite and the
        // coverage gates counting on it.
        for ((jarName, cached) in jars) {
            symlinkOrCopy(dir.resolve(jarName), cached)
        }
        openKotlinParser(home, ktOs, ktEnv).use(block)
    }

    private fun fixtureSourcesRoot(): JarSourceRoot {
        val jars = fixturesDir().listFiles { file ->
            file.isFile && file.name.endsWith("-sources.jar")
        }?.toList().orEmpty()
        require(jars.size == 1) { "expected exactly one fixture sources jar, found: $jars" }
        return JarSourceRoot(jars.single().toPath())
    }

    private fun foundBodies(
        root: SourceRoot,
        ref: MemberSymbolRef,
        parser: KotlinSourceParser,
        aka: Set<String> = emptySet(),
    ): List<SourceBody> =
        findKotlinBodies(root, ref, parser, aka).shouldBeInstanceOf<JavaBodyResult.Found>().bodies

    @Test
    fun `the sidecar parses KotlinShapesKt declarations`(@TempDir home: Path) {
        withParser(home) { parser ->
            parser.available shouldBe true
            fixtureSourcesRoot().use { root ->
                val path = findKotlinSourcePath(root, "dev.jdx.fixtures.KotlinMembers", parser)
                path shouldBe "dev/jdx/fixtures/KotlinShapes.kt"
                val text = root.openSource(path!!).use { it.readBytes().toString(Charsets.UTF_8) }
                val file = (parser.parseKotlin(text, "KotlinShapes.kt") as? KotlinParse.Parsed)
                    ?.file ?: error("expected Parsed")
                val names = file.declarations.map { it.kind to it.name }
                (names.any { it == (KotlinDeclKind.CLASS to "KotlinMembers") }) shouldBe true
                (names.any { it == (KotlinDeclKind.CLASS to "KotlinData") }) shouldBe true
                val members = file.declarations.first { it.name == "KotlinMembers" }
                val memberNames = members.children.map { it.name }
                (memberNames.contains("originalName")) shouldBe true
                (memberNames.contains("fetch")) shouldBe true
                (memberNames.contains("withDefault")) shouldBe true
                members.children.first { it.name == "fetch" }.isSuspend shouldBe true
                val withDefault = members.children.first { it.name == "withDefault" }
                withDefault.params.map { it.hasDefault } shouldBe listOf(false, true)
            }
        }
    }

    @Test
    fun `suspend function body slices the Kotlin declaration`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val bodies = foundBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinMembers", "fetch"),
                    parser,
                )
                bodies shouldHaveSize 1
                val body = bodies.single()
                body.file shouldBe "dev/jdx/fixtures/KotlinShapes.kt"
                body.kind shouldBe SourceBodyKind.METHOD
                body.text shouldContain "user:\$id"
                (body.startLine >= 1) shouldBe true
                (body.endLine >= body.startLine) shouldBe true
            }
        }
    }

    @Test
    fun `JvmName query matches the Kotlin spelling via aka`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                // The JVM name alone cannot match sources: the alias carries it.
                findKotlinBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinMembers", "renamedForJvm", listOf("int")),
                    parser,
                ).shouldBeInstanceOf<JavaBodyResult.MemberNotFound>()
                val bodies = foundBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinMembers", "renamedForJvm", listOf("int")),
                    parser,
                    aka = setOf("originalName"),
                )
                bodies shouldHaveSize 1
                bodies.single().text shouldContain "value * 2"
            }
        }
    }

    @Test
    fun `property body slices the property declaration`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val bodies = foundBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinData", "nickname"),
                    parser,
                    aka = setOf("nickname"),
                )
                bodies shouldHaveSize 1
                val body = bodies.single()
                body.kind shouldBe SourceBodyKind.FIELD
                body.text shouldContain "nickname"
            }
        }
    }

    @Test
    fun `companion member resolves through the companion fallback`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val bodies = foundBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinMembers\$Companion", "create"),
                    parser,
                )
                bodies shouldHaveSize 1
                bodies.single().text shouldContain "KotlinMembers()"
            }
        }
    }

    @Test
    fun `file facade members resolve at file scope`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val path = findKotlinSourcePath(root, "dev.jdx.fixtures.KotlinShapesKt", parser)
                path shouldBe "dev/jdx/fixtures/KotlinShapes.kt"
                val bodies = foundBodies(
                    root,
                    ref("dev.jdx.fixtures.KotlinShapesKt", "extensionGreeting"),
                    parser,
                )
                bodies shouldHaveSize 1
                bodies.single().text shouldContain "hello"
            }
        }
    }

    @Test
    fun `member KDoc resolves with PSI ranges`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val docs = findKotlinMemberDocs(
                    root,
                    ref("dev.jdx.fixtures.KotlinShapesKt", "extensionGreeting"),
                    parser,
                ).shouldBeInstanceOf<JavaDocResult.Found>().docs
                docs shouldHaveSize 1
                docs.single().rawComment shouldContain "Extension function"
            }
        }
    }

    @Test
    fun `type KDoc resolves for documented classes`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                // `UserIdBox` carries its KDoc directly above its annotations;
                // `KotlinData`'s header comment documents the file (a typealias
                // sits between it and the class), so PSI leaves it undocumented.
                val docs = findKotlinTypeDoc(root, "dev.jdx.fixtures.UserIdBox", parser)
                    .shouldBeInstanceOf<JavaDocResult.Found>().docs
                docs shouldHaveSize 1
                docs.single().rawComment shouldContain "erased to its underlying type"
            }
        }
    }

    @Test
    fun `crlf kotlin sources resolve bodies and kdoc like lf sources`(@TempDir home: Path) {
        // Windows checkouts carry CRLF (`* text=auto`), and the PSI
        // doc-comment association misses every KDoc under CRLF while bodies
        // still slice — the read seam normalises, pinned here through the
        // full product path (jar → find → slice), not the raw parse API.
        // Shapes mirror the real reds: KDoc above annotations (UserIdBox)
        // and a directly-documented facade function (extensionGreeting).
        withParser(home) { parser ->
            val lf = "package demo\n\n" +
                "/** A box, erased on the JVM. */\n" +
                "@Deprecated(\"old\")\n" +
                "class Box(val id: String)\n\n" +
                "/** Greets. */\n" +
                "fun greet(): String = \"hi\"\n"
            JarSourceRoot(craftTextJar(home.resolve("crlf-sources.jar"), "demo/Demo.kt", lf)).use { lfRoot ->
                val lfType = findKotlinTypeDoc(lfRoot, "demo.Box", parser)
                    .shouldBeInstanceOf<JavaDocResult.Found>().docs
                lfType shouldHaveSize 1
            }
            JarSourceRoot(
                craftTextJar(home.resolve("crlf-sources.jar"), "demo/Demo.kt", lf.replace("\n", "\r\n")),
            ).use { root ->
                val typeDocs = findKotlinTypeDoc(root, "demo.Box", parser)
                    .shouldBeInstanceOf<JavaDocResult.Found>().docs
                typeDocs shouldHaveSize 1
                typeDocs.single().rawComment shouldContain "erased on the JVM"
                val memberDocs = findKotlinMemberDocs(root, ref("demo.DemoKt", "greet"), parser)
                    .shouldBeInstanceOf<JavaDocResult.Found>().docs
                memberDocs shouldHaveSize 1
                memberDocs.single().rawComment shouldContain "Greets."
                // Offsets index the normalised text: the body slice is exact,
                // proving normalisation did not shift anything.
                val bodies = findKotlinBodies(root, ref("demo.DemoKt", "greet"), parser)
                    .shouldBeInstanceOf<JavaBodyResult.Found>().bodies
                bodies shouldHaveSize 1
                bodies.single().text shouldBe "fun greet(): String = \"hi\""
            }
        }
    }

    /** A one-entry sources jar: [text] stored verbatim (LF or CRLF as given). */
    private fun craftTextJar(jar: Path, entry: String, text: String): Path {
        Files.createDirectories(jar.parent)
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(entry))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `member listing names Kotlin declarations`(@TempDir home: Path) {
        withParser(home) { parser ->
            fixtureSourcesRoot().use { root ->
                val listed = listKotlinMembers(root, "dev.jdx.fixtures.KotlinMembers", parser)
                    .shouldBeInstanceOf<JavaMemberList.Listed>().members
                val names = listed.map { it.name }
                (names.contains("originalName")) shouldBe true
                (names.contains("fetch")) shouldBe true
                (names.contains("<init>")) shouldBe false
            }
        }
    }
}
