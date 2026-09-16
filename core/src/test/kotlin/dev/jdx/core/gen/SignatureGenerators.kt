package dev.jdx.core.gen

import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.BaseTypeSignature
import dev.jdx.core.model.ClassSignature
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.FormalTypeParameter
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.InnerClassType
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.ReferenceTypeSignature
import dev.jdx.core.model.ThrowsSignature
import dev.jdx.core.model.TypeArgument
import dev.jdx.core.model.TypeSignature
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.VoidSignature
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of

/**
 * Shared generators for generic signatures, JVMS §4.7.9.1 (T-055; TESTING.md §4).
 *
 * Every shape the grammar allows appears with real weight: nested generics
 * (`Map<String, List<Integer>>`), all three wildcard kinds plus exact arguments, type
 * variables (uses and declarations, including the empty-class-bound `<T::Bound;>` form),
 * arrays of every depth, inner classes with their own arguments, `void` returns, and
 * `throws` clauses. Recursion bottoms out at `maxDepth` so generation always terminates;
 * depth 2 already yields three-level nesting through the constructor arguments.
 */
fun arbTypeVariableName(): Arb<String> =
    Arb.of(listOf("T", "E", "K", "V", "N", "TKey", "TValue", "T1", "Elem", "U"))

private fun arbBaseTypeSignature(): Arb<BaseTypeSignature> =
    Arb.of(
        JvmPrimitive.entries
            .filter { it != JvmPrimitive.VOID }
            .map { BaseTypeSignature(it) },
    )

fun arbClassTypeSignature(maxDepth: Int = 2): Arb<ClassTypeSignature> =
    Arb.bind(
        arbPackageName,
        arbIdentifierSegment,
        arbTypeArguments(maxDepth),
        Arb.list(arbInnerClassType(maxDepth), 0..2),
    ) { pkg, simple, arguments, inner ->
        ClassTypeSignature(pkg, simple, arguments, inner)
    }

private fun arbInnerClassType(maxDepth: Int): Arb<InnerClassType> =
    Arb.bind(arbIdentifierSegment, arbTypeArguments(maxDepth)) { simple, arguments ->
        InnerClassType(simple, arguments)
    }

fun arbTypeArgument(maxDepth: Int = 2): Arb<TypeArgument> {
    // Arb construction is eager, so the depth-0 base must build nothing recursive:
    // an unbounded wildcard, a bare type variable, or a class with no arguments at all.
    // (A `class(0)` bottoms out here too, which is what terminates the whole family.)
    if (maxDepth <= 0) {
        return Arb.choice(
            Arb.of<TypeArgument>(TypeArgument.Unbounded),
            arbTypeVariableName().map { TypeArgument.Exact(TypeVariableSignature(it)) },
            Arb.bind(arbPackageName, arbIdentifierSegment) { pkg, simple ->
                TypeArgument.Exact(ClassTypeSignature(pkg, simple, emptyList(), emptyList()))
            },
        )
    }
    val exact = arbReferenceTypeSignature(maxDepth - 1).map { TypeArgument.Exact(it) }
    return Arb.choice(
        Arb.of<TypeArgument>(TypeArgument.Unbounded),
        arbReferenceTypeSignature(maxDepth - 1).map { TypeArgument.UpperBounded(it) },
        arbReferenceTypeSignature(maxDepth - 1).map { TypeArgument.LowerBounded(it) },
        exact,
    )
}

private fun arbTypeArguments(maxDepth: Int): Arb<List<TypeArgument>> =
    Arb.choice(
        Arb.of(listOf(emptyList<TypeArgument>())),
        Arb.list(arbTypeArgument(maxDepth), 1..3),
    )

fun arbReferenceTypeSignature(maxDepth: Int = 2): Arb<ReferenceTypeSignature> =
    if (maxDepth <= 0) {
        Arb.choice(
            arbClassTypeSignature(0),
            arbTypeVariableName().map { TypeVariableSignature(it) },
        )
    } else {
        Arb.choice(
            arbClassTypeSignature(maxDepth - 1),
            arbTypeVariableName().map { TypeVariableSignature(it) },
            arbTypeSignature(maxDepth - 1).map { ArrayTypeSignature(it) },
        )
    }

fun arbTypeSignature(maxDepth: Int = 2): Arb<TypeSignature> =
    Arb.choice(arbBaseTypeSignature(), arbReferenceTypeSignature(maxDepth))

fun arbFormalTypeParameter(maxDepth: Int = 2): Arb<FormalTypeParameter> {
    // A parameter needs at least one bound; the class bound may be empty exactly when an
    // interface bound follows (`<T::Comparable<T>>`).
    val withClassBound = Arb.bind(
        arbTypeVariableName(),
        arbReferenceTypeSignature(maxDepth),
        Arb.list(arbReferenceTypeSignature(0), 0..2),
    ) { name, bound, interfaces -> FormalTypeParameter(name, bound, interfaces) }
    val emptyClassBound = Arb.bind(
        arbTypeVariableName(),
        Arb.list(arbReferenceTypeSignature(0), 1..2),
    ) { name, interfaces -> FormalTypeParameter(name, null, interfaces) }
    return Arb.choice(withClassBound, emptyClassBound)
}

fun arbClassSignature(): Arb<ClassSignature> =
    Arb.bind(
        Arb.list(arbFormalTypeParameter(1), 0..2),
        arbClassTypeSignature(2),
        Arb.list(arbClassTypeSignature(1), 0..2),
    ) { parameters, superclass, interfaces ->
        ClassSignature(parameters, superclass, interfaces)
    }

fun arbMethodSignature(): Arb<MethodSignature> =
    Arb.bind(
        Arb.list(arbFormalTypeParameter(1), 0..2),
        Arb.list(arbTypeSignature(2), 0..4),
        Arb.choice(Arb.of(listOf<TypeSignature>(VoidSignature)), arbTypeSignature(2)),
        Arb.list(arbThrowsSignature(), 0..2),
    ) { parameters, arguments, returnType, throws ->
        MethodSignature(parameters, arguments, returnType, throws)
    }

private fun arbThrowsSignature(): Arb<ThrowsSignature> =
    Arb.choice(
        arbClassTypeSignature(1).map { ThrowsSignature.ClassThrows(it) },
        arbTypeVariableName().map { ThrowsSignature.TypeVariableThrows(it) },
    )

fun arbFieldSignature(): Arb<FieldSignature> =
    arbReferenceTypeSignature(2).map { FieldSignature(it) }

fun arbGenericSignature(): Arb<GenericSignature> =
    Arb.choice(arbClassSignature(), arbMethodSignature(), arbFieldSignature())
