package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.paths.JdxOs
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.fixtures.SidecarJars
import dev.jdx.testsupport.paths.symlinkOrCopy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Brace-matching `.kt` extractor (issue #16). Tier 1 owns the lexer laws
 * (strings, comments, raw strings, and a generative family). Tier 2 compares
 * simple shapes with PSI when the sidecar is in the Gradle cache, and scans
 * the fixture corpus.
 */
class KotlinLexerTest {

    @Test
    fun `strings chars comments and raw strings do not close a function`() {
        val src = """
            fun kept(value: Int): Int {
                val s = "}"
                val raw = ${"\"\"\""}
                } not a brace
                ${"\"\"\""}
                val c = '}'
                // }
                /* } /* } */ */
                val t = "${'$'}{ if (value > 0) { 1 } else { 0 } }"
                return value
            }
            fun next(): Int = 0
        """.trimIndent()
        val parsed = lexKotlinFile(src, "T.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
        parsed.origin shouldBe Origin.LEXER
        parsed.confident shouldBe true
        val slice = sliceOf(src, parsed.file, "kept")
        slice shouldContain "return value"
        slice shouldContain "\"}\""
        slice shouldNotContain "fun next"
    }

    @Test
    fun `an expression body does not swallow the next declaration`() {
        val src = """
            fun first(flag: Boolean): Int = if (flag) { 1 } else { 2 }
            fun second(): Int = 0
            class Box {
                val greeting: String
                    get() = "hi }"
                fun value(): Int = 3
            }
            fun Box.ext(): Int = 4
            suspend fun load(id: String): String = "user:${'$'}id"
            class Holder(val id: String, var n: Int = 1) {
                companion object {
                    fun create(): Holder = Holder("a")
                }
            }
            enum class Light {
                RED {
                    override fun isStop(): Boolean = true
                };
                abstract fun isStop(): Boolean
            }
        """.trimIndent()
        val parsed = lexKotlinFile(src, "T.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
        parsed.confident shouldBe true
        sliceOf(src, parsed.file, "first") shouldNotContain "fun second"
        sliceOf(src, parsed.file, "first") shouldContain "else"
        val box = decl(parsed.file.declarations, "Box")
        sliceOf(src, box, "greeting") shouldContain "hi"
        sliceOf(src, box, "greeting") shouldNotContain "fun value"
        decl(parsed.file.declarations, "ext").receiverType shouldBe "Box"
        decl(parsed.file.declarations, "load").isSuspend shouldBe true
        val holder = decl(parsed.file.declarations, "Holder")
        holder.children.any { it.name == "id" } shouldBe true
        holder.children.first { it.name == "Companion" }.isCompanion shouldBe true
        val red = decl(parsed.file.declarations, "RED")
        sliceOf(src, red, "isStop") shouldContain "true"
    }

    @Test
    fun `random names and brace noise inside strings comments and chars stay inside the function`() {
        val names = Arb.list(Arb.of(('a'..'z').toList()), 1..6).map { it.joinToString("") }
        val noises = Arb.list(Arb.of(listOf('}', '{', 'x')), 0..6).map { it.joinToString("") }
        runBlocking {
            checkAll(80, names, noises) { name, noise ->
                val src = """
                    fun $name(value: Int): Int {
                        val s = "$noise"
                        val c = '}'
                        // } $noise
                        /* } $noise */
                        return value
                    }
                    fun after$name(): Int = 0
                """.trimIndent()
                val parsed = lexKotlinFile(src, "G.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
                parsed.confident shouldBe true
                val slice = sliceOf(src, parsed.file, name)
                slice shouldContain "return value"
                slice shouldNotContain "fun after"
            }
        }
    }

    @Test
    fun `shebang backtick where constructor init and context receivers are recovered`() {
        val src = "\uFEFF#!\n" + """
            package demo
            import demo.Other
            fun `odd name`(): Int = 1
            fun interface Listener { fun onEvent(value: Int) }
            class Box<T>(val id: String) where T : CharSequence {
                val lazyName: String by lazy { id }
                val raw: String = ${"\"\"\""}
                    ${'$'}{ if (id.length > 0) { id } else { "" } }
                    ${"\"\"\""}
                val tick: Char = '\u0061'
                init { val n = id }
                constructor(value: Int) : this(value.toString()) {
                    val x = value
                }
                context(Box<String>)
                fun <T : Any> keyed(value: T): T where T : CharSequence = value
                fun pair(p: (Int, String)): Int = p.first
            }
            interface Named { val name: String }
        """.trimIndent()
        val parsed = lexKotlinFile(src, "More.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
        parsed.confident shouldBe true
        parsed.file.packageName shouldBe "demo"
        decl(parsed.file.declarations, "odd name").name shouldBe "odd name"
        decl(parsed.file.declarations, "Listener").name shouldBe "Listener"
        val box = decl(parsed.file.declarations, "Box")
        box.children.any { it.name == "<init>" } shouldBe true
        sliceOf(src, box, "keyed") shouldContain "value"
        sliceOf(src, box, "lazyName") shouldContain "lazy"
    }

    @Test
    fun `lexer and auto modes do not need the sidecar`(@TempDir home: Path) {
        val src = "fun answer(): Int = 1\n"
        openKotlinParser(home, JdxOs.LINUX, emptyMap(), KotlinMode.LEXER).use { parser ->
            parser.available shouldBe true
            val parsed = parser.parseKotlin(src, "A.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
            parsed.origin shouldBe Origin.LEXER
            parser.close()
        }
        openKotlinParser(home, JdxOs.LINUX, emptyMap(), KotlinMode.AUTO).use { parser ->
            val parsed = parser.parseKotlin(src, "A.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
            parsed.origin shouldBe Origin.LEXER
            parsed.confident shouldBe true
        }
    }

    @Test
    fun `unbalanced delimiters are unavailable and never throw`() {
        val broken = listOf(
            "fun a(): Int { return \"",
            "fun a(): Int { return 1 /* ",
            "fun a(): Int { return 1 } }",
            "fun a(): Int = \"\"\"still open",
            "fun a(): Char = '",
        )
        for (src in broken) {
            lexKotlinFile(src, "Bad.kt").shouldBeInstanceOf<KotlinParse.Unavailable>()
        }
    }

    @Test
    fun `a recovered member keeps lexer provenance and a miss of an unconfident file degrades`() {
        val text = """
            package demo
            class Box {
                fun answer(): Int {
                    val junk = "}"
                    return 7
                }
            }
        """.trimIndent()
        val root = MemorySourceRoot(mapOf("demo/Box.kt" to text))
        val found = findKotlinBodies(root, ref("demo.Box", "answer"), LexerKotlinParser)
            .shouldBeInstanceOf<JavaBodyResult.Found>()
        val body = found.bodies.single()
        body.origin shouldBe Origin.LEXER
        body.text shouldContain "return 7"
        body.text shouldContain "\"}\""

        val understood = findKotlinBodies(root, ref("demo.Box", "missing"), LexerKotlinParser)
        understood.shouldBeInstanceOf<JavaBodyResult.MemberNotFound>()

        val shaky = """
            package demo
            fun kept(): Int {
                return 1
            }
            oops
        """.trimIndent()
        val shakyRoot = MemorySourceRoot(mapOf("demo/Box.kt" to shaky))
        findKotlinBodies(shakyRoot, ref("demo.Box", "kept"), LexerKotlinParser)
            .shouldBeInstanceOf<JavaBodyResult.ParserUnavailable>()
    }

    @Tag("tier2")
    @Test
    fun `fixture KotlinShapes is brace-matched confidently`() {
        assumeTrue(
            FixtureJars.sourcesCorpusAvailable(),
            "no testfixtures corpus: build :testfixtures first (docs/TESTING.md §11)",
        )
        val text = JarSourceRoot(FixtureJars.sourcesJar().toPath()).use { root ->
            root.openSource("dev/jdx/fixtures/KotlinShapes.kt").use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val parsed = lexKotlinFile(text, "KotlinShapes.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
        parsed.confident shouldBe true
        parsed.origin shouldBe Origin.LEXER
        val members = decl(parsed.file.declarations, "KotlinMembers")
        val original = sliceOf(text, members, "originalName")
        original shouldContain "value * 2"
        original shouldNotContain "user:"
        decl(parsed.file.declarations, "kotlinFirstOrNull").receiverType shouldBe "List<T>"
        sliceOf(text, decl(parsed.file.declarations, "RED"), "isStop") shouldContain "true"
        sliceOf(text, decl(parsed.file.declarations, "KotlinData"), "greeting") shouldContain "hi"
    }

    @Tag("tier2")
    @Test
    fun `lexer agrees with psi on simple shapes`(@TempDir home: Path) {
        val jars = SidecarJars.cachedRuntimeJars()
        assumeTrue(
            jars.containsKey(SidecarJars.COMPILER_JAR),
            "kotlin-compiler-embeddable not in the Gradle cache",
        )
        val dir = kotlinSidecarDir(home, JdxOs.LINUX, emptyMap())
        Files.createDirectories(dir)
        for ((jarName, cached) in jars) {
            symlinkOrCopy(dir.resolve(jarName), cached)
        }
        val snippets = listOf(
            "fun answer(): Int = 1\n",
            "class Box {\n    fun value(): String = \"ok\"\n    val n: Int = 2\n}\n",
            "class Box\nfun Box.ext(): Int = 3\n",
            "suspend fun load(id: String): String = \"user:${'$'}id\"\n",
        )
        openKotlinParser(home, JdxOs.LINUX, emptyMap(), KotlinMode.PSI).use { psi ->
            for (src in snippets) {
                val lexer = lexKotlinFile(src, "T.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
                val parsed = psi.parseKotlin(src, "T.kt").shouldBeInstanceOf<KotlinParse.Parsed>()
                names(lexer.file.declarations, KotlinDeclKind.FUNCTION) shouldBe
                    names(parsed.file.declarations, KotlinDeclKind.FUNCTION)
                names(lexer.file.declarations, KotlinDeclKind.PROPERTY) shouldBe
                    names(parsed.file.declarations, KotlinDeclKind.PROPERTY)
            }
        }
    }

    private fun ref(type: String, name: String): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
        parameterTypes = emptyList(),
    )

    private fun decl(decls: List<KotlinDecl>, name: String): KotlinDecl =
        findDecl(decls, name) ?: error("no declaration named $name")

    private fun findDecl(decls: List<KotlinDecl>, name: String): KotlinDecl? {
        for (decl in decls) {
            if (decl.name == name) return decl
            findDecl(decl.children, name)?.let { return it }
        }
        return null
    }

    private fun sliceOf(text: String, scope: KotlinDecl, name: String): String {
        val decl = decl(scope.children.ifEmpty { listOf(scope) }, name)
        return text.substring(decl.startOffset, decl.endOffset)
    }

    private fun sliceOf(text: String, file: KotlinFile, name: String): String {
        val decl = decl(file.declarations, name)
        return text.substring(decl.startOffset, decl.endOffset)
    }

    private fun names(decls: List<KotlinDecl>, kind: KotlinDeclKind): List<String> {
        val out = mutableListOf<String>()
        for (decl in decls) {
            if (decl.kind == kind) out += decl.name
            out += names(decl.children, kind)
        }
        return out
    }
}
