package dev.jdx.decompile

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure `javap -c -p -s` section math behind [JavapDecompiler] (T-027): output
 * splits into member sections, descriptors match members — never a throw, no
 * process, no disk.
 */
class JavapOutputTest {

    private val sample = """
        Compiled from "Generics.java"
        public class dev.jdx.fixtures.Generics<T, U> {
          public dev.jdx.fixtures.Generics();
            descriptor: ()V
            Code:
                 0: aload_0
                 1: invokespecial #1                  // Method java/lang/Object."<init>":()V
                 4: return

          public U identity(U);
            descriptor: (Ljava/lang/Object;)Ljava/lang/Object;
            Code:
                 0: aload_1
                 1: areturn

          private final int count;
            descriptor: I
        }
    """.trimIndent()

    @Test
    fun `sections split on blank lines with descriptor and range`() {
        val sections = splitJavapSections(sample)
        sections.map { it.declaration } shouldBe listOf(
            "public dev.jdx.fixtures.Generics();",
            "public U identity(U);",
            "private final int count;",
        )
        sections.map { it.descriptor } shouldBe listOf(
            "()V",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            "I",
        )
        sections[0].startLine shouldBe 3
        sections[0].endLine shouldBe 8
        sections[1].lines shouldContain "    descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
    }

    @Test
    fun `field descriptors ending in semicolons do not start sections`() {
        // `descriptor: Lpkg/Foo;` ends with `;` — only block starts count.
        val sections = splitJavapSections(sample)
        sections.size shouldBe 3
    }

    @Test
    fun `constructors read as init, fields by name`() {
        javapMemberName("public dev.jdx.fixtures.Generics();") shouldBe "<init>"
        javapMemberName("public U identity(U);") shouldBe "identity"
        javapMemberName("private final int count;") shouldBe "count"
        javapMemberName("static {};") shouldBe "<clinit>"
    }

    @Test
    fun `descriptor equality finds the member`() {
        val sections = splitJavapSections(sample)
        findJavapSection(sections, "identity", "(Ljava/lang/Object;)Ljava/lang/Object;")
            ?.declaration shouldBe "public U identity(U);"
        findJavapSection(sections, "<init>", "()V")
            ?.declaration shouldBe "public dev.jdx.fixtures.Generics();"
        findJavapSection(sections, "count", "I")
            ?.declaration shouldBe "private final int count;"
        findJavapSection(sections, "missing", "(I)V") shouldBe null
    }

    @Test
    fun `empty and header-only outputs have no sections`() {
        splitJavapSections("").shouldBeEmpty()
        splitJavapSections("Compiled from \"A.java\"\npublic class A {\n}\n").shouldBeEmpty()
        findJavapSection(emptyList(), "m", "()V") shouldBe null
    }

    // -- generative ------------------------------------------------------------

    /** One synthesised member rendered the way `javap -s` prints it. */
    private data class FakeMember(val name: String, val descriptor: String, val isCtor: Boolean)

    private fun renderClass(members: List<FakeMember>): String = buildString {
        appendLine("Compiled from \"Synth.java\"")
        appendLine("public class pkg.Synth {")
        for (member in members) {
            appendLine()
            if (member.isCtor) {
                appendLine("  public pkg.Synth();")
            } else {
                appendLine("  public java.lang.Object ${member.name}(int);")
            }
            appendLine("    descriptor: ${member.descriptor}")
            appendLine("    Code:")
            appendLine("         0: return")
        }
        appendLine("}")
    }

    @Test
    fun `every synthesised member is found with its descriptor`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.int(0, 999), 1..6)) { seeds ->
            // Names and descriptors carry the index, so every member is
            // unique; every fifth member renders as a constructor.
            val members = seeds.mapIndexed { i, s ->
                FakeMember("m${s}_$i", "(I)Lpkg/Foo${s}_$i;", s % 5 == 0)
            }
            val sections = splitJavapSections(renderClass(members))
            sections.size shouldBe members.size
            for (member in members) {
                val query = if (member.isCtor) "<init>" else member.name
                val found = findJavapSection(sections, query, member.descriptor)
                    ?: throw AssertionError("missing section for $member")
                found.descriptor shouldBe member.descriptor
            }
        }
    }

    @Test
    fun `splitting never throws and sections stay inside the output`() = runBlocking<Unit> {
        checkAll(1_000, Arb.string(0, 200)) { text ->
            val sections = splitJavapSections(text)
            val lines = text.split('\n')
            for (section in sections) {
                (section.startLine >= 1) shouldBe true
                (section.endLine >= section.startLine) shouldBe true
                (section.endLine <= lines.size) shouldBe true
                section.lines shouldBe lines.subList(section.startLine - 1, section.endLine)
                    .map { it.removeSuffix("\r") }
            }
            splitJavapSections(text) shouldBe sections
        }
    }
}
