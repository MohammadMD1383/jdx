package dev.jdx.core.gen

import dev.jdx.core.diff.ApiSnapshot
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of

/**
 * Generators for the API-diff substrate (issue #23, T-055; TESTING.md §4).
 *
 * Everything is generated as real [ClassInfo] and flattened through the production
 * [ApiSnapshot.of], so a property failure points at the whole pipeline (reader model
 * → snapshot filters → differ) rather than at a hand-written snapshot that could
 * never occur.
 *
 * Nastiness on purpose: colliding member keys (two methods with one name and arity,
 * which a real class file only gets from a compiler bug but a hostile artifact
 * certainly can), `<clinit>` next to `<init>`, the access-flag bit overlaps (`BRIDGE`
 * vs `VOLATILE`, `VARARGS` vs `TRANSIENT`), unicode and `$`-nested type names, empty
 * class lists, and every type kind including the Kotlin-only ones.
 */

/** An access mask over the bits a diff can see, overlaps included. */
private fun arbAccess(): Arb<Access> = Arb.int(0, 0xFFFF).map { Access(it) }

/** Real JDK names a supertype or a `throws` clause would name. */
private val NAMED_TYPES = listOf(
    "java.lang.String", "java.lang.Object", "java.util.List", "java.io.Serializable",
    "kotlin.String", "kotlin.collections.List", "java.lang.Cloneable", "java.lang.Runnable",
)

/** A type a member can mention: the shared type shapes plus real JDK names. */
private fun arbMemberTypeName(): Arb<TypeName> = Arb.choice(
    arbFieldTypeName(),
    Arb.of(NAMED_TYPES).map { typeNameFromBinaryName(it) },
)

/** A type a `throws` clause names — the checked/unchecked split decides a severity. */
private fun arbThrowsType(): Arb<TypeName> = Arb.of(
    listOf("java.io.IOException", "java.lang.RuntimeException", "java.lang.Error", "java.lang.Throwable"),
).map { typeNameFromBinaryName(it) }

/** The superclasses a generated type gets: one always in the artifact, one never. */
private val SUPERTYPE_NAMES = listOf("java.lang.Object", "java.util.AbstractList", "com.example.Base")

private fun arbAnnotation(): Arb<AnnotationInfo> = Arb.bind(
    Arb.of(listOf("java.lang.Override", "java.lang.Deprecated", "kotlin.Metadata", "com.example.Marker")),
    Arb.boolean(),
) { type, withValue ->
    AnnotationInfo(
        type = typeNameFromBinaryName(type),
        values = if (withValue) mapOf("forRemoval" to "true") else emptyMap(),
    )
}

fun arbMethodInfo(): Arb<MethodInfo> = Arb.bind(
    Arb.of(listOf("alpha", "beta", "gamma", "<init>", "<clinit>", "compute", "get", "set", "valueOf")),
    Arb.list(arbFieldTypeName(), 0..2),
    arbTypeName(),
    arbAccess(),
    Arb.list(arbAnnotation(), 0..2),
    Arb.list(arbThrowsType(), 0..1),
) { name, parameters, returnType, access, annotations, throwsTypes ->
    MethodInfo(
        name = name,
        descriptor = JvmDescriptor.Method(parameters = parameters, returnType = returnType),
        access = access,
        parameterNames = parameters.indices.map { "p$it" },
        throwsTypes = throwsTypes,
        annotations = annotations,
        deprecated = false,
        annotationDefault = if (name == "value") "1" else null,
    )
}

fun arbFieldInfo(): Arb<FieldInfo> = Arb.bind(
    Arb.of(listOf("COUNT", "name", "value", "serialVersionUID", "field", "TAG")),
    arbFieldTypeName(),
    arbAccess(),
    Arb.list(arbAnnotation(), 0..2),
    Arb.boolean(),
) { name, type, access, annotations, hasConstant ->
    FieldInfo(
        name = name,
        type = type,
        access = access,
        annotations = annotations,
        deprecated = false,
        constantValue = if (hasConstant) "1" else null,
    )
}

fun arbClassInfo(): Arb<ClassInfo> = Arb.bind(
    arbClassType(3),
    Arb.enum<TypeKind>(),
    arbAccess(),
    Arb.of(SUPERTYPE_NAMES),
    Arb.list(arbClassType(1), 0..2),
    Arb.list(arbFieldInfo(), 0..4),
    Arb.list(arbMethodInfo(), 0..5),
) { name, kind, access, superclassName, interfaces, fields, methods ->
    ClassInfo(
        name = name,
        kind = kind,
        access = access,
        // An interface has no superclass; that is the one shape where `null` is normal.
        superclass = if (kind == TypeKind.INTERFACE) null else typeNameFromBinaryName(superclassName),
        interfaces = interfaces,
        fields = fields,
        methods = methods,
        isKotlin = kind == TypeKind.OBJECT || kind == TypeKind.COMPANION,
    )
}

/** A whole snapshot, flattened exactly as the service flattens one. */
fun arbSnapshot(label: String, surface: ApiSurface = ApiSurface.ALL): Arb<ApiSnapshot> =
    Arb.list(arbClassInfo(), 0..4).map { classes ->
        ApiSnapshot.of(label, classes, surface, includeSynthetic = true)
    }

/**
 * One facet changed on one class, or nothing at all — the shape a real upgrade takes.
 * Built by mutating a generated [ClassInfo] rather than by generating two independent
 * class lists, so a "single-field change" really is one. [NoChange] is a real outcome
 * (the class may hold no field or method to change), so the property test has to
 * distinguish "mutated but invisible" from "mutated and reported".
 */
sealed interface ClassMutation {
    public val before: ClassInfo
    public val after: ClassInfo
}

private data class Changed(
    override val before: ClassInfo,
    override val after: ClassInfo,
) : ClassMutation

private data class Unchanged(override val before: ClassInfo) : ClassMutation {
    override val after: ClassInfo get() = before
}

private const val MUTATION_COUNT: Int = 8

fun arbClassMutation(): Arb<ClassMutation> = Arb.bind(
    arbClassInfo(),
    Arb.int(0, MUTATION_COUNT - 1),
) { original, which ->
    val fieldIndex = original.fields.indices.firstOrNull()
    val methodIndex = original.methods.indices.firstOrNull()
    fun changed(transform: (ClassInfo) -> ClassInfo) = Changed(original, transform(original))
    when (which) {
        0 -> changed { it.copy(access = Access(it.access.mask or AccessFlag.FINAL.mask)) }
        1 -> changed {
            it.copy(kind = if (it.kind == TypeKind.CLASS) TypeKind.INTERFACE else TypeKind.CLASS)
        }
        2 -> fieldIndex
            ?.let { index -> changed { info -> info.copy(fields = info.fields.filterIndexed { i, _ -> i != index }) } }
            ?: Unchanged(original)
        3 -> methodIndex
            ?.let { index -> changed { info -> info.copy(methods = info.methods.filterIndexed { i, _ -> i != index }) } }
            ?: Unchanged(original)
        4 -> fieldIndex
            ?.let { index ->
                val target = original.fields[index]
                val narrower = narrowOnce(target.access)
                if (narrower == target.access) {
                    Unchanged(original)
                } else {
                    changed { info ->
                        info.copy(
                            fields = info.fields.mapIndexed { i, field ->
                                if (i == index) field.copy(access = narrower) else field
                            },
                        )
                    }
                }
            }
            ?: Unchanged(original)
        5 -> methodIndex
            ?.let { index ->
                changed { info ->
                    info.copy(
                        methods = info.methods.mapIndexed { i, method ->
                            if (i == index) {
                                method.copy(
                                    descriptor = method.descriptor.copy(
                                        returnType = typeNameFromBinaryName("java.lang.CharSequence"),
                                    ),
                                )
                            } else {
                                method
                            }
                        },
                    )
                }
            }
            ?: Unchanged(original)
        6 -> methodIndex
            ?.let { index ->
                changed { info ->
                    val target = info.methods[index]
                    info.copy(
                        // A Kotlin view rides the *class*, keyed by JVM name + descriptor
                        // (D-077) — it is not a field on `MethodInfo`.
                        kotlinMethodViews = mapOf(
                            kotlinViewKey(target.name, target.descriptor.descriptor) to
                                KotlinMethodView(
                                    displayName = target.name + "Renamed",
                                    displayReturn = "kotlin.String?",
                                    defaultArgIndices = setOf(0),
                                ),
                        ),
                    )
                }
            }
            ?: Unchanged(original)
        else -> fieldIndex
            ?.let { index ->
                changed { info ->
                    val target = info.fields[index]
                    // `KotlinPropertyView`s are keyed by the backing field's name.
                    info.copy(
                        kotlinProperties = mapOf(
                            target.name to KotlinPropertyView(
                                propertyName = target.name,
                                isVar = true,
                                displayType = "kotlin.String?",
                            ),
                        ),
                    )
                }
            }
            ?: Unchanged(original)
    }
}

/**
 * One step *narrower* than [access] on the JLS ladder, or the access itself when it is
 * already private.
 *
 * Deliberately not "clear the visibility bits": a mask can hold `public` *and*
 * `private` at once (the reader stores the raw mask), and clearing bits then produces
 * a change that is not a narrowing at all — which is exactly the kind of difference a
 * diff is right to stay silent about.
 */
internal fun narrowOnce(access: Access): Access = when (access.visibility) {
    Visibility.PUBLIC -> Access(AccessFlag.PROTECTED.mask)
    Visibility.PROTECTED -> Access.NONE
    Visibility.PACKAGE_PRIVATE -> Access(AccessFlag.PRIVATE.mask)
    Visibility.PRIVATE -> Access(AccessFlag.PRIVATE.mask)
}

/** The rule each mutation index targets, for documentation and for a rule-coverage test. */
internal val MUTATION_TARGET_RULES: List<CompatRule> = listOf(
    CompatRule.TYPE_MADE_FINAL,
    CompatRule.TYPE_KIND_CHANGED,
    CompatRule.MEMBER_REMOVED,
    CompatRule.MEMBER_REMOVED,
    CompatRule.MEMBER_VISIBILITY_NARROWED,
    CompatRule.RETURN_TYPE_CHANGED,
    CompatRule.KOTLIN_NAME_CHANGED,
    CompatRule.KOTLIN_PROPERTY_BECAME_MUTABLE,
)

/**
 * Member names a real compiler would never emit but a hostile or obfuscated artifact
 * certainly holds: a JVM descriptor, angle brackets, a `$`, an empty-ish name, and
 * the constructors. The differ must survive all of them.
 */
fun arbHostileName(): Arb<String> = Arb.of(
    listOf(
        "a", "A", "_", "\$\$", "a\$b", "<init>", "<clinit>", "get", "set", "valueOf",
        "lambda\$main\$0", "access\$100", "a b", "a.b", "a:b", "a/b", "ünïcödé", "中", "0", "_1",
    ),
)
