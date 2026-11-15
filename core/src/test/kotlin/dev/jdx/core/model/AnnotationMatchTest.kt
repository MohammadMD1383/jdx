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
}
