package dev.jdx.core.model

/**
 * A declared member of a class: a field or a method. Constructors and static initialisers
 * are [MethodInfo]s named `<init>` and `<clinit>`. Produced by `index` (bytecode),
 * `sources` (source declarations) and `decompile`.
 */
public sealed interface MemberInfo {
    /** The member's simple name; `<init>`/`<clinit>` for constructors/initialisers. */
    public val name: String

    public val access: Access

    /** Annotations declared on the member, without `java.lang.annotation` meta-noise. */
    public val annotations: List<AnnotationInfo>

    /** Whether the declaration carries `@Deprecated`. */
    public val deprecated: Boolean
}

/**
 * A field declaration. [type] is the erased type from the descriptor;
 * [genericSignature] carries the generic type when the field is generic.
 */
public data class FieldInfo(
    override val name: String,
    public val type: TypeName,
    override val access: Access,
    public val genericSignature: FieldSignature? = null,
    override val annotations: List<AnnotationInfo> = emptyList(),
    override val deprecated: Boolean = false,
) : MemberInfo

/**
 * A method, constructor or static-initialiser declaration. [descriptor] is the erased
 * JVM truth; [genericSignature] is present when the method is generic or refers to type
 * variables. [parameterNames] come from `MethodParameters`/`LocalVariableTable` when
 * present — `null` entries mean "unknown, a renderer must synthesise and label".
 */
public data class MethodInfo(
    override val name: String,
    public val descriptor: JvmDescriptor.Method,
    override val access: Access,
    public val genericSignature: MethodSignature? = null,
    public val parameterNames: List<String?> = emptyList(),
    public val throwsTypes: List<TypeName> = emptyList(),
    override val annotations: List<AnnotationInfo> = emptyList(),
    override val deprecated: Boolean = false,
) : MemberInfo
