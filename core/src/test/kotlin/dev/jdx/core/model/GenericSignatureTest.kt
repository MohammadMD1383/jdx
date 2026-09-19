package dev.jdx.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [GenericSignature] — Java generic signatures, JVMS §4.7.9.1.
 * Round-trip is the acceptance criterion: signature → parsed → printed is a fixed point.
 */
class GenericSignatureTest {

    /** For every well-formed signature, parse then print must reproduce the input exactly. */
    private fun assertRoundTrips(text: String) {
        val parsed = GenericSignature.parse(text)
        parsed.shouldNotBeNull { "expected '$text' to parse" }
        parsed.signature shouldBe text
    }

    //
    // Field signatures — reference types only (JVMS §4.7.9.1: FieldSignature).
    //

    @Test
    fun `a plain class type field signature round-trips`() = assertRoundTrips("Ljava/lang/String;")

    @Test
    fun `a type variable field signature round-trips`() = assertRoundTrips("TT;")

    @Test
    fun `array field signatures round-trips`() {
        assertRoundTrips("[Ljava/lang/String;")
        assertRoundTrips("[[TT;")
        assertRoundTrips("[I")
    }

    @Test
    fun `a parameterised field signature round-trips`() =
        assertRoundTrips("Ljava/util/List<Ljava/lang/String;>;")

    @Test
    fun `all three wildcard kinds round-trip`() {
        assertRoundTrips("Ljava/util/List<*>;")
        assertRoundTrips("Ljava/util/List<+Ljava/lang/Number;>;")
        assertRoundTrips("Ljava/util/List<-Ljava/lang/Number;>;")
    }

    @Test
    fun `nested generics round-trip`() =
        assertRoundTrips("Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/Integer;>;>;")

    @Test
    fun `an inner class suffix with its own type arguments round-trips`() =
        assertRoundTrips("Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>.Entry<TKey;TValue;>;")

    @Test
    fun `an inner class suffix without type arguments round-trips`() =
        assertRoundTrips("Ljava/util/Map<TKey;TValue;>.Entry;")

    //
    // Class signatures — optional type parameters, a superclass, then interfaces.
    //

    @Test
    fun `a class signature without type parameters round-trips`() =
        assertRoundTrips("Ljava/lang/Object;Ljava/util/List<Ljava/lang/String;>;")

    @Test
    fun `a class signature with type parameters round-trips`() =
        assertRoundTrips("<T:Ljava/lang/Object;>Ljava/lang/Object;")

    @Test
    fun `a class signature with several parameters and superinterfaces round-trips`() =
        assertRoundTrips(
            "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/util/AbstractMap<TK;TV;>;Ljava/util/Map<TK;TV;>;",
        )

    @Test
    fun `a type parameter with an empty class bound and interface bounds round-trips`() =
        assertRoundTrips("<T::Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;")

    @Test
    fun `a recursive bound round-trips`() =
        assertRoundTrips("<E:Ljava/lang/Enum<TE;>;>Ljava/lang/Object;")

    //
    // Method signatures — type parameters, parameters, return type, throws.
    //

    @Test
    fun `a generic method signature round-trips`() =
        assertRoundTrips("<T:Ljava/lang/Object;>(TT;)TT;")

    @Test
    fun `a method signature with mixed parameter kinds round-trips`() =
        assertRoundTrips("(Ljava/util/List<TT;>;IZ[Ljava/lang/Object;)V")

    @Test
    fun `a method signature with throws clauses round-trips`() =
        assertRoundTrips("<E:Ljava/lang/Object;>(I)V^TE;^Ljava/lang/Exception;")

    @Test
    fun `a method signature returning a nested generic type round-trips`() =
        assertRoundTrips("<T:Ljava/lang/Object;>([TT;)Ljava/util/List<TT;>;")

    //
    // Structure — a few targeted assertions so the tests pin the model, not just the text.
    //

    @Test
    fun `parsing a wildcard yields the unbounded type argument`() {
        val field = GenericSignature.parse("Ljava/util/List<*>;") as FieldSignature
        val list = field.type as ClassTypeSignature
        list.simpleName shouldBe "List"
        list.packageName shouldBe "java.util"
        list.typeArguments.single() shouldBe TypeArgument.Unbounded
    }

    @Test
    fun `parsing an upper bound keeps the bound signature`() {
        val field = GenericSignature.parse("Ljava/util/List<+Ljava/lang/Number;>;") as FieldSignature
        val list = field.type as ClassTypeSignature
        val argument = list.typeArguments.single() as TypeArgument.UpperBounded
        (argument.bound as ClassTypeSignature).simpleName shouldBe "Number"
    }

    @Test
    fun `a type parameter exposes its class bound when present`() {
        // `FormalTypeParameter.classBound` is null for `T extends Object`-style
        // defaults and non-null otherwise — both directions are asserted.
        val withBound = GenericSignature.parseClass("<T:Ljava/lang/Number;>Ljava/lang/Object;")
            ?.typeParameters?.single()
        withBound?.classBound?.signature shouldBe "Ljava/lang/Number;"
        val withoutBound = GenericSignature.parseClass("<T:Ljava/lang/Object;>Ljava/lang/Object;")
            ?.typeParameters?.single()
        withoutBound?.classBound?.signature shouldBe "Ljava/lang/Object;"
    }

    @Test
    fun `parsing an inner class suffix records it as an inner class with its own arguments`() {
        val field =
            GenericSignature.parse("Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>.Entry<TKey;TValue;>;")
                as FieldSignature
        val map = field.type as ClassTypeSignature
        map.innerClasses.single().let { inner ->
            inner.simpleName shouldBe "Entry"
            inner.typeArguments.size shouldBe 2
        }
    }

    @Test
    fun `parsing a class signature splits superclass from superinterfaces`() {
        val signature =
            GenericSignature.parse("<T:Ljava/lang/Object;>Ljava/util/AbstractSet<TT;>;Ljava/util/Set<TT;>;")
                as ClassSignature
        signature.typeParameters.single().name shouldBe "T"
        signature.superclass.simpleName shouldBe "AbstractSet"
        signature.superinterfaces.single().simpleName shouldBe "Set"
    }

    @Test
    fun `a type parameter with an empty class bound models it as null`() {
        val signature =
            GenericSignature.parse("<T::Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;") as ClassSignature
        val parameter = signature.typeParameters.single()
        parameter.classBound.shouldBeNull()
        parameter.interfaceBounds.single().let { it as ClassTypeSignature; it.simpleName shouldBe "Comparable" }
    }

    @Test
    fun `a void method return is represented explicitly`() {
        val signature = GenericSignature.parse("(I)V") as MethodSignature
        signature.returnType shouldBe VoidSignature
    }

    //
    // Malformed input — never throws, always null (errors are values, CONTRIBUTING.md).
    //

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",                          // empty
            "<T",                        // unterminated type parameters
            "Lfoo<;>",                   // empty type-argument list
            "Ljava/lang/String;x",       // trailing garbage
            "(I",                        // unclosed parameter list
            "()Q",                       // bad return type
            "<T:Ljava/lang/Object;>()",  // method signature missing its return type
            "T",                         // type variable without terminator
            "Ljava/util/List<*>",        // parameterised type without terminator
            "^TE;",                      // throws clause outside a method signature
        ],
    )
    fun `malformed signatures parse to null rather than throwing`(text: String) {
        GenericSignature.parse(text).shouldBeNull()
    }

    //
    // parseClass — the class-file `Signature` attribute of a class is a ClassSignature by
    // construction (JVMS §4.7.9.1), even when it is a lone superclass with no interfaces
    // and no type parameters — exactly the shape `parse` reads as a field (T-009).
    //

    @Test
    fun `parseClass reads a superclass-only signature as a class signature`() {
        val parsed = GenericSignature.parseClass("Lt/ArrayList<Ljava/lang/String;>;")
        parsed.shouldNotBeNull { "expected a class signature" }
        parsed.signature shouldBe "Lt/ArrayList<Ljava/lang/String;>;"
        parsed.superclass.simpleName shouldBe "ArrayList"
        parsed.superinterfaces shouldBe emptyList()
        val argument = parsed.superclass.typeArguments.single() as TypeArgument.Exact
        (argument.type as ClassTypeSignature).simpleName shouldBe "String"
    }

    @Test
    fun `parseClass reads a plain superclass with no interfaces`() {
        val parsed = GenericSignature.parseClass("Ljava/lang/Object;")
        parsed.shouldNotBeNull { "expected a class signature" }
        parsed.superclass.simpleName shouldBe "Object"
        parsed.superinterfaces shouldBe emptyList()
    }

    @Test
    fun `parse prefers the field reading for a lone class type`() {
        // Pinned: this ambiguity is why parseClass exists — callers that know the context
        // is a class file must not use the generic entry point.
        (GenericSignature.parse("Lt/ArrayList<Ljava/lang/String;>;") is FieldSignature) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "(I)V",
            "Ljava/lang/Object",
            "Ljava/lang/Object;x",
        ],
    )
    fun `parseClass returns null for non-class signatures`(text: String) {
        GenericSignature.parseClass(text).shouldBeNull()
    }
}
