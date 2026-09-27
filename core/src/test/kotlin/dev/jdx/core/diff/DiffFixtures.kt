package dev.jdx.core.diff

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.ref.SymbolRefPrinter

/**
 * Hand-built snapshots for the differ tests. The point of building a diff substrate
 * by hand is that a test can name *one* difference and assert that exactly one rule
 * fired — which no amount of real-bytecode fixture can do.
 *
 * Canonical refs are produced with the real [SymbolRefPrinter] so a ref asserted in a
 * test is spelled the way the product spells it; the production path for those refs is
 * `methodRefString` (which delegates to this printer) and is pinned separately in
 * [dev.jdx.core.diff.ApiSnapshotTest] against real `ClassInfo`.
 */
internal fun snapshotOf(label: String, vararg types: ApiType): ApiSnapshot =
    ApiSnapshot(label, types.associateBy { it.binaryName })

internal fun type(
    name: String,
    kind: TypeKind = TypeKind.CLASS,
    access: Access = PUBLIC,
    superclass: TypeName? = null,
    interfaces: List<TypeName> = emptyList(),
    members: List<ApiMember> = emptyList(),
    deprecated: Boolean = false,
    isKotlin: Boolean = false,
    annotations: List<AnnotationInfo> = emptyList(),
): ApiType = ApiType(
    binaryName = name,
    kind = kind,
    access = access,
    superclass = superclass,
    interfaces = interfaces,
    members = sortedByKeyText(members.map { it.key to it }),
    deprecated = deprecated,
    isKotlin = isKotlin,
    annotations = annotations,
).withMemberRefs()

/** A method or constructor. A `null` [ref] is filled in from the declaring type by [type]. */
internal fun method(
    name: String,
    parameters: List<TypeName> = emptyList(),
    returnType: TypeName = VOID,
    access: Access = PUBLIC,
    parameterNames: List<String?> = emptyList(),
    genericSignature: String? = null,
    throws: List<TypeName> = emptyList(),
    annotationDefault: String? = null,
    varargs: Boolean = false,
    native: Boolean = false,
    synchronized: Boolean = false,
    kotlin: KotlinMethodView? = null,
    deprecated: Boolean = false,
    annotations: List<AnnotationInfo> = emptyList(),
    ref: String? = null,
): ApiMethod {
    val key = ApiMethodKey(name, parameters)
    return ApiMethod(
        key = key,
        access = access,
        canonicalRef = ref ?: "",
        returnType = returnType,
        parameterNames = parameterNames,
        genericSignature = genericSignature,
        throwsTypes = throws,
        annotationDefault = annotationDefault,
        varargs = varargs,
        native = native,
        synchronized = synchronized,
        kotlin = kotlin,
        deprecated = deprecated,
        annotations = annotations,
    )
}

/** A field. A `null` [ref] is filled in from the declaring type by [type]. */
internal fun field(
    name: String,
    fieldType: TypeName = INT,
    access: Access = PUBLIC,
    genericSignature: String? = null,
    constantValue: String? = null,
    transient: Boolean = false,
    kotlin: KotlinPropertyView? = null,
    deprecated: Boolean = false,
    annotations: List<AnnotationInfo> = emptyList(),
    ref: String? = null,
): ApiField {
    val key = ApiFieldKey(name, fieldType)
    return ApiField(
        key = key,
        access = access,
        canonicalRef = ref ?: "",
        genericSignature = genericSignature,
        constantValue = constantValue,
        transient = transient,
        kotlin = kotlin,
        deprecated = deprecated,
        annotations = annotations,
    )
}

/**
 * Fills in the canonical refs a member left blank, so a hand-built member spells
 * itself exactly as the reader would: `Type#name` for a field,
 * `Type#name(param, param)` for a method or constructor.
 */
internal fun ApiType.withMemberRefs(): ApiType = copy(
    members = members.mapValues { (_, member) ->
        if (member.canonicalRef.isNotEmpty()) {
            member
        } else {
            when (member) {
                is ApiMethod -> member.copy(canonicalRef = methodRefFor(binaryName, member.key))
                is ApiField -> member.copy(canonicalRef = fieldRefFor(binaryName, member.key))
            }
        }
    },
)

private fun methodRefFor(typeName: String, key: ApiMethodKey): String = SymbolRefPrinter.print(
    MemberSymbolRef(
        declaringType = typeNameFromBinaryName(typeName),
        name = key.name,
        parameterTypes = key.parameterTypes,
    ),
)

private fun fieldRefFor(typeName: String, key: ApiFieldKey): String = SymbolRefPrinter.print(
    MemberSymbolRef(declaringType = typeNameFromBinaryName(typeName), name = key.name),
)

/** `diff` as a rule list — the shape almost every assertion wants. */
internal fun rulesBetween(old: ApiSnapshot, new: ApiSnapshot): List<CompatRule> =
    ApiDiffer.diff(old, new).findings.map { it.rule }

/** `diff` as full findings, for asserting a `detail`. */
internal fun findingsBetween(old: ApiSnapshot, new: ApiSnapshot): List<DiffFinding> =
    ApiDiffer.diff(old, new).findings

/** The single finding between two snapshots, or a failure naming what was found instead. */
internal fun onlyFindingBetween(old: ApiSnapshot, new: ApiSnapshot): DiffFinding {
    val findings = findingsBetween(old, new)
    check(findings.size == 1) { "expected exactly one finding, got $findings" }
    return findings.single()
}

// -- shared values -------------------------------------------------------------

/** `public`, and nothing else: the access bits a visibility question can turn on. */
internal val PUBLIC: Access = Access(AccessFlag.PUBLIC.mask)
internal val INT: TypeName = TypeName.PrimitiveType(JvmPrimitive.INT)
internal val LONG: TypeName = TypeName.PrimitiveType(JvmPrimitive.LONG)
internal val VOID: TypeName = TypeName.PrimitiveType(JvmPrimitive.VOID)
internal val STRING: TypeName = typeNameFromBinaryName("java.lang.String")
internal val OBJECT: TypeName = typeNameFromBinaryName("java.lang.Object")
internal val RUNTIME_EXCEPTION: TypeName = typeNameFromBinaryName("java.lang.RuntimeException")
internal val IO_EXCEPTION: TypeName = typeNameFromBinaryName("java.io.IOException")
internal val SERIALIZABLE: TypeName = typeNameFromBinaryName("java.io.Serializable")
