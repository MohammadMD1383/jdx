package dev.jdx.core.gen

import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.arrayTypeName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of

/**
 * Shared generators for JVM type names and descriptors (T-055; TESTING.md §4).
 *
 * Nastiness on purpose: empty (default) packages, unicode segments, nested classes three
 * deep (whose binary names carry `$` — the only place `$` may legally appear, since a bare
 * segment containing `$` would violate [TypeName.ClassType]'s invariant and D-025 treats
 * `$` as a nesting separator), multi-dimension arrays, and every primitive.
 *
 * Segments exclude `.$;[/<>:` so a generated name is also safe inside a generic signature
 * ([SignatureGenerators] reuses [arbIdentifierSegment] for class names there).
 */
val arbIdentifierSegment: Arb<String> = Arb.of(
    listOf(
        "Gson", "Map", "Entry", "A", "Foo", "X1", "_p", "inner", "c",
        "Über", "λ", "中", "A1_B2", "x", "Z", "Object", "String", "List",
    ),
)

val arbPackageName: Arb<String> = Arb.of(
    listOf("", "a", "p", "com.example", "java.util", "dev.jdx", "x.y.z", "org.λ"),
)

fun arbClassType(maxNesting: Int = 3): Arb<TypeName.ClassType> =
    Arb.bind(
        arbPackageName,
        arbIdentifierSegment,
        Arb.list(arbIdentifierSegment, 0..maxNesting),
    ) { pkg, top, nested -> TypeName.ClassType(pkg, listOf(top) + nested) }

fun arbPrimitiveType(): Arb<TypeName.PrimitiveType> =
    Arb.of(JvmPrimitive.entries.map { TypeName.PrimitiveType(it) })

/** Void arrays (`[[[V`) are not types at all — `V` is a return marker, not a field type. */
private fun arbArrayElement(): Arb<TypeName> =
    Arb.choice(
        arbClassType(),
        Arb.of(JvmPrimitive.entries.filter { it != JvmPrimitive.VOID }.map { TypeName.PrimitiveType(it) }),
    )

/** Any type, void included — the right choice for method returns, the wrong one for fields. */
fun arbTypeName(): Arb<TypeName> =
    Arb.choice(
        arbClassType(),
        arbPrimitiveType(),
        Arb.bind(arbArrayElement(), Arb.int(1..3)) { element, dimensions ->
            arrayTypeName(element, dimensions)
        },
    )

/** Any type legal in a field or parameter position: everything but `void` (JVMS §4.3.2). */
fun arbFieldTypeName(): Arb<TypeName> =
    Arb.choice(
        arbClassType(),
        Arb.of(JvmPrimitive.entries.filter { it != JvmPrimitive.VOID }.map { TypeName.PrimitiveType(it) }),
        Arb.bind(arbClassType(), Arb.int(1..3)) { element, dimensions ->
            arrayTypeName(element, dimensions)
        },
        Arb.bind(
            Arb.of(JvmPrimitive.entries.filter { it != JvmPrimitive.VOID }.map { TypeName.PrimitiveType(it) }),
            Arb.int(1..3),
        ) { element, dimensions -> arrayTypeName(element, dimensions) },
    )

fun arbJvmFieldDescriptor(): Arb<JvmDescriptor.Field> =
    arbFieldTypeName().map { type -> JvmDescriptor.Field(type) }

fun arbJvmMethodDescriptor(): Arb<JvmDescriptor.Method> =
    Arb.bind(
        Arb.list(arbFieldTypeName(), 0..4),
        arbTypeName(),
    ) { parameters, returnType -> JvmDescriptor.Method(parameters, returnType) }

fun arbJvmDescriptor(): Arb<JvmDescriptor> =
    Arb.choice(arbJvmFieldDescriptor(), arbJvmMethodDescriptor())
