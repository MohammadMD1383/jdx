package dev.jdx.core.model

/**
 * Structured annotation element value (issue #26).
 *
 * Bytecode still stores a rendered [AnnotationInfo.values] map for the index
 * and for agents that copy `@Fqn(k=v)` spellings, but matching and nested
 * walks prefer this AST when [AnnotationInfo.elements] is populated (ASM
 * readers fill both). Leaves stay [Scalar] with the same deterministic
 * spelling [dev.jdx.index.asm.AsmClassReader] already emits — Nested and
 * Arr carry real structure so `--where nested.k=1` / `{…}` multisets do not
 * re-peel strings.
 *
 * [AnnotationInfo.elements] is ephemeral like Kotlin member views: the SQLite
 * index persists only the rendered [AnnotationInfo.values] map; live ASM
 * reads refill the AST. Matching falls back to `values` when `elements` is
 * empty.
 */
public sealed interface AnnotationValue {
    /** Deterministic rendered form (parity with ASM `renderValue`). */
    public fun render(): String

    /**
     * A leaf value already rendered (`true`, `"99"`, `pkg.E.NAME`,
     * `java.lang.String.class`, …). Keeps float/enum/class spellings
     * byte-identical to the historical string map.
     */
    public data class Scalar(
        public val rendered: String,
    ) : AnnotationValue {
        override fun render(): String = rendered
    }

    /** A nested annotation use (`@Fqn` / `@Fqn(k=v,…)`). */
    public data class Nested(
        public val type: TypeName,
        public val elements: Map<String, AnnotationValue> = emptyMap(),
    ) : AnnotationValue {
        override fun render(): String {
            if (elements.isEmpty()) return "@${type.fqn}"
            val pairs = elements.entries
                .sortedBy { it.key }
                .joinToString(", ") { (key, value) -> "$key=${value.render()}" }
            return "@${type.fqn}($pairs)"
        }
    }

    /** An annotation array value (`{a, b}`). */
    public data class Arr(
        public val elements: List<AnnotationValue> = emptyList(),
    ) : AnnotationValue {
        override fun render(): String =
            elements.joinToString(", ", "{", "}") { it.render() }
    }
}

/**
 * Builds an [AnnotationInfo] whose rendered [AnnotationInfo.values] map is
 * derived from [elements] (single source of truth for ASM-produced uses).
 */
public fun annotationInfo(
    type: TypeName,
    elements: Map<String, AnnotationValue> = emptyMap(),
): AnnotationInfo = AnnotationInfo(
    type = type,
    values = elements.mapValues { it.value.render() },
    elements = elements,
)

/**
 * Annotation-element defaults from an annotation type's methods (issue #26).
 *
 * Keys are element names; values are the rendered [MethodInfo.annotationDefault]
 * spellings. Empty when the type is not an annotation or declares no defaults.
 * Used to fill elements omitted in bytecode so `--where forRemoval=false`
 * matches a bare `@Deprecated`.
 */
public fun ClassInfo.annotationElementDefaults(): Map<String, String> {
    if (kind != TypeKind.ANNOTATION) return emptyMap()
    val defaults = linkedMapOf<String, String>()
    for (method in methods) {
        val rendered = method.annotationDefault ?: continue
        defaults[method.name] = rendered
    }
    return defaults
}
