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
     * functions (stripped `Continuation`, `suspend` keyword, metadata return)
     * and functions with default args (T-078 `= ...` markers).
     * Same-name non-suspend functions without defaults, constructors (which live on
     * [KmClass.constructors], never [KmClass.functions]) and `$default` stubs
     * (synthetic, no metadata entry) map to nothing.
     */
    public fun viewsFor(kmClass: KmClass): Map<String, KotlinMethodView> {
        val views = linkedMapOf<String, KotlinMethodView>()
        for (function in kmClass.functions) {
            val jvm = function.signature ?: continue
            val suspend = function.isSuspend
            val strip = suspend && isContinuationTail(jvm.descriptor)
            val displayReturn = if (suspend) renderType(function.returnType) else null
            val renamed = function.name != jvm.name
            val defaults = defaultArgIndices(function)
            if (!renamed && !strip && displayReturn == null && !suspend && defaults.isEmpty()) continue
            // A `suspend` whose return survives erasure unchanged still marks.
            views[kotlinViewKey(jvm.name, jvm.descriptor)] = KotlinMethodView(
                displayName = function.name,
                stripTrailingContinuation = strip,
                displayReturn = displayReturn,
                markSuspend = suspend,
                defaultArgIndices = defaults,
            )
        }
        return views
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
     * `java.util.List<...>`, trailing `?` for nullable). Returns `null` when
     * the classifier has no Java spelling (an unmapped `kotlin.*` name, a
     * nested shape the converter cannot spell) — the caller keeps the JVM
     * type. Never throws.
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
            else -> return null
        }
        val arguments = type.arguments.map { renderProjection(it) ?: return null }
        val applied = if (arguments.isEmpty()) base else "$base<${arguments.joinToString(", ")}>"
        return if (type.isNullable) "$applied?" else applied
    }

    private fun renderProjection(projection: KmTypeProjection): String? {
        val projected = projection.type ?: return "?"
        val rendered = renderType(projected) ?: return null
        return when (projection.variance) {
            KmVariance.INVARIANT -> rendered
            KmVariance.IN -> "? super $rendered"
            KmVariance.OUT -> "? extends $rendered"
            else -> return null
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
