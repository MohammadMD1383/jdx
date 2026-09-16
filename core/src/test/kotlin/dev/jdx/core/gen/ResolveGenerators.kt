package dev.jdx.core.gen

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull

/**
 * Generators for member-resolution properties (T-009, TESTING.md §4). Small on purpose:
 * a few classes drawn from a tiny name pool, so hierarchies collide, override, cycle
 * and go missing — the shapes hand-written examples never reach. Kept in `gen/` as the
 * seed of T-055's shared generator library.
 */
private val poolPackages = listOf("p", "q")
private val poolTops = listOf("A", "B", "C", "D")
private val poolMembers = listOf("foo", "bar", "baz", "qux", "x")
private val poolDescriptors = listOf("()V", "()I", "(I)V", "(Ljava/lang/Object;)Z", "()Ljava/lang/Object;")

private fun arbClassName(): Arb<String> = Arb.bind(
    Arb.element(poolPackages),
    Arb.element(poolTops),
) { pkg, top -> "$pkg.$top" }

private fun arbAccess(kind: String): Arb<Access> =
    // Constructors are modelled as methods; bridge on a field is meaningless noise,
    // so fields never draw the bridge variant.
    if (kind == "field") {
        Arb.element(
            listOf(
                Access.of(AccessFlag.PUBLIC),
                Access.of(AccessFlag.PRIVATE),
                Access.NONE,
                Access.of(AccessFlag.SYNTHETIC),
            ),
        )
    } else {
        Arb.element(
            listOf(
                Access.of(AccessFlag.PUBLIC),
                Access.of(AccessFlag.PROTECTED),
                Access.of(AccessFlag.PRIVATE),
                Access.NONE, // package-private
                Access.of(AccessFlag.PUBLIC, AccessFlag.SYNTHETIC),
                Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE, AccessFlag.SYNTHETIC),
                Access.of(AccessFlag.SYNTHETIC),
            ),
        )
    }

private fun arbMethod(): Arb<MethodInfo> = Arb.bind(
    Arb.element(poolMembers + "<init>"),
    Arb.element(poolDescriptors),
    arbAccess("method"),
) { name, descriptor, access ->
    MethodInfo(
        name = name,
        descriptor = JvmDescriptor.parse(descriptor) as JvmDescriptor.Method,
        access = access,
    )
}

private fun arbField(): Arb<FieldInfo> = Arb.bind(
    Arb.element(poolMembers),
    Arb.element(listOf("I", "Ljava/lang/Object;", "Z")),
    arbAccess("field"),
) { name, descriptor, access ->
    FieldInfo(
        name = name,
        type = (JvmDescriptor.parse(descriptor) as JvmDescriptor.Field).type,
        access = access,
    )
}

/** One connected-or-not soup of classes: supertypes may cycle, repeat or point nowhere. */
internal data class ClassGraph(val classes: List<ClassInfo>, val targetBinary: String)

/**
 * Generates small, nasty class graphs: 1–4 classes from a 2×4 name pool (collisions are
 * the point — duplicate binary names collapse to one entry, like classpath shadowing),
 * erased-only supertypes that may cycle or dangle, and members drawn from a tiny pool
 * so overrides and overloads happen constantly.
 */
internal fun arbClassGraph(): Arb<ClassGraph> = Arb.bind(
    Arb.list(arbClassBody(), 1..4),
    Arb.element(poolPackages),
    Arb.element(poolTops),
) { bodies, targetPkg, targetTop ->
    val infos = bodies.mapIndexed { index, body ->
        val binary = "$targetPkg.${poolTops[index % poolTops.size]}"
        ClassInfo(
            name = typeNameFromBinaryName(binary) as TypeName.ClassType,
            kind = TypeKind.CLASS,
            superclass = body.superclass?.let { typeNameFromBinaryName(it) },
            interfaces = body.interfaces.map { typeNameFromBinaryName(it) },
            fields = body.fields,
            methods = body.methods,
        )
    }
    // The pool is smaller than the class count can be, so binary names repeat: keep the
    // first declaration, mirroring classpath-order shadowing.
    val deduped = infos.distinctBy { it.name.binaryName }
    ClassGraph(deduped, "$targetPkg.$targetTop")
}

private data class ClassBody(
    val superclass: String?,
    val interfaces: List<String>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
)

private fun arbClassBody(): Arb<ClassBody> = Arb.bind(
    // Small chance of no superclass (an Object-like root); otherwise any pool name,
    // including the class itself — cycles are wanted, not avoided.
    arbClassName().orNull(0.15),
    Arb.list(arbClassName(), 0..2),
    Arb.list(arbField(), 0..4),
    Arb.list(arbMethod(), 0..5),
) { superclass, interfaces, fields, methods ->
    ClassBody(superclass, interfaces.distinct(), fields, methods)
}

/** The `java.lang.Object` stub every generated graph implicitly bottoms out at. */
internal fun generatedObjectStub(): ClassInfo = ClassInfo(
    name = typeNameFromBinaryName("java.lang.Object") as TypeName.ClassType,
    kind = TypeKind.CLASS,
    superclass = null,
)

/** A lookup over the graph plus the Object stub; unknown names are missing supertypes. */
internal fun graphLookup(graph: ClassGraph): (dev.jdx.core.model.TypeName) -> ClassInfo? {
    val byBinary = (graph.classes + generatedObjectStub()).associateBy { it.name.binaryName }
    return { name -> byBinary[name.binaryName] }
}

/** The graph's target, synthesised empty when the pool never generated it. */
internal fun graphTarget(graph: ClassGraph): ClassInfo =
    graph.classes.firstOrNull { it.name.binaryName == graph.targetBinary }
        ?: ClassInfo(
            name = typeNameFromBinaryName(graph.targetBinary) as TypeName.ClassType,
            kind = TypeKind.CLASS,
            superclass = typeNameFromBinaryName("java.lang.Object"),
        )

internal fun arbSyntheticToggle(): Arb<Boolean> = Arb.of(true, false)
