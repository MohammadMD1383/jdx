package dev.jdx.core.resolve

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassSignature
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName

/**
 * Shared builders for member-resolution tests (T-009). Hand-built [ClassInfo] graphs are
 * the only way to pin the resolver's contract precisely — real bytecode (the tier-2 JRT
 * test) can only spot-check, never dictate edge cases like cycles.
 */
internal fun testClassType(binary: String): TypeName.ClassType =
    typeNameFromBinaryName(binary) as TypeName.ClassType

internal fun testMethodDescriptor(text: String): JvmDescriptor.Method =
    JvmDescriptor.parse(text) as JvmDescriptor.Method

internal fun testMethodSignature(text: String): MethodSignature =
    GenericSignature.parse(text) as MethodSignature

internal fun testClassSignature(text: String): ClassSignature =
    GenericSignature.parseClass(text) ?: error("not a class signature: $text")

internal fun testFieldSignature(text: String): FieldSignature =
    GenericSignature.parse(text) as FieldSignature

internal fun publicMethod(
    name: String,
    descriptor: String,
    genericSignature: String? = null,
    access: Access = Access.of(AccessFlag.PUBLIC),
): MethodInfo = MethodInfo(
    name = name,
    descriptor = testMethodDescriptor(descriptor),
    access = access,
    genericSignature = genericSignature?.let { testMethodSignature(it) },
)

internal fun publicField(
    name: String,
    descriptor: String,
    genericSignature: String? = null,
): FieldInfo = FieldInfo(
    name = name,
    type = (JvmDescriptor.parse(descriptor) as JvmDescriptor.Field).type,
    access = Access.of(AccessFlag.PUBLIC),
    genericSignature = genericSignature?.let { testFieldSignature(it) },
)

internal fun testClass(
    binary: String,
    superclass: String? = null,
    interfaces: List<String> = emptyList(),
    genericSignature: String? = null,
    fields: List<FieldInfo> = emptyList(),
    methods: List<MethodInfo> = emptyList(),
): ClassInfo = ClassInfo(
    name = testClassType(binary),
    kind = TypeKind.CLASS,
    superclass = superclass?.let { typeNameFromBinaryName(it) },
    interfaces = interfaces.map { typeNameFromBinaryName(it) },
    genericSignature = genericSignature?.let { testClassSignature(it) },
    fields = fields,
    methods = methods,
)

internal fun testInterface(
    binary: String,
    interfaces: List<String> = emptyList(),
    genericSignature: String? = null,
    methods: List<MethodInfo> = emptyList(),
): ClassInfo = ClassInfo(
    name = testClassType(binary),
    kind = TypeKind.INTERFACE,
    superclass = null,
    interfaces = interfaces.map { typeNameFromBinaryName(it) },
    genericSignature = genericSignature?.let { testClassSignature(it) },
    methods = methods,
)

/** A lookup over an in-memory map; unknown names are missing supertypes, like a real index. */
internal fun mapLookup(vararg infos: ClassInfo): (TypeName) -> ClassInfo? {
    val byBinary = infos.associateBy { it.name.binaryName }
    return { name -> byBinary[name.binaryName] }
}
