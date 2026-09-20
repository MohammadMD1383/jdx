package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure javadoc extraction (T-025, tier 1): inline sources over an in-memory
 * [SourceRoot], so no disk is touched. Disk behaviour (real `-sources.jar`
 * reads, crafted hostile jars) lives in the tier-2 suite; generating
 * coverage lives in the properties below.
 */
class JavaDocsTest {

    private fun ref(
        type: String,
        name: String,
        params: List<String>? = null,
    ): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
        parameterTypes = params?.map { typeNameFromBinaryName(it) },
    )

    private val shapes = MemorySourceRoot(
        mapOf(
            "com/example/Docs.java" to
                """
                package com.example;
                /** The main class: does things. */
                public class Docs {
                    /** Adds two numbers.
                     * @param a the first
                     * @param b the second
                     * @return the sum
                     */
                    public int add(int a, int b) {
                        return a + b;
                    }
                    public int undocumented(int a) {
                        return a;
                    }
                    /** The seed field. */
                    private int seed;
                    /**
                     * Builds a Docs.
                     * @param seed the seed
                     */
                    public Docs(int seed) {
                        this.seed = seed;
                    }
                    /** First. */
                    private int first;
                    /** Second. */
                    private int second, third;
                }
                """.trimIndent(),
            "com/example/Plain.java" to
                """
                package com.example;
                public class Plain {
                    public void bare() {
                    }
                }
                """.trimIndent(),
            "com/example/Traffic.java" to
                """
                package com.example;
                /** Signals. */
                public enum Traffic {
                    /** Stop now. */
                    RED,
                    YELLOW;
                    /** Waits. */
                    public void waitForIt() {
                    }
                }
                """.trimIndent(),
            "com/example/Outer.java" to
                """
                package com.example;
                public class Outer {
                    /** Inner things. */
                    public static class Inner {
                        /** Deep method. */
                        public void deep() {
                        }
                    }
                }
                """.trimIndent(),
            "com/example/Empty.java" to
                """
                package com.example;
                public class Empty {
                    /**  */
                    public void blank() {
                    }
                }
                """.trimIndent(),
        ),
    )

    @Test
    fun `type doc carries the raw comment and the file`() {
        val result = findTypeDoc(shapes, "com.example.Docs")
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        result.docs shouldHaveSize 1
        val doc = result.docs.single()
        doc.kind shouldBe SourceDocKind.TYPE
        doc.file shouldBe "com/example/Docs.java"
        doc.rawComment shouldContain "The main class"
        (doc.startLine >= 1 && doc.endLine >= doc.startLine) shouldBe true
    }

    @Test
    fun `method doc carries params and return tags raw`() {
        val result = findMemberDocs(shapes, ref("com.example.Docs", "add", listOf("int", "int")))
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        val doc = result.docs.single()
        doc.kind shouldBe SourceDocKind.METHOD
        doc.rawComment shouldContain "Adds two numbers"
        doc.rawComment shouldContain "@param a the first"
        doc.rawComment shouldContain "@return the sum"
    }

    @Test
    fun `field doc matches by variable name`() {
        val result = findMemberDocs(shapes, ref("com.example.Docs", "seed"))
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        result.docs.single().rawComment shouldContain "The seed field"
    }

    @Test
    fun `multi-declarator fields resolve per variable`() {
        findMemberDocs(shapes, ref("com.example.Docs", "first"))
            .shouldBeInstanceOf<JavaDocResult.Found>()
        val second = findMemberDocs(shapes, ref("com.example.Docs", "second"))
        second.shouldBeInstanceOf<JavaDocResult.Found>()
        // `second` and `third` share one declaration — and its one comment.
        second.docs.single().rawComment shouldContain "Second."
        val third = findMemberDocs(shapes, ref("com.example.Docs", "third"))
        third.shouldBeInstanceOf<JavaDocResult.Found>()
        third.docs.single().rawComment shouldContain "Second."
    }

    @Test
    fun `init matches documented constructors`() {
        val result = findMemberDocs(shapes, ref("com.example.Docs", "<init>", listOf("int")))
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        result.docs.single().kind shouldBe SourceDocKind.CONSTRUCTOR
        result.docs.single().rawComment shouldContain "Builds a Docs"
    }

    @Test
    fun `enum entry docs resolve`() {
        val result = findMemberDocs(shapes, ref("com.example.Traffic", "RED"))
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        result.docs.single().kind shouldBe SourceDocKind.ENUM_ENTRY
        result.docs.single().rawComment shouldContain "Stop now"
    }

    @Test
    fun `nested type member docs resolve through dollar nesting`() {
        val result = findMemberDocs(shapes, ref("com.example.Outer\$Inner", "deep", listOf()))
        result.shouldBeInstanceOf<JavaDocResult.Found>()
        result.docs.single().rawComment shouldContain "Deep method"
    }

    @Test
    fun `undocumented member is member-not-found so callers fall back to supertypes`() {
        findMemberDocs(shapes, ref("com.example.Docs", "undocumented", listOf("int")))
            .shouldBeInstanceOf<JavaDocResult.MemberNotFound>()
    }

    @Test
    fun `undocumented type is type-undocumented`() {
        findTypeDoc(shapes, "com.example.Plain").shouldBeInstanceOf<JavaDocResult.TypeUndocumented>()
    }

    @Test
    fun `absent nested type is type-not-found`() {
        findTypeDoc(shapes, "com.example.Outer\$Missing").shouldBeInstanceOf<JavaDocResult.TypeNotFound>()
    }

    @Test
    fun `blank javadoc counts as undocumented`() {
        findMemberDocs(shapes, ref("com.example.Empty", "blank", listOf()))
            .shouldBeInstanceOf<JavaDocResult.MemberNotFound>()
    }

    @Test
    fun `missing file is no-source`() {
        findTypeDoc(shapes, "com.example.Missing").shouldBeInstanceOf<JavaDocResult.NoSource>()
        findMemberDocs(shapes, ref("com.example.Missing", "m", listOf()))
            .shouldBeInstanceOf<JavaDocResult.NoSource>()
    }

    @Test
    fun `kotlin-only root is not-java`() {
        val root = MemorySourceRoot(mapOf("com/example/Only.kt" to "fun f(): Int = 1\n"))
        findTypeDoc(root, "com.example.Only").shouldBeInstanceOf<JavaDocResult.NotJava>()
        findMemberDocs(root, ref("com.example.Only", "f", listOf()))
            .shouldBeInstanceOf<JavaDocResult.NotJava>()
    }

    @Test
    fun `truncated source is a parse error, never a throw`() {
        val root = MemorySourceRoot(mapOf("com/example/Broken.java" to "package com.example; public class Broken { public void m("))
        findTypeDoc(root, "com.example.Broken").shouldBeInstanceOf<JavaDocResult.ParseError>()
        findMemberDocs(root, ref("com.example.Broken", "m", listOf()))
            .shouldBeInstanceOf<JavaDocResult.ParseError>()
    }

    @Test
    fun `extraction never throws on hostile input`() = runBlocking<Unit> {
        // Binary names come from the model/ref-parser upstream (which rejects
        // empty `$` segments itself, T-065) — this property owns hostile
        // *source text* over fixed valid names, both fully agent-reachable.
        val binaries = listOf(
            "com.example.Hostile",
            "Hostile",
            "com.example.Hostile\$Inner",
            "com.example.Absent",
        )
        val names = listOf("add", "seed", "RED", "absent", "<init>", "<clinit>", "")
        checkAll(1_000, Arb.string(0..400), Arb.of(binaries), Arb.of(names)) { text, binary, name ->
            val root = MemorySourceRoot(mapOf("com/example/Hostile.java" to text))
            findTypeDoc(root, binary)
            findMemberDocs(root, ref(binary, name))
        }
    }

    @Test
    fun `extraction is deterministic`() = runBlocking<Unit> {
        checkAll(100, Arb.string(0..200)) { text ->
            val root = MemorySourceRoot(mapOf("com/example/Hostile.java" to text))
            findTypeDoc(root, "com.example.Hostile") shouldBe findTypeDoc(root, "com.example.Hostile")
        }
    }
}
