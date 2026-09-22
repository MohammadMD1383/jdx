package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Kotlin body/KDoc matching over hand-built [KotlinFile] models (T-039, tier
 * 1): navigation, name matching, signature narrowing, slicing and discovery
 * are pure over the model, so this suite pins every law without a compiler.
 * Real PSI ranges live in `KotlinBodiesPsiTest` (tier 2).
 */
class KotlinBodiesTest {

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

    private fun fakeParser(
        available: Boolean = true,
        parse: (text: String, fileName: String) -> KotlinParse = { _, _ ->
            KotlinParse.Failed("no fake file")
        },
    ): KotlinSourceParser = object : KotlinSourceParser {
        override val available: Boolean = available
        override val detail: String = "fake kotlin parser"
        override fun parseKotlin(text: String, fileName: String): KotlinParse =
            if (available) parse(text, fileName) else KotlinParse.Unavailable(detail)

        override fun close(): Unit = Unit
    }

    private fun fn(
        name: String,
        params: List<KotlinParam> = emptyList(),
        doc: String? = null,
        suspend: Boolean = false,
        receiver: String? = null,
        returns: String? = null,
    ): KotlinDecl = KotlinDecl(
        kind = KotlinDeclKind.FUNCTION,
        name = name,
        params = params,
        returnType = returns,
        receiverType = receiver,
        isSuspend = suspend,
        startOffset = 0,
        endOffset = 10,
        docText = doc,
        docStartOffset = null,
        docEndOffset = null,
    )

    private fun prop(name: String, doc: String? = null): KotlinDecl = KotlinDecl(
        kind = KotlinDeclKind.PROPERTY,
        name = name,
        startOffset = 0,
        endOffset = 10,
        docText = doc,
        docStartOffset = null,
        docEndOffset = null,
    )

    private fun param(name: String, type: String?, default: Boolean = false): KotlinParam =
        KotlinParam(name, type, default)

    // -- navigation ----------------------------------------------------------

    @Test
    fun `navigateKotlin reaches nested classes through dollar nesting`() {
        val inner = KotlinDecl(
            kind = KotlinDeclKind.CLASS, name = "Inner",
            startOffset = 0, endOffset = 5,
            docText = null, docStartOffset = null, docEndOffset = null,
        )
        val outer = KotlinDecl(
            kind = KotlinDeclKind.CLASS, name = "Outer",
            startOffset = 0, endOffset = 20,
            docText = null, docStartOffset = null, docEndOffset = null,
            children = listOf(inner),
        )
        val file = KotlinFile("com.example", listOf(outer))

        navigateKotlin(file, "com.example.Outer")!!.declarations shouldBe listOf(inner)
        navigateKotlin(file, "com.example.Outer\$Inner")!!.declarations shouldBe emptyList()
    }

    @Test
    fun `navigateKotlin serves file facades at file scope`() {
        val file = KotlinFile("com.example", listOf(fn("top")))
        val scope = navigateKotlin(file, "com.example.WidgetsKt")
        scope!!.declarations.map { it.name } shouldBe listOf("top")
    }

    @Test
    fun `navigateKotlin rejects hostile and missing names`() {
        val file = KotlinFile("", listOf(fn("top")))
        navigateKotlin(file, "") shouldBe null
        navigateKotlin(file, "com.example.\$\$Bad") shouldBe null
        navigateKotlin(file, "com.example.Missing") shouldBe null
        navigateKotlin(file, "com.example.Missing\$Inner") shouldBe null
    }

    // -- name matching ---------------------------------------------------------

    @Test
    fun `matchKotlinByName matches functions and properties by name`() {
        val scope = KtScope(listOf(fn("fetch"), prop("nickname")), null)
        matchKotlinByName(scope, "fetch", emptySet()).map { it.name } shouldBe listOf("fetch")
        matchKotlinByName(scope, "nickname", emptySet()).map { it.name } shouldBe listOf("nickname")
        matchKotlinByName(scope, "absent", emptySet()) shouldBe emptyList()
    }

    @Test
    fun `matchKotlinByName honours aka spellings`() {
        val scope = KtScope(listOf(fn("originalName")), null)
        matchKotlinByName(scope, "renamedForJvm", setOf("originalName")).map { it.name } shouldBe
            listOf("originalName")
    }

    @Test
    fun `matchKotlinByName maps JVM accessors onto the property`() {
        val scope = KtScope(listOf(prop("nickname")), null)
        matchKotlinByName(scope, "getNickname", emptySet()).map { it.name } shouldBe listOf("nickname")
        matchKotlinByName(scope, "setNickname", emptySet()).map { it.name } shouldBe listOf("nickname")
        matchKotlinByName(scope, "getOther", emptySet()) shouldBe emptyList()
    }

    @Test
    fun `matchKotlinByName resolves init to explicit ctors only`() {
        val primary = KotlinDecl(
            kind = KotlinDeclKind.CONSTRUCTOR, name = "<init>",
            params = listOf(param("name", "String")),
            startOffset = 0, endOffset = 5,
            docText = null, docStartOffset = null, docEndOffset = null,
        )
        val secondary = KotlinDecl(
            kind = KotlinDeclKind.CONSTRUCTOR, name = "<init>",
            startOffset = 6, endOffset = 12,
            docText = null, docStartOffset = null, docEndOffset = null,
        )
        val scope = KtScope(listOf(fn("create"), secondary), primaryCtor = primary)
        matchKotlinByName(scope, "<init>", emptySet()) shouldBe listOf(primary, secondary)

        val implicit = KtScope(listOf(fn("create")), primaryCtor = null)
        matchKotlinByName(implicit, "<init>", emptySet()) shouldBe emptyList()
        matchKotlinByName(implicit, "<clinit>", emptySet()) shouldBe emptyList()
    }

    @Test
    fun `matchKotlinByName falls back to companion members`() {
        val companion = KotlinDecl(
            kind = KotlinDeclKind.OBJECT, name = "Companion",
            startOffset = 0, endOffset = 20,
            docText = null, docStartOffset = null, docEndOffset = null,
            children = listOf(fn("create")),
            isCompanion = true,
        )
        val scope = KtScope(listOf(fn("fetch"), companion), null)
        matchKotlinByName(scope, "create", emptySet()).map { it.name } shouldBe listOf("create")
        // Direct children still win: no companion search when matched.
        matchKotlinByName(scope, "fetch", emptySet()).map { it.name } shouldBe listOf("fetch")
    }

    // -- signature narrowing -----------------------------------------------------

    @Test
    fun `narrowKotlinBySignature keeps every overload when under-specified`() {
        val overloads = listOf(
            fn("withDefault", listOf(param("first", "Int"))),
            fn("withDefault", listOf(param("first", "Int"), param("second", "String", default = true))),
        )
        narrowKotlinBySignature(overloads, ref("com.example.Foo", "withDefault")) shouldBe overloads
    }

    @Test
    fun `narrowKotlinBySignature matches Kotlin primitive spellings`() {
        val candidate = fn("originalName", listOf(param("value", "Int")))
        narrowKotlinBySignature(
            listOf(candidate),
            ref("com.example.Foo", "originalName", listOf("int")),
        ) shouldBe listOf(candidate)
    }

    @Test
    fun `narrowKotlinBySignature accepts the suspend JVM spelling`() {
        val candidate = fn("fetch", listOf(param("id", "UserId")), suspend = true)
        narrowKotlinBySignature(
            listOf(candidate),
            ref(
                "com.example.Foo",
                "fetch",
                listOf("dev.jdx.fixtures.UserId", "kotlin.coroutines.Continuation"),
            ),
        ) shouldBe listOf(candidate)
    }

    @Test
    fun `narrowKotlinBySignature counts the extension receiver`() {
        val candidate = fn("extensionGreeting", receiver = "dev.jdx.fixtures.KotlinMembers")
        narrowKotlinBySignature(
            listOf(candidate),
            ref("com.example.FooKt", "extensionGreeting", listOf("dev.jdx.fixtures.KotlinMembers")),
        ) shouldBe listOf(candidate)
        narrowKotlinBySignature(
            listOf(candidate),
            ref("com.example.FooKt", "extensionGreeting", listOf("java.lang.String")),
        ) shouldBe emptyList()
    }

    @Test
    fun `narrowKotlinBySignature allows omitted trailing defaults`() {
        val candidate = fn(
            "withDefault",
            listOf(param("first", "Int"), param("second", "String", default = true)),
        )
        narrowKotlinBySignature(
            listOf(candidate),
            ref("com.example.Foo", "withDefault", listOf("int")),
        ) shouldBe listOf(candidate)
        narrowKotlinBySignature(
            listOf(candidate),
            ref("com.example.Foo", "withDefault", listOf("int", "java.lang.String", "boolean")),
        ) shouldBe emptyList()
    }

    @Test
    fun `kotlinTypeKey maps Kotlin spellings onto JVM keys`() {
        kotlinTypeKey("Int") shouldBe "int"
        kotlinTypeKey("String?") shouldBe "String"
        kotlinTypeKey("Any") shouldBe "Object"
        kotlinTypeKey("List<String>") shouldBe "List"
        kotlinTypeKey("Integer") shouldBe "int"
        kotlinTypeKey("Unit") shouldBe "void"
    }

    // -- slicing -------------------------------------------------------------------

    @Test
    fun `sliceKotlinBody cuts verbatim lines from offsets`() {
        val text = "package demo\n\n/** greeting. */\nfun greet(name: String): String {\n    return \"hi \$name\"\n}\n"
        val decl = KotlinDecl(
            kind = KotlinDeclKind.FUNCTION, name = "greet",
            params = listOf(param("name", "String")), returnType = "String",
            startOffset = text.indexOf("fun greet"),
            endOffset = text.length,
            docText = " greeting. ",
            docStartOffset = text.indexOf("/**"),
            docEndOffset = text.indexOf("*/") + 2,
        )
        val lines = splitKotlinLines(text)
        val body = sliceKotlinBody("demo/Greet.kt", lines, text, decl)!!
        // The KDoc the PSI range includes is trimmed: the slice starts at `fun`.
        body.startLine shouldBe 4
        body.endLine shouldBe 6
        body.text shouldContain "return \"hi \$name\""
        body.kind shouldBe SourceBodyKind.METHOD
        body.returnType shouldBe "String"
    }

    @Test
    fun `kotlinLineRange rejects out-of-bounds offsets`() {
        val text = "a\nb\n"
        val lines = splitKotlinLines(text)
        val bad = KotlinDecl(
            kind = KotlinDeclKind.FUNCTION, name = "f",
            startOffset = 0, endOffset = 500,
            docText = null, docStartOffset = null, docEndOffset = null,
        )
        sliceKotlinBody("x.kt", lines, text, bad) shouldBe null
        lineOfOffset(text, 0) shouldBe 1
        lineOfOffset(text, 2) shouldBe 2
    }

    @Test
    fun `kdocInnerOf strips delimiters and rejects blanks`() {
        kdocInnerOf("/** greeting. */") shouldBe " greeting. "
        kdocInnerOf("/***/") shouldBe null
        kdocInnerOf("not a doc") shouldBe null
        kdocInnerOf("/**\n * line one\n * line two\n */")!! shouldContain "line one"
    }

    // -- end-to-end over memory roots ------------------------------------------------

    @Test
    fun `findKotlinBodies slices a function through the parser`() {
        val text = "package demo\n\nclass Answer {\n    fun answer(): Int = 42\n}\n"
        val answer = KotlinDecl(
            kind = KotlinDeclKind.FUNCTION, name = "answer",
            returnType = "Int",
            startOffset = text.indexOf("fun"),
            endOffset = text.indexOf("42") + 2,
            docText = null, docStartOffset = null, docEndOffset = null,
        )
        val file = KotlinFile(
            "demo",
            listOf(
                KotlinDecl(
                    kind = KotlinDeclKind.CLASS, name = "Answer",
                    startOffset = text.indexOf("class"),
                    endOffset = text.length - 1,
                    docText = null, docStartOffset = null, docEndOffset = null,
                    children = listOf(answer),
                ),
            ),
        )
        val root = MemorySourceRoot(mapOf("demo/Answer.kt" to text))
        val parser = fakeParser { _, _ -> KotlinParse.Parsed(file) }
        // Direct `.kt` hit: the binary name matches the file name here.
        val found = findKotlinBodies(root, ref("demo.Answer", "answer"), parser)
            .shouldBeInstanceOf<JavaBodyResult.Found>()
        found.bodies shouldHaveSize 1
        found.bodies.single().text shouldContain "42"
    }

    @Test
    fun `findKotlinBodies degrades without a parser or file`() {
        val root = MemorySourceRoot(mapOf("demo/Answer.kt" to "package demo\n"))
        findKotlinBodies(root, ref("demo.Answer", "answer"), fakeParser(available = false))
            .shouldBeInstanceOf<JavaBodyResult.ParserUnavailable>()
        findKotlinBodies(root, ref("demo.Missing", "answer"), fakeParser())
            .shouldBeInstanceOf<JavaBodyResult.NoSource>()
        val empty = fakeParser { _, _ -> KotlinParse.Parsed(KotlinFile("demo", emptyList())) }
        findKotlinBodies(root, ref("demo.Answer", "absent"), empty)
            .shouldBeInstanceOf<JavaBodyResult.MemberNotFound>()
    }

    @Test
    fun `findKotlinSourcePath prefers declarations over the facade guess`() {
        // `Bar.kt` declares class `FooKt`: the declaration wins over `Foo.kt`.
        val bar = "package demo\n\nclass FooKt\n"
        val foo = "package demo\n\nfun unrelated() = 1\n"
        val root = MemorySourceRoot(mapOf("demo/Bar.kt" to bar, "demo/Foo.kt" to foo))
        val parser = fakeParser { text, _ ->
            if ("class FooKt" in text) {
                KotlinParse.Parsed(
                    KotlinFile(
                        "demo",
                        listOf(
                            KotlinDecl(
                                kind = KotlinDeclKind.CLASS, name = "FooKt",
                                startOffset = 0, endOffset = text.length,
                                docText = null, docStartOffset = null, docEndOffset = null,
                            ),
                        ),
                    ),
                )
            } else {
                KotlinParse.Parsed(KotlinFile("demo", listOf(fn("unrelated"))))
            }
        }
        findKotlinSourcePath(root, "demo.FooKt", parser) shouldBe "demo/Bar.kt"
    }

    @Test
    fun `findKotlinSourcePath applies the facade convention`() {
        val shapes = "package demo\n\nfun KotlinMembers.extensionGreeting(): String = \"hi\"\n"
        val root = MemorySourceRoot(mapOf("demo/KotlinShapes.kt" to shapes))
        val parser = fakeParser { text, _ ->
            KotlinParse.Parsed(KotlinFile("demo", listOf(fn("extensionGreeting"))))
        }
        findKotlinSourcePath(root, "demo.KotlinShapesKt", parser) shouldBe "demo/KotlinShapes.kt"
    }

    // -- generating families ---------------------------------------------------------------

    @Test
    fun `hostile queries never throw`(): Unit = runBlocking {
        val names = Arb.string(0, 40)
        val texts = Arb.string(0, 200)
        checkAll(names, texts) { rawName, rawText ->
            val root = MemorySourceRoot(mapOf("a/B.kt" to rawText))
            val parser = fakeParser { _, _ -> KotlinParse.Parsed(KotlinFile("", emptyList())) }
            val binary = "a.B"
            findKotlinBodies(root, ref(binary, rawName), parser)
            findKotlinMemberDocs(root, ref(binary, rawName), parser)
            findKotlinTypeDoc(root, binary, parser)
            listKotlinMembers(root, binary, parser)
            findKotlinSourcePath(root, rawName, parser)
            navigateKotlin(KotlinFile("", emptyList()), rawName)
            kdocInnerOf(rawText)
            containsWord(rawText, rawName)
        }
    }

    @Test
    fun `queries are deterministic`(): Unit = runBlocking {
        val texts = Arb.string(0, 200).filter { it.isNotBlank() }
        checkAll(texts) { rawText ->
            val root = MemorySourceRoot(mapOf("a/B.kt" to rawText))
            val parser = fakeParser { _, _ -> KotlinParse.Parsed(KotlinFile("", emptyList())) }
            val first = findKotlinBodies(root, ref("a.B", "member"), parser)
            findKotlinBodies(root, ref("a.B", "member"), parser) shouldBe first
            val listed = listKotlinMembers(root, "a.B", parser)
            listKotlinMembers(root, "a.B", parser) shouldBe listed
        }
    }

    @Test
    fun `line mapping is monotone`(): Unit = runBlocking {
        val texts = Arb.list(Arb.string(0, 20), 1..12)
        checkAll(texts) { parts ->
            val text = parts.joinToString("\n")
            var previous = 0
            for (offset in 0..text.length) {
                val line = lineOfOffset(text, offset)
                (line >= previous) shouldBe true
                previous = line
            }
        }
    }
}
