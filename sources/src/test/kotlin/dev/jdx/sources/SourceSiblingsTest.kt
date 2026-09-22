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
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Same-file top-level siblings (T-074, tier 1): `Tag`/`Matrix` live in
 * `Annos.java`, so the outer-file-only mapping resolved them to no source
 * file. Pure in-memory roots here — disk behaviour lives in the tier-2 suite.
 */
class SourceSiblingsTest {

    private fun ref(type: String, name: String): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
    )

    private fun annosRoot(): MemorySourceRoot = MemorySourceRoot(
        mapOf(
            "dev/jdx/fixtures/Annos.java" to
                """
                package dev.jdx.fixtures;
                @interface Tag {
                    String value();
                }
                @interface Matrix {
                    String[] names() default {};
                }
                public class Annos {
                    public void tagged() { }
                }
                class Helper {
                    public int answer() {
                        return 42;
                    }
                }
                """.trimIndent(),
        ),
    )

    @Test
    fun `sibling annotation resolves to the shared file`() {
        annosRoot().use { root ->
            findJavaSourcePath(root, "dev.jdx.fixtures.Tag") shouldBe "dev/jdx/fixtures/Annos.java"
            findJavaSourcePath(root, "dev.jdx.fixtures.Matrix") shouldBe "dev/jdx/fixtures/Annos.java"
            findJavaSourcePath(root, "dev.jdx.fixtures.Annos") shouldBe "dev/jdx/fixtures/Annos.java"
        }
    }

    @Test
    fun `sibling member bodies slice from the shared file`() {
        annosRoot().use { root ->
            val bodies = findJavaBodies(root, ref("dev.jdx.fixtures.Helper", "answer"))
                .shouldBeInstanceOf<JavaBodyResult.Found>().bodies
            bodies shouldHaveSize 1
            bodies.single().file shouldBe "dev/jdx/fixtures/Annos.java"
            bodies.single().text shouldContain "return 42;"
        }
    }

    @Test
    fun `sibling member listing names the shared file members`() {
        annosRoot().use { root ->
            val listed = listJavaMembers(root, "dev.jdx.fixtures.Matrix")
                .shouldBeInstanceOf<JavaMemberList.Listed>().members
            listed.map { it.name } shouldContainAll listOf("names")
        }
    }

    @Test
    fun `outer file still wins when present`() {
        MemorySourceRoot(
            mapOf(
                "com/example/Foo.java" to "package com.example; public class Foo { }",
                "com/example/Other.java" to "package com.example; class Foo { }",
            ),
        ).use { root ->
            // Direct outer hit returns without consulting the sibling scan.
            findJavaSourcePath(root, "com.example.Foo") shouldBe "com/example/Foo.java"
        }
    }

    @Test
    fun `sibling java wins over a kotlin direct hit`() {
        MemorySourceRoot(
            mapOf(
                "com/example/Holder.java" to
                    "package com.example; public class Holder { } class Wanted { }",
                "com/example/Wanted.kt" to "package com.example\nclass Wanted",
            ),
        ).use { root ->
            findJavaSourcePath(root, "com.example.Wanted") shouldBe "com/example/Holder.java"
        }
    }

    @Test
    fun `missing type stays missing`() {
        annosRoot().use { root ->
            findJavaSourcePath(root, "dev.jdx.fixtures.Missing") shouldBe null
            findJavaBodies(root, ref("dev.jdx.fixtures.Missing", "anything")) shouldBe
                JavaBodyResult.NoSource
        }
    }

    @Test
    fun `unparseable sibling candidate is skipped`() {
        MemorySourceRoot(
            mapOf(
                "com/example/Broken.java" to "package com.example; public class Broken { public int half(",
                "com/example/Good.java" to
                    "package com.example; public class Good { } class Wanted { }",
            ),
        ).use { root ->
            findJavaSourcePath(root, "com.example.Wanted") shouldBe "com/example/Good.java"
        }
    }

    @Test
    fun `kotlin-only type stays NotJava`() {
        MemorySourceRoot(
            mapOf("com/example/Only.kt" to "package com.example\nclass Only"),
        ).use { root ->
            findJavaBodies(root, ref("com.example.Only", "anything")) shouldBe JavaBodyResult.NotJava
            listJavaMembers(root, "com.example.Only") shouldBe JavaMemberList.NotJava
        }
    }

    @Test
    fun `nested sibling outer resolves through the shared file`() {
        MemorySourceRoot(
            mapOf(
                "com/example/Holder.java" to
                    """
                    package com.example;
                    public class Holder { }
                    class Outer {
                        class Inner { }
                    }
                    """.trimIndent(),
            ),
        ).use { root ->
            findJavaSourcePath(root, "com.example.Outer\$Inner") shouldBe "com/example/Holder.java"
        }
    }

    @Test
    fun `top level names list every declaration`() {
        topLevelTypeNamesOf(
            "package p; public class A { } class B { } @interface C { }",
        ) shouldBe listOf("A", "B", "C")
        topLevelTypeNamesOf("package p; public class Broken { public int half(") shouldBe null
    }

    // -- generating family (standing test bar) ---------------------------------

    private fun arbIdent(): Arb<String> =
        Arb.of(
            "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta",
            "Iota", "Kappa", "Lambda", "Mu",
        )

    @Test
    fun `sibling lookup never throws on hostile input`() = runBlocking<Unit> {
        checkAll(1000, Arb.string(0..48).filter { it.length <= 48 }) { raw ->
            val root = annosRoot()
            root.use {
                // Value is unchecked: the law is totality, not any one answer.
                findJavaSourcePath(it, raw)
                listJavaMembers(it, raw)
            }
        }
    }

    @Test
    fun `sibling lookup is deterministic across repeated opens`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..48).filter { it.length <= 48 }) { raw ->
            val first = annosRoot().use { findJavaSourcePath(it, raw) }
            val second = annosRoot().use { findJavaSourcePath(it, raw) }
            first shouldBe second
        }
    }

    @Test
    fun `every declared sibling resolves to its java file`() = runBlocking<Unit> {
        checkAll(200, Arb.list(arbIdent(), 1..4), Arb.list(arbIdent(), 1..3)) { holders, extraTypes ->
            val pkg = "gen.sib"
            val files = mutableMapOf<String, String>()
            val declared = mutableListOf<Pair<String, String>>()
            val names = (holders + extraTypes).distinct()
            // Pack all types into one shared file plus one lonely file.
            val sharedTypes = names.take(3)
            val sharedText = buildString {
                appendLine("package $pkg;")
                sharedTypes.forEachIndexed { i, name ->
                    if (i == 0) appendLine("public class $name { }") else appendLine("class $name { }")
                }
            }
            files["gen/sib/Shared.java"] = sharedText
            sharedTypes.forEach { declared.add("$pkg.$it" to "gen/sib/Shared.java") }
            val lonely = (names - sharedTypes.toSet()).firstOrNull()
            if (lonely != null) {
                files["gen/sib/Lonely.java"] = "package $pkg; public class $lonely { }"
                declared.add("$pkg.$lonely" to "gen/sib/Lonely.java")
            }
            MemorySourceRoot(files).use { root ->
                for ((binary, expected) in declared) {
                    findJavaSourcePath(root, binary) shouldBe expected
                }
                // Determinism on the generated root as well.
                for ((binary, _) in declared) {
                    findJavaSourcePath(root, binary) shouldBe findJavaSourcePath(root, binary)
                }
            }
        }
    }

    private infix fun List<String>.shouldContainAll(expected: List<String>) {
        expected.forEach { want -> this.contains(want) shouldBe true }
    }
}
