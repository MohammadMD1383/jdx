package dev.jdx.core.diff

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.render.methodRefString

/**
 * Which declarations enter a snapshot — the question "is this the API a consumer
 * sees, or everything that is declared?" The default is the exported surface,
 * because that is what a dependency upgrade is judged on; [ALL] exists for the
 * other half of the job — refactors inside a jar, and mod/mixin work where a
 * private method's JVM name is the thing being targeted.
 */
public enum class ApiSurface(public val wireName: String) {
    /** `public`/`protected` types and members — the surface a consumer compiles against. */
    PUBLIC("public"),

    /** Every declared type and member, whatever its visibility. */
    ALL("all"),
    ;

    public companion object {
        /** The `--visibility` flag value, or `null` when the text is not a known surface. */
        public fun fromFlag(flag: String): ApiSurface? =
            entries.firstOrNull { it.wireName == flag.lowercase() }
    }
}

/**
 * A member's identity across two artifacts.
 *
 * Identity is deliberately **not** the full JVM descriptor. Two members are "the
 * same member" when they have the same name and the same *erased parameter list*,
 * so a changed return type is one `RETURN_TYPE_CHANGED` finding rather than a
 * removal plus an addition, and a covariant override is one member, not two
 * (the same identity [dev.jdx.core.resolve.MemberResolver] uses for override
 * collapsing). A changed *parameter* type genuinely is a different member — the
 * caller's descriptor no longer resolves — and is reported as a paired
 * `PARAMETER_TYPE_CHANGED`.
 */
public sealed interface ApiMemberKey {
    /** The JVM member name; `<init>` for constructors, `<clinit>` never appears. */
    public val name: String
}

/** A method or constructor, keyed by name plus erased parameter list (JVMS §4.3.3). */
public data class ApiMethodKey(
    override val name: String,
    public val parameterTypes: List<TypeName>,
) : ApiMemberKey {
    /** The erased parameter descriptor — `(Ljava/lang/String;I)` — the identity's text form. */
    public val parameterDescriptor: String
        get() = "(" + parameterTypes.joinToString("") { it.descriptor } + ")"
}

/** A field, keyed by name plus erased type: changing either makes it a different field. */
public data class ApiFieldKey(
    override val name: String,
    public val type: TypeName,
) : ApiMemberKey

/** One declared member, flattened to exactly the facts a diff compares. */
public sealed interface ApiMember {
    public val key: ApiMemberKey

    public val access: Access

    public val deprecated: Boolean

    public val annotations: List<AnnotationInfo>

    /**
     * The canonical reference of the member **in the artifact that declared it**
     * (D-016), computed once where the reader still has a real `MethodInfo` so
     * `jdx diff` spells a member exactly as `members`/`body`/`doc` do.
     */
    public val canonicalRef: String

    /**
     * The name the Kotlin source declares this member under, when it differs from
     * [ApiMemberKey.name]; `null` when the JVM name *is* the Kotlin name.
     *
     * This is what separates a JVM rename (`@JvmName`, `internal` mangling) from a
     * plain member removal: two members agreeing here while their keys differ are the
     * same Kotlin declaration under two names.
     */
    public val kotlinName: String?
}

/**
 * A method or constructor. Every fact here is bytecode truth: the erased
 * descriptor, the `Signature` attribute verbatim, and the access flags read
 * through [dev.jdx.core.model.Access] (which is why a bit-overlap mistake is
 * visible here — see [ApiSnapshot.of]).
 */
public data class ApiMethod(
    override val key: ApiMethodKey,
    override val access: Access,
    override val canonicalRef: String,
    public val returnType: TypeName,
    public val parameterNames: List<String?> = emptyList(),
    public val genericSignature: String? = null,
    public val throwsTypes: List<TypeName> = emptyList(),
    /** The rendered `AnnotationDefault` of an annotation-element method, else `null`. */
    public val annotationDefault: String? = null,
    public val varargs: Boolean = false,
    public val native: Boolean = false,
    public val synchronized: Boolean = false,
    /** The Kotlin view when it differs from the JVM projection, else `null` (D-077/T-078). */
    public val kotlin: KotlinMethodView? = null,
    override val deprecated: Boolean = false,
    override val annotations: List<AnnotationInfo> = emptyList(),
) : ApiMember {
    override val kotlinName: String? get() = kotlin?.displayName
}

/**
 * A field. The type lives in [ApiFieldKey] — it is part of the identity, so there
 * is no second copy of it here to drift.
 */
public data class ApiField(
    override val key: ApiFieldKey,
    override val access: Access,
    override val canonicalRef: String,
    public val genericSignature: String? = null,
    public val constantValue: String? = null,
    public val transient: Boolean = false,
    /** The folded Kotlin property when this field is one, else `null` (T-078). */
    public val kotlin: KotlinPropertyView? = null,
    override val deprecated: Boolean = false,
    override val annotations: List<AnnotationInfo> = emptyList(),
) : ApiMember {
    override val kotlinName: String? get() = kotlin?.propertyName
}

/**
 * One type, flattened for diffing. [members] is keyed by [ApiMemberKey] and
 * always sorted by key, so iteration order never depends on class-file order.
 */
public data class ApiType(
    public val binaryName: String,
    public val kind: TypeKind,
    public val access: Access,
    public val superclass: TypeName? = null,
    public val interfaces: List<TypeName> = emptyList(),
    public val members: Map<ApiMemberKey, ApiMember> = emptyMap(),
    public val deprecated: Boolean = false,
    public val isKotlin: Boolean = false,
    public val annotations: List<AnnotationInfo> = emptyList(),
) {
    /** The type's own canonical reference — its binary name (PROPOSAL.md §6). */
    public val canonicalRef: String get() = binaryName
}

/**
 * Everything one artifact exposes to a diff: a display label plus every type in
 * the chosen [ApiSurface]. Pure data with no IO — [of] is the only constructor,
 * and it reads a list of [ClassInfo] that `index` has already parsed with ASM
 * (never loaded, D-017).
 *
 * [artifact] is a *label*, not a path: it is what the report prints, so it must
 * be the file name or coordinate the user named, never an absolute path or a
 * content hash (determinism, AGENTS.md §2.5).
 */
public data class ApiSnapshot(
    public val artifact: String,
    public val types: Map<String, ApiType>,
) {
    /** Number of types compared — the denominator of "how much of this jar is even in scope". */
    public val typeCount: Int get() = types.size

    public companion object {
        /**
         * Flattens [classes] into a snapshot.
         *
         * Filters, in order: the type's own surface, then per member the
         * class initialiser (`<clinit>` is never API), then synthetic members
         * (see [includeSynthetic]), then the member's surface.
         *
         * Two deliberate choices, both documented because they surprise people:
         *
         * - **Bytecode is the truth, always.** Kotlin property accessors and `$default`
         *   stubs stay in the snapshot even though `members --view kotlin` folds them
         *   away (T-078). Folding would hide exactly the getter whose access changed,
         *   and the whole point of an upgrade diff is that the JVM name a mixin
         *   targets really moved.
         * - **Synthetic detection is context-driven** (`AccessFlag` reuses bits per
         *   declaration kind: `0x0040` is `VOLATILE` on a field and `BRIDGE` on a
         *   method). Reading the bridge bit off a field would drop every volatile
         *   field, so fields are tested for `SYNTHETIC` alone.
         */
        public fun of(
            artifact: String,
            classes: Iterable<ClassInfo>,
            surface: ApiSurface = ApiSurface.PUBLIC,
            includeSynthetic: Boolean = false,
        ): ApiSnapshot {
            val types = sortedMapOf<String, ApiType>()
            for (info in classes) {
                if (!inSurface(info.access, surface)) continue
                types[info.name.binaryName] = flatten(info, surface, includeSynthetic)
            }
            return ApiSnapshot(artifact = artifact, types = types)
        }

        private fun inSurface(access: Access, surface: ApiSurface): Boolean = when (surface) {
            ApiSurface.ALL -> true
            ApiSurface.PUBLIC -> access.visibility == Visibility.PUBLIC || access.visibility == Visibility.PROTECTED
        }

        private fun flatten(info: ClassInfo, surface: ApiSurface, includeSynthetic: Boolean): ApiType {
            val members = mutableListOf<Pair<ApiMemberKey, ApiMember>>()
            for (field in info.fields) {
                if (field.name == CLASS_INITIALISER) continue
                if (!includeSynthetic && field.access.has(AccessFlag.SYNTHETIC)) continue
                if (!inSurface(field.access, surface)) continue
                val key = ApiFieldKey(field.name, field.type)
                members.add(key to flattenField(info, field, key))
            }
            for (method in info.methods) {
                if (method.name == CLASS_INITIALISER) continue
                if (!includeSynthetic && isSyntheticMethod(method)) continue
                if (!inSurface(method.access, surface)) continue
                val key = ApiMethodKey(method.name, method.descriptor.parameters)
                members.add(key to flattenMethod(info, method, key))
            }
            return ApiType(
                binaryName = info.name.binaryName,
                kind = info.kind,
                access = info.access,
                superclass = info.superclass,
                interfaces = info.interfaces,
                members = sortedByKeyText(members),
                deprecated = info.deprecated,
                isKotlin = info.isKotlin,
                annotations = info.annotations,
            )
        }

        /** `SYNTHETIC` plus `BRIDGE` — the latter's bit is `VOLATILE` on fields, hence methods only. */
        private fun isSyntheticMethod(method: MethodInfo): Boolean =
            method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)

        private fun flattenField(info: ClassInfo, field: FieldInfo, key: ApiFieldKey): ApiField = ApiField(
            key = key,
            access = field.access,
            canonicalRef = SymbolRefPrinter.print(MemberSymbolRef(declaringType = info.name, name = key.name)),
            genericSignature = field.genericSignature?.signature,
            constantValue = field.constantValue,
            transient = field.access.has(AccessFlag.TRANSIENT),
            kotlin = info.kotlinProperties[key.name],
            deprecated = field.deprecated,
            annotations = field.annotations,
        )

        private fun flattenMethod(info: ClassInfo, method: MethodInfo, key: ApiMethodKey): ApiMethod {
            val view = info.kotlinMethodViews[kotlinViewKey(method.name, method.descriptor.descriptor)]
            // `methodRefString` states views never apply to constructors, and a
            // Kotlin constructor's display name is not a JVM name — honour that
            // invariant rather than re-deriving it here.
            val isConstructor = method.name == CONSTRUCTOR
            return ApiMethod(
                key = key,
                access = method.access,
                canonicalRef = methodRefString(
                    declaring = info.name,
                    member = method,
                    view = if (isConstructor) null else view,
                    disambiguateReturn = false,
                ),
                returnType = method.descriptor.returnType,
                parameterNames = method.parameterNames,
                genericSignature = method.genericSignature?.signature,
                throwsTypes = method.throwsTypes,
                annotationDefault = method.annotationDefault,
                varargs = method.access.has(AccessFlag.VARARGS),
                native = method.access.has(AccessFlag.NATIVE),
                synchronized = method.access.has(AccessFlag.SYNCHRONIZED),
                kotlin = if (isConstructor) null else view,
                deprecated = method.deprecated,
                annotations = method.annotations,
            )
        }
    }
}

/** The class initialiser. Never part of an API and never comparable (`<clinit>`). */
public const val CLASS_INITIALISER: String = "<clinit>"

/**
 * The supertypes every type has without being told: the roots the compiler and the
 * JVM supply, none of which is ever inside a third-party jar.
 *
 * `jdx diff` skips them when deciding whether a removed member might have been
 * inherited. Without that, the superclass `java.lang.Object` — which *every* ordinary
 * class has — would count as "supertype missing from the artifact" and put an
 * "inheritance not checked" caveat on every removal in every jar, which is noise
 * exactly where the report must be trusted. The list is closed and small: a type
 * outside the artifact that is *not* one of these still produces the caveat, which is
 * the case that genuinely needs it (a superclass living in another dependency).
 */
internal val UNIVERSAL_SUPERTYPES: Set<String> = setOf(
    "java.lang.Object",
    "java.lang.Enum",
    "java.lang.Record",
    "java.lang.Throwable",
    "java.lang.annotation.Annotation",
    "java.lang.constant.Constable",
    "java.lang.constant.ConstantDesc",
)

/** The constructor name, as the JVM spells it. */
public const val CONSTRUCTOR: String = "<init>"

/**
 * A total order over member keys. Keys are a sealed hierarchy, not `Comparable`,
 * and iterating a `Map` in insertion order would leak class-file order into the
 * output — so the snapshot and the differ both order through this one spelling.
 */
internal fun apiKeySortText(key: ApiMemberKey): String = when (key) {
    is ApiMethodKey -> "m:" + key.name + key.parameterDescriptor
    is ApiFieldKey -> "f:" + key.name + key.type.descriptor
}

/** [apiKeySortText]-ordered map, so a snapshot's iteration order is input-independent. */
internal fun <M : ApiMember> sortedByKeyText(
    members: List<Pair<ApiMemberKey, M>>,
): Map<ApiMemberKey, M> {
    val ordered = LinkedHashMap<ApiMemberKey, M>(members.size)
    for ((key, member) in members.sortedBy { apiKeySortText(it.first) }) ordered[key] = member
    return ordered
}

/**
 * `--severity`: how much of the report to show. Cumulative, like [DiffSeverity]
 * itself — [SUSPICIOUS] means "breaking *and* suspicious", not "suspicious only" —
 * because an agent filtering out breaking changes is never what anyone wants.
 *
 * The filter changes what is *printed*, never the counts: the summary always
 * describes the whole comparison, so a filtered report still says what it is not
 * showing.
 */
public enum class SeverityFilter(public val wireName: String, public val threshold: DiffSeverity) {
    /** Every finding (the default). */
    ALL("all", DiffSeverity.INFO),

    /** Breaking and suspicious findings. */
    SUSPICIOUS("suspicious", DiffSeverity.SUSPICIOUS),

    /** Breaking findings only — the "can I still ship against this?" view. */
    BREAKING("breaking", DiffSeverity.BREAKING),
    ;

    public companion object {
        /** The `--severity` flag value, or `null` when the text is not a known filter. */
        public fun fromFlag(flag: String): SeverityFilter? =
            entries.firstOrNull { it.wireName == flag.lowercase() }
    }
}
