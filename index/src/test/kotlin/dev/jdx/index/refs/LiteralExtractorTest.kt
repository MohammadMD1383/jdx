package dev.jdx.index.refs

import dev.jdx.index.asm.buildClass
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Tier-1 coverage for [LiteralExtractor] (issue #86): LDC strings, field
 * ConstantValue, empty/hostile degradation, Type-LDC ignored, first-wins line.
 */
class LiteralExtractorTest {

    @Test
    fun `extracts LDC strings and String ConstantValue fields`() {
        val bytes = buildClass("lit/Keys") {
            source("Keys.java")
            field(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "TITLE",
                "Ljava/lang/String;",
                null,
                "notification.missing.title",
            )
            method(Opcodes.ACC_PUBLIC, "notify", "()V", null, null) {
                line(10)
                ldc("notification.missing.title")
                code(Opcodes.POP to null, Opcodes.RETURN to null)
            }
        }
        val hits = LiteralExtractor.extract(bytes)
        hits.map { Triple(it.kind, it.fromMember, it.value) }.shouldContainExactly(
            Triple("const", "TITLE", "notification.missing.title"),
            Triple("ldc", "notify", "notification.missing.title"),
        )
        val ldc = hits.single { it.kind == "ldc" }
        ldc.line shouldBe 10
        ldc.sourceFile shouldBe "Keys.java"
    }

    @Test
    fun `ignores Type LDC empty strings and non-String ConstantValue`() {
        val bytes = buildClass("lit/Skip") {
            field(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "N", "I", null, 42)
            method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                ldc(Type.getType("Llit/Skip;"))
                ldc("")
                code(Opcodes.POP to null, Opcodes.POP to null, Opcodes.RETURN to null)
            }
        }
        LiteralExtractor.extract(bytes).shouldBeEmpty()
    }

    @Test
    fun `dedups repeated LDC of the same string keeping the first line`() {
        val bytes = buildClass("lit/Loop") {
            source("Loop.java")
            method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                line(3)
                ldc("same")
                line(7)
                ldc("same")
                code(Opcodes.POP to null, Opcodes.POP to null, Opcodes.RETURN to null)
            }
        }
        val hits = LiteralExtractor.extract(bytes)
        hits.size shouldBe 1
        hits.single().line shouldBe 3
    }

    @Test
    fun `hostile bytes degrade to empty never throw`() {
        LiteralExtractor.extract(byteArrayOf(1, 2, 3)).shouldBeEmpty()
        LiteralExtractor.extract(ByteArray(0)).shouldBeEmpty()
    }
}
