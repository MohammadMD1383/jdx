package dev.jdx.core.model

/**
 * The Kotlin view of one JVM method, derived from `@Metadata` (T-077).
 *
 * Produced by `index` (which reads the metadata) and applied by `core`
 * renderers; `core` never reads metadata itself. A missing entry in
 * [ClassInfo.kotlinMethodViews] is the JVM projection — every Java method and
 * every unmapped Kotlin method (constructors, `$default` stubs, file-facade
 * members, corrupt metadata) renders exactly as today.
 *
 * [displayName] is the Kotlin declaration name (`originalName` for a
 * `@JvmName("renamedForJvm")` function, `internalHelper` for a mangled
 * `internalHelper$module` one). [stripTrailingContinuation] drops the hidden
 * `Continuation` parameter of a `suspend` function from the rendered
 * parameter list (ignored unless the tail parameter really is
 * `kotlin.coroutines.Continuation` — degrade, don't fail). [displayReturn]
 * is the metadata return type as rendered text (`java.lang.String` for a
 * `suspend` function whose JVM erasure is `Object`); `null` keeps the JVM
 * return type. [markSuspend] prints the `suspend` keyword.
 */
public data class KotlinMethodView(
    public val displayName: String,
    public val stripTrailingContinuation: Boolean = false,
    public val displayReturn: String? = null,
    public val markSuspend: Boolean = false,
) {
    /**
     * Whether the Continuation strip applies to [parameters]: the flag must be
     * set, a parameter must exist, and the tail must be the (erased)
     * `kotlin.coroutines.Continuation`. Anything else keeps the JVM list.
     */
    public fun stripAppliesTo(parameters: List<TypeName>): Boolean {
        if (!stripTrailingContinuation || parameters.isEmpty()) return false
        return (parameters.last() as? TypeName.ClassType)?.binaryName ==
            "kotlin.coroutines.Continuation"
    }
}

/** Map key joining a JVM method name with its descriptor string. */
public fun kotlinViewKey(jvmName: String, jvmDescriptor: String): String =
    jvmName + jvmDescriptor
