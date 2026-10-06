package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Lenient decompiled slicing: a single Vineflower token JavaParser rejects
 * (`import foo.1;`, `return this.1;`) must not hide members `jdx source`
 * serves fine unparsed. Strict stays honest for paired sources; only the
 * reconstruction path uses the lenient seam.
 */
@Tag("integration")
class JavaBodiesLenientTest {

    private fun ref(
        type: String,
        name: String,
        params: List<String>? = null,
    ): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
        parameterTypes = params?.map { typeNameFromBinaryName(it) },
        returnType = null,
    )

    private val cleanText: String = """
        package com.example;
        import java.util.List;
        public class Foo {
            public int other() {
                return 1;
            }
            public int getVisibilityPercent() {
                return 42;
            }
        }
        """.trimIndent()

    private val badImportText: String = """
        package com.example;
        import foo.1;
        public class Foo {
            public int other() {
                return 1;
            }
            public int getVisibilityPercent() {
                return 42;
            }
        }
        """.trimIndent()

    private val badBodyText: String = """
        package com.example;
        public class Foo {
            public int get() {
                return this.1;
            }
            public int other() {
                return 1;
            }
        }
        """.trimIndent()

    @Test
    fun `strict fails on bad import but lenient finds a distant member`() {
        val root = MemorySourceRoot(mapOf("com/example/Foo.java" to badImportText))
        findJavaBodies(root, ref("com.example.Foo", "other", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.ParseError>()
        val found = findJavaBodiesLenient(root, ref("com.example.Foo", "other", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.Found>()
        found.bodies shouldHaveSize 1
        found.bodies.single().text shouldContain "return 1;"
    }

    @Test
    fun `lenient slice is verbatim original with original line numbers`() {
        val cleanRoot = MemorySourceRoot(mapOf("com/example/Foo.java" to cleanText))
        val clean = findJavaBodies(cleanRoot, ref("com.example.Foo", "getVisibilityPercent", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.Found>().bodies.single()
        val badRoot = MemorySourceRoot(mapOf("com/example/Foo.java" to badImportText))
        val repaired = findJavaBodiesLenient(badRoot, ref("com.example.Foo", "getVisibilityPercent", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.Found>().bodies.single()
        // Same member, same lines (the bad import blanks to spaces, preserving breaks).
        repaired.startLine shouldBe clean.startLine
        repaired.endLine shouldBe clean.endLine
        repaired.text shouldBe clean.text
        repaired.text shouldContain "return 42;"
    }

    @Test
    fun `lenient finds a member whose own body holds the bad token showing original`() {
        val root = MemorySourceRoot(mapOf("com/example/Foo.java" to badBodyText))
        findJavaBodies(root, ref("com.example.Foo", "get", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.ParseError>()
        val found = findJavaBodiesLenient(root, ref("com.example.Foo", "get", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.Found>()
        found.bodies shouldHaveSize 1
        // Ground-truth bytes, not the blanked repair copy.
        found.bodies.single().text shouldContain "this.1"
    }

    @Test
    fun `lenient finds a healthy member beside a broken one`() {
        val root = MemorySourceRoot(mapOf("com/example/Foo.java" to badBodyText))
        val found = findJavaBodiesLenient(root, ref("com.example.Foo", "other", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.Found>()
        found.bodies shouldHaveSize 1
        found.bodies.single().text shouldContain "return 1;"
    }

    @Test
    fun `lenient never invents a member from a truncated file`() {
        val truncated = "package com.example; public class Cut { public int half("
        val root = MemorySourceRoot(mapOf("com/example/Cut.java" to truncated))
        findJavaBodies(root, ref("com.example.Cut", "half", listOf("int")))
            .shouldBeInstanceOf<JavaBodyResult.ParseError>()
        val lenient = findJavaBodiesLenient(root, ref("com.example.Cut", "half", listOf("int")))
        // Honest either way — ParseError or MemberNotFound — but never Found.
        (lenient is JavaBodyResult.ParseError || lenient is JavaBodyResult.MemberNotFound) shouldBe true
    }

    @Test
    fun `lenient stays a ParseError on binary garbage`() {
        val root = MemorySourceRoot(mapOf("com/example/Foo.java" to "\u0000\u0001\u0002 binary garbage"))
        findJavaBodiesLenient(root, ref("com.example.Foo", "other", emptyList()))
            .shouldBeInstanceOf<JavaBodyResult.ParseError>()
    }

    @Test
    fun `lenient is deterministic`() {
        val root = MemorySourceRoot(mapOf("com/example/Foo.java" to badImportText))
        val first = findJavaBodiesLenient(root, ref("com.example.Foo", "other", emptyList()))
        first shouldBe findJavaBodiesLenient(root, ref("com.example.Foo", "other", emptyList()))
    }

    @Test
    fun `injected bad imports still slice via lenient over generated members`() = runBlocking<Unit> {
        val names = listOf("alpha", "beta", "gamma", "getVisibilityPercent")
        checkAll(100, Arb.of(names)) { name ->
            val text = "package com.example;\nimport foo.1;\npublic class Foo {\n" +
                " public int $name() { return 1; }\n public int other() { return 2; }\n}\n"
            val root = MemorySourceRoot(mapOf("com/example/Foo.java" to text))
            val found = findJavaBodiesLenient(root, ref("com.example.Foo", name, emptyList()))
                .shouldBeInstanceOf<JavaBodyResult.Found>()
            found.bodies shouldHaveSize 1
            found.bodies.single().text shouldContain "return 1;"
        }
    }
}
