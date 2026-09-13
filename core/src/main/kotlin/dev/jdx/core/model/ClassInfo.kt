package dev.jdx.core.model

/**
 * The declaration kind of a class file. `OBJECT` and `COMPANION` are Kotlin-only kinds,
 * derived from `@Metadata` by the `index` module; the rest come straight from JVM flags.
 */
public enum class TypeKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    OBJECT,
    COMPANION,
}

/**
 * An annotation use on a class or member. [values] maps element names to raw rendered
 * values (strings, as the renderer formats them) — full value modelling arrives with the
 * javadoc/KDoc work (M3).
 */
public data class AnnotationInfo(
    public val type: TypeName,
    public val values: Map<String, String> = emptyMap(),
)

/**
 * A class or interface declaration, the central node of the model. Produced by `index`
 * (bytecode — the skeleton, D-009), `sources` and `decompile`.
 *
 * [superclass] is `null` only for `java.lang.Object` and interfaces (JVMS: interfaces
 * have `Object` as their direct superclass in the class file — producers normalise that
 * to `null` here; the distinction is not interesting to any query).
 */
public data class ClassInfo(
    public val name: TypeName.ClassType,
    public val kind: TypeKind,
    public val access: Access = Access.NONE,
    public val superclass: TypeName? = null,
    public val interfaces: List<TypeName> = emptyList(),
    public val genericSignature: ClassSignature? = null,
    public val fields: List<FieldInfo> = emptyList(),
    public val methods: List<MethodInfo> = emptyList(),
    public val annotations: List<AnnotationInfo> = emptyList(),
    public val outerClass: TypeName.ClassType? = null,
    public val sourceFileName: String? = null,
) {
    /** Fields then methods, in declaration order — the order renderers print. */
    public val members: List<MemberInfo>
        get() = fields + methods

    /** Methods named `<init>`. */
    public val constructors: List<MethodInfo>
        get() = methods.filter { it.name == "<init>" }
}
