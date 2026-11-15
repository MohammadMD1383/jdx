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

    @Test
    fun `recipes expand bare aliases for annotated`() {
        // Issue #26 dedicated command: short aliases → well-known types.
        lookupAnnotationRecipe("deprecated") shouldBe
            AnnotationUseQuery("java.lang.Deprecated")
        lookupAnnotationRecipe("@forRemoval") shouldBe
            AnnotationUseQuery(
                "java.lang.Deprecated",
                listOf(AnnotationWhere("forRemoval", "true")),
            )
        lookupAnnotationRecipe("TEST") shouldBe
            AnnotationUseQuery("org.junit.jupiter.api.Test")
        lookupAnnotationRecipe("junit5") shouldBe
            AnnotationUseQuery("org.junit.jupiter.api.Test")
        lookupAnnotationRecipe("junit4") shouldBe
            AnnotationUseQuery("org.junit.Test")
        lookupAnnotationRecipe("bean") shouldBe
            AnnotationUseQuery("org.springframework.context.annotation.Bean")
        // FQNs / use-spelling / member refs are never recipes.
        lookupAnnotationRecipe("java.lang.Deprecated") shouldBe null
        lookupAnnotationRecipe("Deprecated(forRemoval=true)") shouldBe null
        lookupAnnotationRecipe("Gson#toJson") shouldBe null
    }

    @Test
    fun `resolveAnnotatedQuery prefers recipes then leaves peelables alone`() {
        resolveAnnotatedQuery("forRemoval") shouldBe AnnotatedQuery(
            query = "java.lang.Deprecated",
            recipeWhere = listOf(AnnotationWhere("forRemoval", "true")),
            recipe = "forRemoval",
        )
        resolveAnnotatedQuery("@deprecated") shouldBe AnnotatedQuery(
            query = "java.lang.Deprecated",
            recipe = "deprecated",
        )
        // Annotation-use spelling stays for usages --kind annotation to peel.
        resolveAnnotatedQuery("Deprecated(forRemoval=true)") shouldBe
            AnnotatedQuery("Deprecated(forRemoval=true)")
        resolveAnnotatedQuery("java.lang.Deprecated") shouldBe
            AnnotatedQuery("java.lang.Deprecated")
    }

    @Test
    fun `recipe names list is the public help vocabulary`() {
        ANNOTATION_RECIPE_NAMES shouldBe listOf(
            "deprecated", "forRemoval", "test", "junit5", "junit4", "bean",
        )
        for (name in ANNOTATION_RECIPE_NAMES) {
            (lookupAnnotationRecipe(name) != null) shouldBe true
        }
    }
}
