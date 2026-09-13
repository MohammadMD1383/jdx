package dev.jdx.core.gen

import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MavenCoordinate
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.SymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import dev.jdx.core.model.arrayTypeName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull

/**
 * Shared property-test generators for symbol refs (T-003; the seed of the `gen/`
 * package that T-055 grows into the project-wide generator library).
 *
 * **Round-trip safety (D-025):** generated refs print to text that parses back to the
 * identical value. This constrains package names — every segment must start with a
 * lowercase letter — and excludes module refs (no input syntax, print-only). The
 * excluded shapes are documented in D-025; the parser's KDoc carries the same caveat.
 */

/** Packages whose segments all start lowercase, so `print` → `parse` is the identity. */
val arbRoundTripPackageName: Arb<String> =
    Arb.of("", "a", "com.example", "java.util", "dev.jdx", "x.y.z")

/** Class name segments: mixed case, digits, underscores, unicode — no separators. */
val arbClassSegment: Arb<String> =
    Arb.of("Gson", "Map", "Entry", "A", "Foo", "X1", "_p", "inner", "c", "Über", "λ")

fun arbClassType(): Arb<TypeName.ClassType> =
    Arb.bind(arbRoundTripPackageName, arbClassSegment, Arb.list(arbClassSegment, 0..3)) { pkg, top, nested ->
        TypeName.ClassType(pkg, listOf(top) + nested)
    }

val arbPrimitive: Arb<TypeName> =
    Arb.of(JvmPrimitive.entries.map { TypeName.PrimitiveType(it) })

/** Primitives legal in a *parameter* position — void is return-only (JVMS §4.3.2, L-005). */
val arbNonVoidPrimitive: Arb<TypeName> =
    Arb.of(JvmPrimitive.entries.filter { it != JvmPrimitive.VOID }.map { TypeName.PrimitiveType(it) })

fun arbParameterType(): Arb<TypeName> =
    Arb.choice(
        arbClassType(),
        arbNonVoidPrimitive,
        Arb.bind(arbClassType(), Arb.int(1..3)) { t, d -> arrayTypeName(t, d) },
        Arb.bind(arbNonVoidPrimitive, Arb.int(1..2)) { t, d -> arrayTypeName(t, d) },
    )

/** Return positions accept any primitive, void included. */
fun arbReturnType(): Arb<TypeName> = Arb.choice(arbClassType(), arbPrimitive)

val arbMemberName: Arb<String> = Arb.of("toJson", "get", "value", "a", "λ", "<init>", "<clinit>")

fun arbCoordinate(): Arb<MavenCoordinate> =
    Arb.bind(
        Arb.of("com.example", "org.apache"),
        Arb.of("gson", "artifact-x"),
        Arb.of("1.0", "2.14.0", "1.0-SNAPSHOT"),
    ) { g, a, v -> MavenCoordinate(g, a, v) }

/**
 * Parameter/return shapes the parser can produce: absent list, explicit zero args,
 * zero args with a return type, or a specified list with an optional return type.
 * (`null` parameters with a non-null return type is not a parser-producible shape.)
 */
fun arbParameterAndReturnShape(): Arb<Pair<List<TypeName>?, TypeName?>> =
    Arb.choice(
        Arb.of<Pair<List<TypeName>?, TypeName?>>(null to null),
        Arb.of(emptyList<TypeName>() to (null as TypeName?)),
        Arb.of(emptyList<TypeName>() to TypeName.PrimitiveType(JvmPrimitive.VOID)),
        Arb.bind(Arb.list(arbParameterType(), 1..3), arbReturnType().orNull()) { ps, rt -> ps to rt },
    )

/** Any ref whose printed form round-trips: type, member, or package glob. */
fun arbRoundTripRef(): Arb<SymbolRef> =
    Arb.choice(
        Arb.bind(arbClassType(), arbCoordinate().orNull()) { t, c -> TypeSymbolRef(t, c) },
        Arb.bind(
            arbClassType(),
            arbMemberName,
            arbParameterAndReturnShape(),
            arbCoordinate().orNull(),
        ) { dt, n, (ps, rt), c -> MemberSymbolRef(dt, n, ps, rt, c) },
        Arb.bind(
            Arb.of("com.example", "java.util", "a"),
            Arb.of(".*", ".**"),
        ) { p, s -> PackageSymbolRef(p + s) },
    )
