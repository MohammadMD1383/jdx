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
 *
 * [deprecated] is true when the class carries the `Deprecated` attribute or a
 * `@Deprecated` annotation (T-008 reads both — either source proves deprecation).
 *
 * [isKotlin] is true when the class file carries a Kotlin `@Metadata` annotation
 * (T-035 decodes it in `index`; file facades count — they are Kotlin declarations
 * whose JVM projection is a static utility class).
 *
 * [kotlinMethodViews] maps JVM methods to their Kotlin view (T-077): the key is
 * the JVM name plus the descriptor string ([kotlinViewKey]), the value the
 * declaration name and `suspend` repair from `@Metadata`. Populated by `index`
 * at read time; empty for Java classes and for every unmapped member, which
 * keeps the JVM projection. Never persisted (like the T-076 carrier, the
 * first consumer reads live roots).
 *
 * [kotlinProperties] maps Kotlin property names to their folded view (T-078):
 * populated by `index` from `KmProperty` getter/setter signatures; empty for
 * Java and unmapped Kotlin. [kotlinHiddenMethods] holds the JVM keys
 * ([kotlinViewKey]) of folded getters/setters, [kotlinHiddenFields] the names
 * of folded backing fields — hidden from listings unless `--include-synthetic`.
 * Never persisted, like the method views.
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
    public val deprecated: Boolean = false,
    public val isKotlin: Boolean = false,
    public val kotlinMethodViews: Map<String, KotlinMethodView> = emptyMap(),
    public val kotlinProperties: Map<String, KotlinPropertyView> = emptyMap(),
    public val kotlinHiddenMethods: Set<String> = emptySet(),
    public val kotlinHiddenFields: Set<String> = emptySet(),
) {
    /** Fields then methods, in declaration order — the order renderers print. */
    public val members: List<MemberInfo>
        get() = fields + methods

    /** Methods named `<init>`. */
    public val constructors: List<MethodInfo>
        get() = methods.filter { it.name == "<init>" }
}
