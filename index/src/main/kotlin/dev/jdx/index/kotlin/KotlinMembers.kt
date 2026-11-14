package dev.jdx.index.kotlin

import dev.jdx.core.model.Access
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.kotlinViewKey
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.isVar
import kotlin.metadata.jvm.JvmFieldSignature
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature

/**
 * Maps collected JVM members back onto Kotlin declarations (T-077, PROPOSAL.md
 * §9.3 step 7, §12.1).
 *
 * Pure functions over the T-076 carrier: one [KmFunction] ↔ one JVM method,
 * keyed by the metadata `jvmSignature` (name + descriptor), which the compiler
 * writes to match the class file exactly — `@JvmName` renames, `internal`
 * mangling (`name$module`) and the `suspend` `Continuation` parameter are all
 * visible as the difference between [KmFunction.name] and the JVM signature.
 *
 * Everything here degrades: a function without a JVM signature cannot be
 * keyed, an unrenderable type keeps the JVM text, a malformed descriptor never
 * throws. Callers render the JVM projection for anything unmapped.
 */
public object KotlinMembers {

    /** Erased tail parameter the compiler appends to every `suspend` function. */
    public const val CONTINUATION_BINARY_NAME: String = "kotlin.coroutines.Continuation"

    /**
     * The folded property set for a `CLASS` [KmClass] (T-078): properties with
     * their hidden JVM accessor/field keys.
     *
     * [properties] maps the Kotlin property name to its view; [hiddenMethods]
     * holds the JVM keys ([kotlinViewKey]) of folded getters/setters;
     * [hiddenFields] holds backing-field names. All three are empty when the
     * class declares no mappable properties. Never throws.
     */
    public data class KotlinProperties(
        public val properties: Map<String, KotlinPropertyView>,
        public val hiddenMethods: Set<String>,
        public val hiddenFields: Set<String>,
    )

    /**
     * Builds the per-method Kotlin views for a `CLASS` [KmClass], keyed by
     * [kotlinViewKey] (JVM name + descriptor) for lookup from `ClassInfo`.
     *
     * Only methods whose Kotlin view differs from the JVM projection are
     * present: renames (`@JvmName`, mangled `internal`), `suspend`
     * functions (stripped `Continuation`, `suspend` keyword, metadata return),
     * functions with default args (T-078 `= ...` markers), and functions whose
     * metadata types carry nullability the JVM erasure cannot see (issue #13 —
     * `String?`, `List<String?>`, nullable returns/params).
     * Same-name non-suspend non-nullable functions without defaults, constructors
     * (which live on [KmClass.constructors], never [KmClass.functions]) and
     * `$default` stubs (synthetic, no metadata entry) map to nothing.
     */
    public fun viewsFor(kmClass: KmClass): Map<String, KotlinMethodView> {
        val views = linkedMapOf<String, KotlinMethodView>()
        for (function in kmClass.functions) {
            val jvm = function.signature ?: continue
            val suspend = function.isSuspend
            val strip = suspend && isContinuationTail(jvm.descriptor)
            val renamed = function.name != jvm.name
            val defaults = defaultArgIndices(function)
            val returnNullable = carriesNullability(function.returnType)
            // Suspend always prefers the metadata return (erasure is often Object);
            // non-suspend only overrides when nullability (incl. nested) is present.
            val displayReturn = when {
                suspend -> renderType(function.returnType)
                returnNullable -> renderType(function.returnType)
                else -> null
            }
            // Display params are indexed in Kotlin order against the *visible* JVM
            // list (post-Continuation-strip). Value-class members prepend an
            // unboxed receiver on the JVM that metadata does not list — when the
            // arities disagree, keep every JVM type rather than shift-misalign
            // (issue #13).
            val visibleJvmArity = parameterArity(jvm.descriptor).let { arity ->
                if (strip && arity > 0) arity - 1 else arity
            }
            val kotlinArity = try {
                function.valueParameters.size
            } catch (_: Exception) {
                -1
            }
            val displayParameters = if (kotlinArity == visibleJvmArity) {
                displayParametersFor(function)
            } else {
                emptyList()
            }
            val hasParamDisplay = displayParameters.any { it != null }
            if (!renamed && !strip && displayReturn == null && !suspend &&
                defaults.isEmpty() && !hasParamDisplay
            ) {
                continue
            }
            // A `suspend` whose return survives erasure unchanged still marks.
            views[kotlinViewKey(jvm.name, jvm.descriptor)] = KotlinMethodView(
                displayName = function.name,
                stripTrailingContinuation = strip,
                displayReturn = displayReturn,
                markSuspend = suspend,
                defaultArgIndices = defaults,
                displayParameters = if (hasParamDisplay) displayParameters else emptyList(),
            )
        }
        return views
    }

    /**
     * Per-value-parameter metadata type text for [viewsFor] (issue #13). A slot
     * is filled only when the `KmType` carries nullability the JVM cannot see;
     * otherwise `null` keeps the JVM projection. Never throws.
     */
    public fun displayParametersFor(function: KmFunction): List<String?> {
        return try {
            function.valueParameters.map { parameter ->
                try {
                    val type = parameter.type
                    if (!carriesNullability(type)) null else renderType(type)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Whether a metadata type (or any type argument) is nullable. Hostile /
     * incomplete containers read as `false`, never a throw.
     */
    public fun carriesNullability(type: KmType): Boolean {
        return try {
            if (type.isNullable) return true
            type.arguments.any { projection ->
                val projected = projection.type ?: return@any false
                carriesNullability(projected)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the folded properties for a `CLASS` [KmClass] (T-078).
     *
     * Each [KmProperty] contributes its getter/setter JVM keys (via
     * `getterSignature`/`setterSignature`) to [KotlinProperties.hiddenMethods]
     * and its backing-field name (via `fieldSignature`) to `hiddenFields`;
     * the property itself lands in `properties` with its `val`/`var` shape and
     * metadata type. A property with no JVM signatures maps to nothing (it has
     * no projection to fold). Never throws: hostile metadata degrades to empty.
     */
    public fun propertiesFor(kmClass: KmClass): KotlinProperties {
        val properties = linkedMapOf<String, KotlinPropertyView>()
        val hiddenMethods = linkedSetOf<String>()
        val hiddenFields = linkedSetOf<String>()
        for (property in kmClass.properties) {
            try {
                val getter = property.getterSignature
                val setter = property.setterSignature
                if (getter == null && setter == null) continue
                // Skip synthetic/delegated shapes without a real accessor name.
                val getterKey = getter?.let { kotlinViewKey(it.name, it.descriptor) }
                val setterKey = setter?.let { kotlinViewKey(it.name, it.descriptor) }
                if (getterKey != null) hiddenMethods.add(getterKey)
                if (setterKey != null) hiddenMethods.add(setterKey)
                try {
                    property.fieldSignature?.let { field ->
                        // JvmFieldSignature carries name + descriptor; hide by name
                        // (fields are unique by name in ClassInfo lookups).
                        hiddenFields.add(field.name)
                    }
                } catch (_: Exception) {
                    // No field signature — accessors still fold.
                }
                val isVar = try {
                    property.isVar || setter != null
                } catch (_: Exception) {
                    setter != null
                }
                val displayType = try {
                    renderType(property.returnType)
                } catch (_: Exception) {
                    null
                }
                properties[property.name] = KotlinPropertyView(
                    propertyName = property.name,
                    isVar = isVar,
                    displayType = displayType,
                )
            } catch (_: Exception) {
                continue
            }
        }
        return KotlinProperties(properties, hiddenMethods, hiddenFields)
    }

    /**
     * Zero-based Kotlin parameter positions declaring a default value
     * (T-078): `KmValueParameter.declaresDefaultValue` per value parameter.
     * Empty when none default. Never throws.
     */
    public fun defaultArgIndices(function: KmFunction): Set<Int> {
        return try {
            function.valueParameters.mapIndexedNotNull { index, parameter ->
                try {
                    if (parameter.declaresDefaultValue) index else null
                } catch (_: Exception) {
                    null
                }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Counts the erased parameters in a JVM method descriptor. Malformed
     * descriptors read as `0`, never a throw — callers treat a mismatch as
     * "keep the JVM types".
     */
    public fun parameterArity(descriptor: String): Int {
        if (!descriptor.startsWith("(")) return 0
        val depth = descriptor.indexOf(')')
        if (depth < 0) return 0
        val parameters = descriptor.substring(1, depth)
        var count = 0
        var i = 0
        while (i < parameters.length) {
            when (val c = parameters[i]) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                    count++
                    i++
                }
                '[' -> {
                    // Array: skip every leading `[`, then one element type.
                    i++
                    while (i < parameters.length && parameters[i] == '[') i++
                    if (i >= parameters.length) return count
                    if (parameters[i] == 'L') {
                        val end = parameters.indexOf(';', i)
                        if (end < 0) return count
                        i = end + 1
                    } else {
                        i++
                    }
                    count++
                }
                'L' -> {
                    val end = parameters.indexOf(';', i)
                    if (end < 0) return count
                    i = end + 1
                    count++
                }
                else -> return count // hostile — stop rather than invent
            }
        }
        return count
    }

    /**
     * Whether a JVM descriptor ends in the erased `Continuation` parameter.
     * Checked textually (the parameter list is the text between the outer
     * parentheses; ASM's `Type` accessors throw `AssertionError` — not
     * `Exception` — on hostile inputs, so parsing is not worth it here).
     * Malformed descriptors read as `false`, never a throw.
     */
    public fun isContinuationTail(descriptor: String): Boolean {
        if (!descriptor.startsWith("(")) return false
        val depth = descriptor.indexOf(')')
        if (depth < 0) return false
        val parameters = descriptor.substring(1, depth)
        // The erased Continuation is exactly `Lkotlin/coroutines/Continuation;`
        // as the last parameter: a preceding `[` would make it an array of
        // Continuations, and a missing `L` prefix would make it a longer name
        // that merely ends the same way.
        val marker = "Lkotlin/coroutines/Continuation;"
        if (!parameters.endsWith(marker)) return false
        val before = parameters.dropLast(marker.length).lastOrNull() ?: return true
        return before != '['
    }

    /**
     * Renders a metadata type as Java-style text (`java.lang.String`,
     * `java.util.List<...>`, trailing `?` for nullable). Nullable primitives
     * box to their wrapper (`int` → `java.lang.Integer?`) so the spelling
     * matches the JVM erasure agents already see. Returns `null` when the
     * classifier has no Java spelling (an unmapped `kotlin.*` name, a nested
     * shape the converter cannot spell) — the caller keeps the JVM type.
     * Never throws.
     */
    public fun renderType(type: KmType): String? {
        // `classifier` is a lateinit property: real metadata always sets it,
        // but a hand-built container may not — degrade to the JVM text, never
        // a throw (the never-throws property pins this).
        val classifier = try {
            type.classifier
        } catch (_: Exception) {
            return null
        }
        val base = when (classifier) {
            is KmClassifier.Class -> mapClassName(classifier.name) ?: return null
            // A type variable is an `id` into the declaration's type-parameter
            // table, not a name — spelling it needs the container, so the JVM
            // text (which core already renders from the generic signature) wins.
            is KmClassifier.TypeParameter -> return null
            is KmClassifier.TypeAlias -> classifier.name.substringAfterLast('/')
        }
        val arguments = type.arguments.map { renderProjection(it) ?: return null }
        val applied = if (arguments.isEmpty()) base else "$base<${arguments.joinToString(", ")}>"
        return if (type.isNullable) "${boxPrimitive(applied)}?" else applied
    }

    /** Boxes a Java primitive keyword so a nullable spelling matches JVM erasure. */
    private fun boxPrimitive(applied: String): String = when (applied) {
        "boolean" -> "java.lang.Boolean"
        "byte" -> "java.lang.Byte"
        "char" -> "java.lang.Character"
        "short" -> "java.lang.Short"
        "int" -> "java.lang.Integer"
        "long" -> "java.lang.Long"
        "float" -> "java.lang.Float"
        "double" -> "java.lang.Double"
        else -> applied
    }

    private fun renderProjection(projection: KmTypeProjection): String? {
        val projected = projection.type ?: return "?"
        val rendered = renderType(projected) ?: return null
        return when (projection.variance) {
            KmVariance.INVARIANT -> rendered
            KmVariance.IN -> "? super $rendered"
            KmVariance.OUT -> "? extends $rendered"
            // `variance` is nullable (`*` projections carry none): the old
            // redundant `else` only ever caught null. Name it (T-052).
            null -> return null
        }
    }

    /**
     * Spells a metadata class name (`kotlin/String`, `a/b/Outer.Inner`) as a
     * binary name, or `null` when it has no Java spelling. Well-known
     * `kotlin.*` declarations map to their runtime types; other packages
     * convert separators (`/` → `.`, nesting `.` → `$`).
     */
    private fun mapClassName(name: String): String? = kotlinMappings[name] ?: run {
        if (name.startsWith("kotlin/")) return null
        val packageEnd = name.lastIndexOf('/')
        val packageName = if (packageEnd < 0) "" else name.substring(0, packageEnd).replace('/', '.')
        val classes = name.substring(packageEnd + 1).split('.')
        if (classes.any { it.isEmpty() }) return null
        val nested = classes.joinToString("$")
        if (packageName.isEmpty()) nested else "$packageName.$nested"
    }

    private val kotlinMappings: Map<String, String> = mapOf(
        "kotlin/Any" to "java.lang.Object",
        "kotlin/Boolean" to "boolean",
        "kotlin/Byte" to "byte",
        "kotlin/Char" to "char",
        "kotlin/Short" to "short",
        "kotlin/Int" to "int",
        "kotlin/Long" to "long",
        "kotlin/Float" to "float",
        "kotlin/Double" to "double",
        "kotlin/Unit" to "void",
        "kotlin/String" to "java.lang.String",
        "kotlin/CharSequence" to "java.lang.CharSequence",
        "kotlin/Number" to "java.lang.Number",
        "kotlin/Throwable" to "java.lang.Throwable",
        "kotlin/collections/List" to "java.util.List",
        "kotlin/collections/MutableList" to "java.util.List",
        "kotlin/collections/Map" to "java.util.Map",
        "kotlin/collections/MutableMap" to "java.util.Map",
        "kotlin/collections/Set" to "java.util.Set",
        "kotlin/collections/MutableSet" to "java.util.Set",
        "kotlin/collections/Collection" to "java.util.Collection",
        "kotlin/collections/MutableCollection" to "java.util.Collection",
        "kotlin/collections/Iterable" to "java.lang.Iterable",
        "kotlin/collections/MutableIterable" to "java.lang.Iterable",
        "kotlin/collections/Iterator" to "java.util.Iterator",
        "kotlin/collections/MutableIterator" to "java.util.Iterator",
    )
}
