package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Annotation value-predicate helpers (issue #26): `--where key=value` parsing,
 * rendered-use spelling, and quote-tolerant value matching.
 */
class AnnotationMatchTest {

    private fun deprecated(vararg pairs: Pair<String, String>): AnnotationInfo =
        AnnotationInfo(typeNameFromBinaryName("java.lang.Deprecated"), pairs.toMap())

    @Test
    fun `parse requires key equals value`() {
        parseAnnotationWhere("forRemoval=true").getOrThrow() shouldBe
            AnnotationWhere("forRemoval", "true")
        parseAnnotationWhere("since=\"99\"").getOrThrow() shouldBe
            AnnotationWhere("since", "\"99\"")
        parseAnnotationWhere("noEquals").isFailure shouldBe true
        parseAnnotationWhere("=true").isFailure shouldBe true
        parseAnnotationWhere("noEquals").exceptionOrNull()!!.message shouldContain "key=value"
    }

    @Test
    fun `renderUse sorts element names`() {
        deprecated("since" to "\"99\"", "forRemoval" to "true").renderUse() shouldBe
            "@java.lang.Deprecated(forRemoval=true, since=\"99\")"
        deprecated().renderUse() shouldBe "@java.lang.Deprecated"
    }

    @Test
    fun `matchesWhere is AND over clauses and quote-tolerant`() {
        val annot = deprecated("forRemoval" to "true", "since" to "\"99\"")
        annot.matchesWhere(emptyList()) shouldBe true
        annot.matchesWhere(listOf(AnnotationWhere("forRemoval", "true"))) shouldBe true
        annot.matchesWhere(listOf(AnnotationWhere("since", "99"))) shouldBe true
        annot.matchesWhere(listOf(AnnotationWhere("since", "\"99\""))) shouldBe true
        annot.matchesWhere(
            listOf(
                AnnotationWhere("forRemoval", "true"),
                AnnotationWhere("since", "99"),
            ),
        ) shouldBe true
        annot.matchesWhere(listOf(AnnotationWhere("forRemoval", "false"))) shouldBe false
        annot.matchesWhere(listOf(AnnotationWhere("missing", "x"))) shouldBe false
        annot.matchesWhere(
            listOf(
                AnnotationWhere("forRemoval", "true"),
                AnnotationWhere("since", "1"),
            ),
        ) shouldBe false
    }

    @Test
    fun `peelAnnotationUseQuery strips at-sign and element parens`() {
        // Issue #26 shortcut: @Fqn(k=v) is the natural spelling for
        // "every use of this annotation with these values".
        peelAnnotationUseQuery("@java.lang.Deprecated")!!.getOrThrow() shouldBe
            AnnotationUseQuery("java.lang.Deprecated")
        peelAnnotationUseQuery("@Deprecated(forRemoval=true)")!!.getOrThrow() shouldBe
            AnnotationUseQuery(
                "Deprecated",
                listOf(AnnotationWhere("forRemoval", "true")),
            )
        peelAnnotationUseQuery(
            "java.lang.Deprecated(forRemoval=true, since=\"99\")",
        )!!.getOrThrow() shouldBe
            AnnotationUseQuery(
                "java.lang.Deprecated",
                listOf(
                    AnnotationWhere("forRemoval", "true"),
                    AnnotationWhere("since", "\"99\""),
                ),
            )
        // Arrays / nested annotations keep commas inside braces / parens.
        peelAnnotationUseQuery("@Mark(value={a, b}, other=@Inner(x=1))")!!.getOrThrow() shouldBe
            AnnotationUseQuery(
                "Mark",
                listOf(
                    AnnotationWhere("value", "{a, b}"),
                    AnnotationWhere("other", "@Inner(x=1)"),
                ),
            )
    }

    @Test
    fun `peelAnnotationUseQuery leaves ordinary refs alone`() {
        peelAnnotationUseQuery("java.lang.Deprecated") shouldBe null
        peelAnnotationUseQuery("com.google.gson.Gson#toJson(Object)") shouldBe null
        peelAnnotationUseQuery("com.example.Foo(Object)") shouldBe null
        peelAnnotationUseQuery("com.example.Foo(...)") shouldBe null
    }

    @Test
    fun `peelAnnotationUseQuery rejects malformed element lists`() {
        val bare = peelAnnotationUseQuery("@Deprecated(forRemoval)")!!
        bare.isFailure shouldBe true
        bare.exceptionOrNull()!!.message shouldContain "key=value"
        val unclosed = peelAnnotationUseQuery("@Deprecated(forRemoval=true")!!
        unclosed.isFailure shouldBe true
        unclosed.exceptionOrNull()!!.message shouldContain "closing ')'"
    }
}
