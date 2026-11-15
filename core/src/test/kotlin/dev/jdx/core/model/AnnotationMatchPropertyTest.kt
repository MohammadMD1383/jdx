package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative laws for annotation where-matching (issue #26): a clause built
 * from a rendered value always matches that annotation, and renderUse is
 * deterministic under key-order shuffle.
 */
class AnnotationMatchPropertyTest {

    private fun arbKey(): Arb<String> = Arb.string(1..8, Codepoint.az())

    private fun arbRenderedValue(): Arb<String> = Arb.bind(Arb.boolean(), Arb.string(0..6, Codepoint.az()), Arb.int(0..999)) {
            quoted, text, number ->
        if (quoted) "\"$text\"" else number.toString()
    }

    private fun arbPairs(): Arb<Map<String, String>> =
        Arb.bind(Arb.list(arbKey(), 0..5), Arb.list(arbRenderedValue(), 0..5)) { keys, values ->
            keys.zip(values).toMap()
        }

    @Test
    fun `clause taken from rendered values always matches`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbPairs()) { pairs ->
            if (pairs.isEmpty()) return@checkAll
            val annot = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"), pairs)
            val clauses = pairs.entries.map { (key, value) -> AnnotationWhere(key, value) }
            assert(annot.matchesWhere(clauses)) {
                "self-derived clauses must match: $pairs"
            }
            val unquoted = pairs.entries.map { (key, value) ->
                val wanted = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value.substring(1, value.length - 1)
                } else {
                    value
                }
                AnnotationWhere(key, wanted)
            }
            assert(annot.matchesWhere(unquoted)) {
                "unquoted clauses must match quoted stored values: $pairs"
            }
        }
    }

    @Test
    fun `renderUse is deterministic under key shuffle`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbPairs()) { pairs ->
            val a = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"), pairs)
            val shuffled = AnnotationInfo(
                typeNameFromBinaryName("com.example.Mark"),
                pairs.entries.shuffled().associate { it.key to it.value },
            )
            assert(a.renderUse() == shuffled.renderUse()) {
                "renderUse must sort keys: ${a.renderUse()} vs ${shuffled.renderUse()}"
            }
        }
    }

    @Test
    fun `peel of renderUse round-trips type and clauses`(): Unit = runBlocking {
        // Issue #26 shortcut: the spelling usages prints on each row must peel
        // back into a type ref + where clauses that still match.
        checkAll(JDX_PROPERTY_ITERATIONS, arbPairs()) { pairs ->
            val annot = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"), pairs)
            val peeled = peelAnnotationUseQuery(annot.renderUse())
            assert(peeled != null) { "renderUse must look like annotation-use spelling" }
            val query = peeled!!.getOrThrow()
            assert(query.typeRef == "com.example.Mark") {
                "peeled typeRef: ${query.typeRef}"
            }
            assert(annot.matchesWhere(query.where)) {
                "peeled where must match rendered values: ${query.where} vs $pairs"
            }
        }
    }

    @Test
    fun `recipe lookup is case-insensitive and stable`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.of(ANNOTATION_RECIPE_NAMES)) { name ->
            val lower = lookupAnnotationRecipe(name.lowercase())
            val upper = lookupAnnotationRecipe(name.uppercase())
            val atPrefixed = lookupAnnotationRecipe("@$name")
            assert(lower != null && lower == upper && lower == atPrefixed) {
                "recipe '$name' must match lower/upper/@ forms"
            }
            val resolved = resolveAnnotatedQuery(name)
            resolved.query shouldBe lower!!.typeRef
            resolved.recipeWhere shouldBe lower.where
            // FQN form of the same type is never a recipe.
            lookupAnnotationRecipe(lower.typeRef) shouldBe null
        }
    }

    @Test
    fun `nested path derived from rendered nested value always matches`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbKey(), arbPairs()) { outerKey, innerPairs ->
            if (innerPairs.isEmpty()) return@checkAll
            val inner = AnnotationInfo(typeNameFromBinaryName("com.example.Inner"), innerPairs)
            val outer = AnnotationInfo(
                typeNameFromBinaryName("com.example.Outer"),
                mapOf(outerKey to inner.renderUse()),
            )
            for ((key, value) in innerPairs) {
                val path = AnnotationWhere("$outerKey.$key", value)
                assert(outer.matchesWhere(listOf(path))) {
                    "nested path $path must match rendered ${inner.renderUse()}"
                }
            }
        }
    }

    @Test
    fun `array clause matches under element shuffle`(): Unit = runBlocking {
        val arbElement = Arb.bind(Arb.boolean(), Arb.string(1..4, Codepoint.az()), Arb.int(0..99)) {
                quoted, text, number ->
            if (quoted) "\"$text\"" else number.toString()
        }
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbElement, 1..5)) { elements ->
            val stored = elements.joinToString(", ", "{", "}")
            val shuffled = elements.shuffled().joinToString(", ", "{", "}")
            val annot = AnnotationInfo(
                typeNameFromBinaryName("com.example.Mark"),
                mapOf("tags" to stored),
            )
            assert(annot.matchesWhere(listOf(AnnotationWhere("tags", shuffled)))) {
                "array multiset must ignore order: stored=$stored wanted=$shuffled"
            }
        }
    }

    @Test
    fun `defaults fill omitted keys and never override explicit values`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbKey(), arbRenderedValue(), arbRenderedValue()) {
                key, explicit, defaulted ->
            if (explicit == defaulted) return@checkAll
            val withValue = AnnotationInfo(
                typeNameFromBinaryName("com.example.Mark"),
                mapOf(key to explicit),
            )
            val bare = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"))
            val defaults = mapOf(key to defaulted)
            assert(bare.matchesWhere(listOf(AnnotationWhere(key, defaulted)), defaults)) {
                "bare use must match via default: key=$key default=$defaulted"
            }
            assert(!bare.matchesWhere(listOf(AnnotationWhere(key, explicit)), defaults)) {
                "bare use must not match a non-default wanted value"
            }
            assert(withValue.matchesWhere(listOf(AnnotationWhere(key, explicit)), defaults)) {
                "explicit value must still match under defaults"
            }
            assert(!withValue.matchesWhere(listOf(AnnotationWhere(key, defaulted)), defaults)) {
                "explicit value must win over a conflicting default"
            }
            assert(bare.renderUse() == "@com.example.Mark") {
                "renderUse must not invent defaults into the printed use"
            }
        }
    }

    @Test
    fun `structured AnnotationValue render round-trips into matchesWhere`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbKey(), arbPairs()) { outerKey, innerPairs ->
            if (innerPairs.isEmpty()) return@checkAll
            val nested = AnnotationValue.Nested(
                typeNameFromBinaryName("com.example.Inner"),
                innerPairs.mapValues { AnnotationValue.Scalar(it.value) },
            )
            val arr = AnnotationValue.Arr(
                innerPairs.values.map { AnnotationValue.Scalar(it) },
            )
            val annot = annotationInfo(
                typeNameFromBinaryName("com.example.Outer"),
                mapOf(outerKey to nested, "tags" to arr),
            )
            assert(annot.values[outerKey] == nested.render()) {
                "values map must derive from AST render"
            }
            for ((key, value) in innerPairs) {
                assert(annot.matchesWhere(listOf(AnnotationWhere("$outerKey.$key", value)))) {
                    "AST nested path $outerKey.$key must match"
                }
            }
            val shuffled = innerPairs.values.shuffled().joinToString(", ", "{", "}")
            assert(annot.matchesWhere(listOf(AnnotationWhere("tags", shuffled)))) {
                "AST array multiset must ignore order"
            }
        }
    }
}
