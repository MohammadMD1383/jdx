package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure Java body extraction (T-021, tier 1): inline sources over an
 * in-memory [SourceRoot], so no disk is touched. Disk behaviour (real
 * `-sources.jar` reads, crafted hostile jars) lives in the tier-2 suite;
 * generating coverage lives in the properties below.
 */
class JavaBodiesTest {

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

    private val shapes = MemorySourceRoot(
        mapOf(
            "com/example/Overloads.java" to
                """
                package com.example;
                public class Overloads {
                    public int add(int a, int b) {
                        return a + b;
                    }
                    public String add(String a, String b) {
                        return a + b;
                    }
                    public Overloads() {
                    }
                    public Overloads(int seed) {
                        this.seed = seed;
                    }
                    private int seed;
                    private int count, total;
                }
                """.trimIndent(),
            "com/example/Nesting.java" to
                """
                package com.example;
                public class Nesting {
                    public static class Inner {
                        public String greet(String name) {
                            return "hi " + name;
                        }
                    }
                }
                """.trimIndent(),
            "com/example/Point.java" to
                """
                package com.example;
                public record Point(int x, int y) {
                    public Point {
                        if (x < 0) {
                            throw new IllegalArgumentException("x");
                        }
                    }
                }
                """.trimIndent(),
            "com/example/Traffic.java" to
                """
                package com.example;
                public enum Traffic {
                    RED(30) {
                        @Override
                        public boolean isStop() {
                            return true;
                        }
                    },
                    GREEN(30);
                    private final int seconds;
                    Traffic(int seconds) {
                        this.seconds = seconds;
                    }
                }
                """.trimIndent(),
            "com/example/Only.kt" to "class Only",
        ),
    )

    private fun foundBodies(root: SourceRoot = shapes, ref: MemberSymbolRef): List<SourceBody> {
        val result = findJavaBodies(root, ref)
        return result.shouldBeInstanceOf<JavaBodyResult.Found>().bodies
    }

    @Test
    fun `method body is verbatim with 1-based lines`() {
        val bodies = foundBodies(ref = ref("com.example.Overloads", "add", listOf("int", "int")))
        bodies shouldHaveSize 1
        val body = bodies.single()
        body.kind shouldBe SourceBodyKind.METHOD
        body.file shouldBe "com/example/Overloads.java"
        body.startLine shouldBe 3
        body.endLine shouldBe 5
        body.text shouldBe "    public int add(int a, int b) {\n        return a + b;\n    }"
        body.parameterTypes shouldBe listOf("int", "int")
        body.returnType shouldBe "int"
    }

    @Test
    fun `under-specified ref returns every overload`() {
        val bodies = foundBodies(ref = ref("com.example.Overloads", "add"))
        bodies shouldHaveSize 2
        bodies.mapNotNull { it.returnType }.sorted() shouldBe listOf("String", "int")
    }

    @Test
    fun `fully-qualified ref params match simple source spellings`() {
        val bodies = foundBodies(
            ref = ref("com.example.Nesting\$Inner", "greet", listOf("java.lang.String")),
        )
        bodies shouldHaveSize 1
        bodies.single().text shouldContain "hi "
    }

    @Test
    fun `init matches every constructor`() {
        val bodies = foundBodies(ref = ref("com.example.Overloads", "<init>"))
        bodies shouldHaveSize 2
        bodies.forEach { it.kind shouldBe SourceBodyKind.CONSTRUCTOR }
        bodies.forEach { it.name shouldBe "<init>" }
    }

    @Test
    fun `parameterised init narrows constructors by arity`() {
        val bodies = foundBodies(ref = ref("com.example.Overloads", "<init>", listOf("int")))
        bodies shouldHaveSize 1
        bodies.single().parameterTypes shouldBe listOf("int")
    }

    @Test
    fun `field matches by variable name slicing the whole declaration`() {
        val bodies = foundBodies(ref = ref("com.example.Overloads", "count"))
        bodies shouldHaveSize 1
        val body = bodies.single()
        body.kind shouldBe SourceBodyKind.FIELD
        body.text shouldBe "    private int count, total;"
    }

    @Test
    fun `parameterised ref never matches a field`() {
        findJavaBodies(shapes, ref("com.example.Overloads", "seed", listOf("int"))) shouldBe
            JavaBodyResult.MemberNotFound
        findJavaBodies(shapes, ref("com.example.Overloads", "seed", emptyList())) shouldBe
            JavaBodyResult.MemberNotFound
    }

    @Test
    fun `nested classes resolve through dollar segments`() {
        val bodies = foundBodies(ref = ref("com.example.Nesting\$Inner", "greet"))
        bodies shouldHaveSize 1
        bodies.single().file shouldBe "com/example/Nesting.java"
    }

    @Test
    fun `missing member missing nested type and clinit resolve to MemberNotFound`() {
        findJavaBodies(shapes, ref("com.example.Overloads", "absent")) shouldBe
            JavaBodyResult.MemberNotFound
        findJavaBodies(shapes, ref("com.example.Overloads\$Absent", "add")) shouldBe
            JavaBodyResult.MemberNotFound
        findJavaBodies(shapes, ref("com.example.Overloads", "<clinit>")) shouldBe
            JavaBodyResult.MemberNotFound
    }

    @Test
    fun `missing file is NoSource and blank names never reach parsing`() {
        findJavaBodies(shapes, ref("com.example.Absent", "add")) shouldBe JavaBodyResult.NoSource
        // A blank binary name is unconstructible through the model factory,
        // so the guard is pinned at the seam instead of through a ref.
        loadJavaUnit(shapes, "") shouldBe JavaUnit.NoSource
        loadJavaUnit(shapes, "   ") shouldBe JavaUnit.NoSource
    }

    @Test
    fun `malformed source is a ParseError value never a throw`() {
        val root = MemorySourceRoot(mapOf("com/example/Broken.java" to "public class Broken { void oops("))
        val result = findJavaBodies(root, ref("com.example.Broken", "oops"))
        result.shouldBeInstanceOf<JavaBodyResult.ParseError>()
    }

    @Test
    fun `memory root only serves listed source kinds`() {
        val root = MemorySourceRoot(
            mapOf(
                "com/example/notes.txt" to "not source",
                "../evil.java" to "evil",
                "com/example/Ok.java" to "class Ok {}",
            ),
        )
        root.sourcePaths() shouldBe listOf("com/example/Ok.java")
        root.findSource("com.example.Ok") shouldBe "com/example/Ok.java"
    }

    @Test
    fun `kt-only root is NotJava and empty root is NoSource`() {
        findJavaBodies(shapes, ref("com.example.Only", "anything")) shouldBe JavaBodyResult.NotJava
        findJavaBodies(shapes, ref("com.example.Missing", "anything")) shouldBe JavaBodyResult.NoSource
    }

    @Test
    fun `record compact constructor answers init`() {
        val bodies = foundBodies(ref = ref("com.example.Point", "<init>"))
        bodies shouldHaveSize 1
        val body = bodies.single()
        body.kind shouldBe SourceBodyKind.CONSTRUCTOR
        body.text shouldContain "IllegalArgumentException"
        // The components are implicit in a compact constructor: no spelled list.
        body.parameterTypes shouldBe emptyList()
    }

    @Test
    fun `record compact constructor survives a parameterised init ref`() {
        val bodies = foundBodies(ref = ref("com.example.Point", "<init>", listOf("int", "int")))
        bodies shouldHaveSize 1
    }

    @Test
    fun `enum entry slices to its constant body`() {
        val bodies = foundBodies(ref = ref("com.example.Traffic", "RED"))
        bodies shouldHaveSize 1
        val body = bodies.single()
        body.kind shouldBe SourceBodyKind.ENUM_ENTRY
        body.text shouldContain "isStop"
    }

    @Test
    fun `enum constructor and field read like their class equivalents`() {
        foundBodies(ref = ref("com.example.Traffic", "<init>", listOf("int"))) shouldHaveSize 1
        // The inline enum declares no `seconds()` method (the ambiguity with
        // a same-named method is pinned on the real fixture in tier 2).
        val field = foundBodies(ref = ref("com.example.Traffic", "seconds")).single()
        field.kind shouldBe SourceBodyKind.FIELD
        field.text shouldBe "    private final int seconds;"
    }

    @Test
    fun `return type disambiguates same-erasure overloads`() {
        // `javac` would reject this pair (same erasure); JavaParser parses it
        // fine, which is exactly the bridge/covariant shape the return-type
        // narrowing exists for.
        val root = MemorySourceRoot(
            mapOf(
                "com/example/Bridges.java" to
                    """
                    package com.example;
                    public class Bridges {
                        public Number size() {
                            return 0;
                        }
                        public Integer size() {
                            return 0;
                        }
                        public Bridges(int a) {
                        }
                        public Bridges(String s) {
                        }
                    }
                    """.trimIndent(),
            ),
        )
        // Under-specified: both (exit-2 set upstream, never a guess).
        foundBodies(root, ref("com.example.Bridges", "size")) shouldHaveSize 2
        // Return-typed: the covariant one.
        val one = foundBodies(
            root,
            ref("com.example.Bridges", "size", emptyList(), "java.lang.Integer"),
        )
        one shouldHaveSize 1
        one.single().returnType shouldBe "Integer"
        // A return type matching neither still returns both, not nothing:
        // the ref may be erased where the source is generic.
        foundBodies(
            root,
            ref("com.example.Bridges", "size", emptyList(), "java.lang.String"),
        ) shouldHaveSize 2
        // Constructors ignore return types gracefully (they have none):
        // the arity/name match still decides.
        val ctor = foundBodies(
            root,
            ref("com.example.Bridges", "<init>", listOf("int"), "com.example.Bridges"),
        )
        ctor shouldHaveSize 1
        ctor.single().parameterTypes shouldBe listOf("int")
    }

    @Test
    fun `listJavaMembers covers records enums and parse errors`() {
        val point = listJavaMembers(shapes, "com.example.Point")
            .shouldBeInstanceOf<JavaMemberList.Listed>().members
        point shouldBe listOf(DeclaredSourceMember(SourceBodyKind.CONSTRUCTOR, "<init>", emptyList()))

        val traffic = listJavaMembers(shapes, "com.example.Traffic")
            .shouldBeInstanceOf<JavaMemberList.Listed>().members
        traffic.filter { it.kind == SourceBodyKind.ENUM_ENTRY }.map { it.name } shouldBe
            listOf("RED", "GREEN")
        traffic.map { it.name } shouldBe
            listOf("<init>", "seconds", "RED", "GREEN")

        val broken = MemorySourceRoot(mapOf("com/example/Broken.java" to "public class Broken { void oops("))
        listJavaMembers(broken, "com.example.Broken")
            .shouldBeInstanceOf<JavaMemberList.ParseError>()
    }

    @Test
    fun `listJavaMembers lists in file order with overloads and fields split`() {
        val members = listJavaMembers(shapes, "com.example.Overloads")
            .shouldBeInstanceOf<JavaMemberList.Listed>().members
        members.map { it.name } shouldBe
            listOf("add", "add", "<init>", "<init>", "seed", "count", "total")
        members.map { it.kind } shouldBe
            listOf(
                SourceBodyKind.METHOD,
                SourceBodyKind.METHOD,
                SourceBodyKind.CONSTRUCTOR,
                SourceBodyKind.CONSTRUCTOR,
                SourceBodyKind.FIELD,
                SourceBodyKind.FIELD,
                SourceBodyKind.FIELD,
            )
    }

    @Test
    fun `listJavaMembers names its absences`() {
        listJavaMembers(shapes, "com.example.Missing") shouldBe JavaMemberList.NoSource
        listJavaMembers(shapes, "com.example.Only") shouldBe JavaMemberList.NotJava
        listJavaMembers(shapes, "com.example.Overloads\$Absent") shouldBe JavaMemberList.TypeNotFound
    }

    @Test
    fun `type keys normalise annotations generics arrays and varargs`() {
        sourceTypeKey("@NonNull String") shouldBe "String"
        sourceTypeKey("java.util.List<String>") shouldBe "List"
        sourceTypeKey("String...") shouldBe "String"
        sourceTypeKey("int[]") shouldBe "int"
        typeKeyOf("java.lang.Object") shouldBe "Object"
        typeKeyOf("String[]") shouldBe "String"
        typeKeyOf("com.example.Outer\$Inner") shouldBe "Inner"
    }

    private fun arbMemberName(): Arb<String> = Arb.of(
        "add", "greet", "seed", "count", "isStop", "RED", "absent", "<init>", "<clinit>", "",
    )

    @Test
    fun `extraction never throws on hostile input`() = runBlocking<Unit> {
        // Binary names come from the model/ref-parser upstream (which rejects
        // empty `$` segments itself, T-065) — this property owns hostile
        // *source text* and hostile *member names*, both fully agent-reachable.
        val binaries = listOf(
            "com.example.Hostile",
            "Hostile",
            "com.example.Hostile\$Inner",
            "com.example.Absent",
        )
        checkAll(
            1000,
            Arb.string(0..400),
            Arb.of(binaries),
            arbMemberName(),
            Arb.int(0..3),
        ) { text, binary, name, arity ->
            val root = MemorySourceRoot(mapOf("com/example/Hostile.java" to text))
            val params = if (arity == 0 && text.length % 2 == 0) null else List(arity) { "int" }
            val outcome = try {
                findJavaBodies(root, ref(binary, name, params))
                "value"
            } catch (e: Exception) {
                "threw:${e.javaClass.simpleName}"
            }
            outcome shouldBe "value"
        }
    }

    @Test
    fun `found bodies are verbatim sub-slices and deterministic`() = runBlocking<Unit> {
        val typePool = listOf(
            "com.example.Overloads",
            "com.example.Nesting\$Inner",
            "com.example.Point",
            "com.example.Traffic",
            "com.example.Absent",
        )
        checkAll(1000, Arb.of(typePool), arbMemberName(), Arb.list(Arb.of("int", "java.lang.String"), 0..2)) {
            type, name, params ->
            val wanted = if ((type.hashCode() + name.hashCode()) % 3 == 0) null else params
            val first = findJavaBodies(shapes, ref(type, name, wanted))
            // Determinism across parses (D-007 promise, TESTING.md §6).
            first shouldBe findJavaBodies(shapes, ref(type, name, wanted))
            if (first is JavaBodyResult.Found) {
                val fileLines = shapes.openSource(first.bodies.first().file).use {
                    it.readBytes().toString(Charsets.UTF_8).split('\n')
                }
                first.bodies.forEach { body ->
                    (body.startLine >= 1) shouldBe true
                    (body.endLine >= body.startLine) shouldBe true
                    (body.endLine <= fileLines.size) shouldBe true
                    body.text shouldBe fileLines.subList(body.startLine - 1, body.endLine).joinToString("\n")
                }
            }
        }
    }
}
