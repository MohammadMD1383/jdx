package dev.jdx.sources

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.BaseTypeSignature
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSignature
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.VoidSignature
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.kotlinViewKey

/**
 * `SOURCES_VERSION_MISMATCH` detection (T-028, D-009, PROPOSAL.md §11.1).
 *
 * Bytecode is the skeleton, sources are the flesh: when a `-sources.jar`
 * disagrees with its binary jar about which members a type declares (a member
 * added, removed or renamed — a real and common packaging bug), `jdx` cannot
 * be lied to about the API, but it must say so. This compares one type's
 * bytecode members ([ClassInfo]) against the same type's source declarations
 * ([listJavaMembers]) and returns a structured [Warning] when they disagree,
 * or `null` when they agree.
 *
 * The comparison is deliberately lenient where the compiler legitimately
 * widens bytecode beyond sources, so matched jars never warn:
 *
 * - synthetic/bridge members and `<clinit>` never appear in sources;
 * - an implicit default constructor (no source `<init>` at all, exactly one
 *   bytecode `<init>`) is compiler-generated;
 * - record accessors, `toString`/`hashCode`/`equals` and the compact/canonical
 *   constructor pairing are implicit unless declared;
 * - enum `values()`/`valueOf(String)` are implicit; enum constructors carry
 *   hidden `(String, int)` name/ordinal parameters;
 * - inner-class constructors carry a hidden leading outer-`this` parameter;
 * - generic spellings (`U` vs erased `Object`), varargs (`String...` vs
 *   `String[]`) and annotations/generics on source spellings all normalise to
 *   the same key.
 *
 * Pure and total: hostile names, empty lists and odd spellings return a value,
 * never throw. Callers that cannot list sources ([JavaMemberList] absences)
 * skip the check rather than warning — a missing file is a different,
 * already-labelled degradation.
 */
public fun detectSourcesMismatch(
    target: ClassInfo,
    sourceMembers: List<DeclaredSourceMember>,
    sourcesName: String,
): Warning? {
    val binary = target.name.binaryName
    val bytecode = bytecodeEntriesOf(target)
    // Static initialisers are not declarations in either language — neither
    // side can name one, so neither side is compared on it.
    val remaining = sourceMembers.filterNot { it.name == "<clinit>" }.toMutableList()
    val missingFromSources = mutableListOf<String>()

    // An implicit default constructor: no source `<init>` at all and exactly
    // one bytecode `<init>` (a default, an inner default with its outer
    // parameter, or an enum default with name/ordinal) is compiler output.
    // `DefaultConstructorMarker` overloads are synthetic and never counted.
    val comparable = if (remaining.none { it.kind == SourceBodyKind.CONSTRUCTOR } &&
        bytecode.count { it.kind == EntryKind.CONSTRUCTOR && !isMarkerCtor(it) } == 1
    ) {
        bytecode.filterNot { it.kind == EntryKind.CONSTRUCTOR && !isMarkerCtor(it) }
    } else {
        bytecode
    }

    for (entry in comparable) {
        val match = remaining.indexOfFirst { matches(entry, it, target) }
        if (match >= 0) {
            remaining.removeAt(match)
        } else if (!isImplicitMember(target, entry)) {
            missingFromSources.add(renderBytecodeEntry(binary, entry))
        }
    }
    // Source leftovers are always explicit declarations, so every one is
    // evidence — there are no implicit source members to excuse.
    val extraInSources = remaining.map { renderSourceEntry(binary, it) }.sorted()

    if (missingFromSources.isEmpty() && extraInSources.isEmpty()) return null
    val message = buildString {
        if (extraInSources.isNotEmpty()) {
            append(sourcesName)
            append(" declares ")
            append(summarise(extraInSources))
            append(" which is absent from ")
            append(binary)
            append(". ")
        }
        if (missingFromSources.isNotEmpty()) {
            append(sourcesName)
            append(" omits ")
            append(summarise(missingFromSources.sorted()))
            append(" present in ")
            append(binary)
            append(". ")
        }
        append("Structure shown from bytecode.")
    }
    return Warning(code = WarningCode.SOURCES_VERSION_MISMATCH, message = message, subject = binary)
}

/** Bounded member summary: up to three refs, then a count. Deterministic. */
private fun summarise(refs: List<String>): String {
    if (refs.size <= 3) return refs.joinToString(", ")
    return refs.take(3).joinToString(", ") + " (+${refs.size - 3} more)"
}

/**
 * `@Metadata`-aware `SOURCES_VERSION_MISMATCH` detection for Kotlin (T-081,
 * D-009, PROPOSAL.md §11.1/§12.1).
 *
 * The JVM-name pairing of [detectSourcesMismatch] false-positives on Kotlin:
 * `@JvmName` renames, mangled `internal`s, `suspend` `Continuation`s,
 * folded properties and compiler-generated members (data `componentN`/`copy`,
 * value-class `-impl`s, `@JvmOverloads` overloads, companion `@JvmStatic`
 * bridges) all widen bytecode beyond what the `.kt` file declares. This pairs
 * over Kotlin declaration names instead — display names and property names
 * from the [ClassInfo] views — so stale Kotlin sources still warn while
 * matched ones stay silent.
 *
 * Bytecode entries are projected into declaration space before pairing:
 *
 * - JVM names map through `kotlinMethodViews` (`renamedForJvm` →
 *   `originalName`, `internalHelper$module` → `internalHelper`); unmatched
 *   members keep their JVM name (file facades have no carrier).
 * - `suspend` functions drop the trailing `Continuation` parameter (the
 *   source never spells it).
 * - folded accessors/backing fields (`kotlinHiddenMethods`/`kotlinHiddenFields`)
 *   drop out; one `FIELD` entry per property takes their place — but only for
 *   properties the sources actually declare as fields (body-declared ones
 *   like `greeting`); primary-constructor properties (`val name` in the
 *   header) pair through the `<init>` parameter lists instead.
 * - extension-receiver parameters pair positionally (the facade static's
 *   first argument; [listedKotlinMembers] counts the receiver).
 *
 * Compiler-generated leftovers that must not warn (checked only after a
 * member fails to pair, so declared members always win):
 *
 * - `$default` stubs, `componentN`, data-class `copy`, value-class `-impl`s
 *   and `equals-impl0` (kept as entries so the trio rule can evidence them,
 *   excused as leftovers), `<init>` overloads trailing
 *   `DefaultConstructorMarker`, generated `toString()`/`hashCode()`/
 *   `equals(Object)` for data/value classes, `$`-named holder fields and the
 *   `Companion`/`INSTANCE` holder fields;
 * - `@JvmOverloads` shorter overloads of a defaulted function;
 * - outer-class statics when a companion holder field evidences a companion
 *   object (`@JvmStatic` bridges, `const` fields) — companion-member
 *   staleness still warns under the `$Companion` binary, which pairs
 *   strictly;
 * - a value class's source `<init>` when bytecode shows no visible
 *   constructor (the real one is private synthetic).
 *
 * Parameter spelling is Kotlin-aware (`kotlinTypeKey`: `Int` → `int`,
 * `String?` → `String`, `Any` → `Object`), with one deliberate leniency: a
 * source spelling the detector cannot resolve to a JVM type (a `typealias`
 * like `UserId`, which erases to `String`) pairs by arity when the bytecode
 * side is a known type — aliases are user-defined per file and unresolvable
 * here, and warning on every aliased parameter would be noise. A stale
 * change between two known types still warns.
 *
 * Known limitations (documented, not silent bugs):
 *
 * - companion `const` vals live only in the outer bytecode, so a direct
 *   `$Companion` query may warn on them; outer queries are exact, and
 *   companion-member staleness still warns under `$Companion`;
 * - removing a body-declared property from sources is not detected (its
 *   hidden accessors are dropped unconditionally) — adding or renaming one
 *   still warns.
 *
 * Pure and total like [detectSourcesMismatch]: hostile names, empty lists
 * and odd spellings return a value, never throw.
 */
public fun detectKotlinSourcesMismatch(
    target: ClassInfo,
    sourceMembers: List<DeclaredSourceMember>,
    sourcesName: String,
): Warning? {
    // Not Kotlin: the JVM-name pairing is the honest one (file facades and
    // plain classes without metadata ride the same call path in callers).
    if (!target.isKotlin) return detectSourcesMismatch(target, sourceMembers, sourcesName)
    val binary = target.name.binaryName
    val remaining = sourceMembers.filterNot { it.name == "<clinit>" }.toMutableList()
    val sourceFields = remaining
        .filter { it.kind == SourceBodyKind.FIELD || it.kind == SourceBodyKind.ENUM_ENTRY }
        .map { it.name }
        .toSet()
    val bytecode = kotlinBytecodeEntriesOf(target, sourceFields)
    // A value class's constructor is private synthetic: bytecode shows none,
    // while sources declare the primary — that declaration is not evidence.
    if (bytecode.none { it.kind == EntryKind.CONSTRUCTOR }) {
        remaining.removeAll { it.kind == SourceBodyKind.CONSTRUCTOR }
    }
    val missingFromSources = mutableListOf<String>()

    // An implicit default constructor, mirroring the Java rule
    // (`DefaultConstructorMarker` overloads never count).
    val comparable = if (remaining.none { it.kind == SourceBodyKind.CONSTRUCTOR } &&
        bytecode.count { it.kind == EntryKind.CONSTRUCTOR && !isMarkerCtor(it) } == 1
    ) {
        bytecode.filterNot { it.kind == EntryKind.CONSTRUCTOR && !isMarkerCtor(it) }
    } else {
        bytecode
    }

    for (entry in comparable) {
        val match = remaining.indexOfFirst { matchesKotlin(target, entry, it) }
        if (match >= 0) {
            remaining.removeAt(match)
        } else if (!isKotlinImplicitMember(target, entry, comparable)) {
            missingFromSources.add(renderBytecodeEntry(binary, entry))
        }
    }
    val extraInSources = remaining.map { renderSourceEntry(binary, it) }.sorted()

    if (missingFromSources.isEmpty() && extraInSources.isEmpty()) return null
    val message = buildString {
        if (extraInSources.isNotEmpty()) {
            append(sourcesName)
            append(" declares ")
            append(summarise(extraInSources))
            append(" which is absent from ")
            append(binary)
            append(". ")
        }
        if (missingFromSources.isNotEmpty()) {
            append(sourcesName)
            append(" omits ")
            append(summarise(missingFromSources.sorted()))
            append(" present in ")
            append(binary)
            append(". ")
        }
        append("Structure shown from bytecode.")
    }
    return Warning(code = WarningCode.SOURCES_VERSION_MISMATCH, message = message, subject = binary)
}

/** One type's bytecode projected into Kotlin declaration space for pairing. */
private fun kotlinBytecodeEntriesOf(
    target: ClassInfo,
    sourceFields: Set<String>,
): List<BytecodeEntry> {
    val fields = target.fields
        // Plain-named synthetics are kept (unlike Java): `inline`/`reified`
        // implementations are synthetic yet source-declared; `$`-named
        // synthetics (`$default` stubs, delegates, bridges) never are.
        .filterNot { it.access.has(AccessFlag.SYNTHETIC) && '$' in it.name }
        .filterNot { it.name in target.kotlinHiddenFields }
        .map {
            BytecodeEntry(
                EntryKind.FIELD,
                it.name,
                emptyList(),
                null,
                isStatic = it.access.has(AccessFlag.STATIC),
            )
        }
    val methods = target.methods.mapNotNull { method ->
        if (method.name == "<clinit>") return@mapNotNull null
        if (method.access.has(AccessFlag.BRIDGE)) return@mapNotNull null
        if (method.access.has(AccessFlag.SYNTHETIC) && '$' in method.name) return@mapNotNull null
        if (kotlinViewKey(method.name, method.descriptor.descriptor) in target.kotlinHiddenMethods) {
            return@mapNotNull null
        }
        // Value-class implementations pair with nothing (a source-declared
        // `toString` pairs with the instance method instead) — but they are
        // kept as entries so the trio rule below can evidence them.
        val kind = if (method.name == "<init>") EntryKind.CONSTRUCTOR else EntryKind.METHOD
        val view = target.kotlinMethodViews[kotlinViewKey(method.name, method.descriptor.descriptor)]
        val params = method.descriptor.parameters
        val effective = if (view?.stripAppliesTo(params) == true) params.dropLast(1) else params
        BytecodeEntry(
            kind = kind,
            name = view?.displayName ?: method.name,
            erasedKeys = effective.map(::bytecodeTypeKey),
            genericKeys = method.genericSignature?.parameters?.map(::signatureTypeKey),
            isStatic = method.access.has(AccessFlag.STATIC),
            jvmName = method.name,
        )
    }
    // Property rows for body-declared properties only (see the KDoc on
    // [detectKotlinSourcesMismatch]): primary-constructor properties pair
    // through the `<init>` parameter lists, so a property without a
    // same-named source field gets no row (its hidden field is already
    // dropped above).
    val properties = target.kotlinProperties.keys
        .filter { it in sourceFields }
        .map { BytecodeEntry(EntryKind.FIELD, it, emptyList(), null, isStatic = false) }
    return fields + methods + properties
}

/**
 * Whether one Kotlin source declaration pairs with one declaration-space
 * bytecode member: same kind, same Kotlin name, compatible parameters
 * ([kotlinTypeKey] both spellings onto JVM keys).
 */
private fun matchesKotlin(target: ClassInfo, entry: BytecodeEntry, source: DeclaredSourceMember): Boolean {
    if (entry.kind != sourceKindOf(source.kind)) return false
    if (entry.name != source.name) return false
    if (entry.kind == EntryKind.FIELD) return true
    val wanted = source.parameterTypes.map(::kotlinTypeKey)
    if (entry.erasedKeys.size == wanted.size && kotlinParamsMatch(entry.erasedKeys, wanted)) return true
    val generic = entry.genericKeys
    if (generic != null && generic.size == wanted.size && kotlinParamsMatch(generic, wanted)) return true
    // Inner-class constructors carry a hidden leading outer-`this`, enum
    // constructors a `(String, int)` name/ordinal pair — neither spelled in
    // `.kt`, same as Java (shared helper over the same entry shape).
    val stripped = strippedParameters(target, entry)
    if (stripped != null && stripped.size == wanted.size && kotlinParamsMatch(stripped, wanted)) return true
    return false
}

/**
 * Per-parameter pairing with the typealias leniency: equal keys pair, and an
 * unresolvable source spelling (a `typealias`, invisible to bytecode) pairs
 * against a known JVM type — anything else (two known types disagreeing, two
 * unknown names disagreeing) does not.
 */
private fun kotlinParamsMatch(erased: List<String>, wanted: List<String>): Boolean {
    if (erased.size != wanted.size) return false
    return erased.zip(wanted).all { (have, want) ->
        have == want || (want !in KNOWN_JVM_KEYS && have in KNOWN_JVM_KEYS)
    }
}

/** JVM type keys a source spelling can never legitimately alias-resolve from. */
private val KNOWN_JVM_KEYS: Set<String> = setOf(
    "boolean", "byte", "char", "short", "int", "long", "float", "double", "void",
    "Object", "String", "CharSequence", "Number", "Throwable",
    "List", "Map", "Set", "Collection", "Iterable", "Iterator",
)

/**
 * Compiler-generated Kotlin leftovers that must not warn (checked only for
 * members that failed to pair, so declared members always win):
 *
 * - `$default` stubs, data `componentN`/`copy`, value-class `-impl`s and
 *   `equals-impl0`, `DefaultConstructorMarker` constructor overloads,
 *   generated `toString`/`hashCode`/`equals` (only when data/value
 *   generation is evidenced), `$`-named holder fields and the
 *   `Companion`/`INSTANCE` holder fields;
 * - `@JvmOverloads` shorter overloads of a defaulted function (same Kotlin
 *   name, fewer parameters than the full declaration);
 * - outer statics when a companion holder field evidences a companion object
 *   (`@JvmStatic` bridges, `const` fields — checked strictly under the
 *   `$Companion` binary instead).
 */
private fun isKotlinImplicitMember(
    target: ClassInfo,
    entry: BytecodeEntry,
    comparable: List<BytecodeEntry>,
): Boolean {
    if (isImplicitMember(target, entry)) return true
    if (entry.kind == EntryKind.FIELD) {
        if (entry.name == "Companion" || entry.name == "INSTANCE") return true
        if ('$' in entry.name) return true
        // `const` vals live as outer statics, declared in the companion
        // object (checked strictly under the `$Companion` binary instead).
        if (entry.isStatic && hasCompanionHolder(target)) return true
        return false
    }
    if (entry.kind == EntryKind.CONSTRUCTOR) {
        // The `@JvmOverloads`/default-args synthetic constructor: always
        // compiler output, never a source declaration.
        if (entry.erasedKeys.lastOrNull() == "DefaultConstructorMarker") return true
        return false
    }
    val name = entry.name
    if ("\$default" in name) return true
    if (entry.jvmName.endsWith("-impl") || entry.jvmName == "equals-impl0") return true
    if (COMPONENT_N.matchEntire(name) != null) return true
    val generatedTrio = comparable.any {
        COMPONENT_N.matchEntire(it.name) != null ||
            it.jvmName.endsWith("-impl") || it.jvmName == "equals-impl0"
    }
    if (name == "copy" && comparable.any { COMPONENT_N.matchEntire(it.name) != null }) return true
    // `toString`/`hashCode`/`equals` are generated for data/value classes
    // (evidenced above) — but a plain Kotlin class that declares them is
    // checked strictly, so dropping one from sources still warns.
    if (generatedTrio && (name == "toString" || name == "hashCode") && entry.erasedKeys.isEmpty()) return true
    if (generatedTrio && name == "equals" && entry.erasedKeys == listOf("Object")) return true
    if (target.kind == TypeKind.ENUM && name == "values" && entry.erasedKeys.isEmpty()) return true
    if (target.kind == TypeKind.ENUM && name == "valueOf" && entry.erasedKeys == listOf("String")) return true
    // A shorter overload of a defaulted function (`withDefault(int)` beside
    // `withDefault(int, String = ...)`): the sources declare one function.
    if (entry.erasedKeys.size < (comparable.filter { it.kind == EntryKind.METHOD && it.name == name }
            .maxOfOrNull { it.erasedKeys.size } ?: entry.erasedKeys.size) &&
        target.kotlinMethodViews.values.any { it.displayName == name && it.defaultArgIndices.isNotEmpty() }
    ) {
        return true
    }
    // `@JvmStatic` bridges and `const` statics live in the outer bytecode
    // but are declared in the companion object: excused here when a holder
    // field evidences the companion, checked strictly under `$Companion`.
    if (entry.isStatic && hasCompanionHolder(target)) return true
    return false
}

private val COMPONENT_N: Regex = Regex("component\\d+")

/** Whether a constructor entry is the default-args synthetic overload. */
private fun isMarkerCtor(entry: BytecodeEntry): Boolean =
    entry.kind == EntryKind.CONSTRUCTOR && entry.erasedKeys.lastOrNull() == "DefaultConstructorMarker"

/** Whether the bytecode evidences a companion object (its holder field). */
private fun hasCompanionHolder(target: ClassInfo): Boolean =
    target.fields.any { field ->
        field.access.has(AccessFlag.STATIC) &&
            (field.name == "Companion" || (field.type as? TypeName.ClassType)?.simpleName == field.name)
    }

/** Member kinds for pairing; source `ENUM_ENTRY` constants are bytecode fields. */
private enum class EntryKind { METHOD, CONSTRUCTOR, FIELD }

/** One bytecode member reduced to its pairing key. */
private data class BytecodeEntry(
    val kind: EntryKind,
    /** Declaration-space name (Kotlin display name when mapped, else JVM). */
    val name: String,
    /** Erased descriptor keys, e.g. `[Object]`, `[String]` (`String...`/`[]` collapsed). */
    val erasedKeys: List<String>,
    /** Generic-signature keys when present (`U` for `U identity(U)`), else null. */
    val genericKeys: List<String>?,
    /** Whether the member is static — record accessors never are. */
    val isStatic: Boolean,
    /** JVM name before Kotlin display-name mapping (equal to [name] for Java). */
    val jvmName: String = name,
)

/** Declared (non-synthetic, non-`<clinit>`) bytecode members in declaration order. */
private fun bytecodeEntriesOf(target: ClassInfo): List<BytecodeEntry> {
    val fields = target.fields
        .filterNot { it.access.has(AccessFlag.SYNTHETIC) }
        .map { BytecodeEntry(EntryKind.FIELD, it.name, emptyList(), null, isStatic = false) }
    val methods = target.methods.mapNotNull { method ->
        if (method.name == "<clinit>") return@mapNotNull null
        // The bridge bit shares its mask with `VOLATILE`, so it is only
        // meaningful on methods — fields check `SYNTHETIC` alone (as in
        // `MemberResolver` and `JdxService.isSyntheticMember`).
        if (method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)) {
            return@mapNotNull null
        }
        val kind = if (method.name == "<init>") EntryKind.CONSTRUCTOR else EntryKind.METHOD
        BytecodeEntry(
            kind = kind,
            name = method.name,
            erasedKeys = method.descriptor.parameters.map(::bytecodeTypeKey),
            genericKeys = method.genericSignature?.parameters?.map(::signatureTypeKey),
            isStatic = method.access.has(AccessFlag.STATIC),
        )
    }
    return fields + methods
}

/**
 * Whether one source declaration pairs with one bytecode member: same kind
 * (constructors pair only with constructors, fields — and enum entries — only
 * with fields), same name, and compatible parameters.
 */
private fun matches(entry: BytecodeEntry, source: DeclaredSourceMember, target: ClassInfo): Boolean {
    if (entry.kind != sourceKindOf(source.kind)) return false
    if (entry.name != source.name) return false
    val wanted = source.parameterTypes.map(::sourceTypeKey)
    if (entry.kind == EntryKind.FIELD) return true
    // A record compact constructor declares no parameters for the canonical
    // one the compiler emits — arity cannot judge it.
    if (entry.kind == EntryKind.CONSTRUCTOR && target.kind == TypeKind.RECORD && wanted.isEmpty()) {
        return true
    }
    if (entry.erasedKeys.size == wanted.size && entry.erasedKeys == wanted) return true
    val generic = entry.genericKeys
    if (generic != null && generic.size == wanted.size && generic == wanted) return true
    // Inner-class and enum constructors carry hidden leading parameters the
    // source never spells: the outer `this`, or enum name/ordinal.
    val stripped = strippedParameters(target, entry)
    if (stripped != null && stripped.size == wanted.size && stripped == wanted) return true
    return false
}

private fun sourceKindOf(kind: SourceBodyKind): EntryKind = when (kind) {
    SourceBodyKind.METHOD -> EntryKind.METHOD
    SourceBodyKind.CONSTRUCTOR -> EntryKind.CONSTRUCTOR
    SourceBodyKind.FIELD, SourceBodyKind.ENUM_ENTRY -> EntryKind.FIELD
}

/**
 * The bytecode parameter keys minus hidden leading parameters, or null when
 * the shape does not apply: enum constructors drop `(String, int)`
 * name/ordinal; nested-class constructors drop a leading outer-`this` when it
 * names the enclosing class. Static-nested constructors match exactly, so the
 * full-keys check above already paired them — this only widens, never narrows.
 */
private fun strippedParameters(target: ClassInfo, entry: BytecodeEntry): List<String>? {
    if (entry.kind != EntryKind.CONSTRUCTOR) return null
    val keys = entry.erasedKeys
    if (target.kind == TypeKind.ENUM && keys.size >= 2 && keys[0] == "String" && keys[1] == "int") {
        return keys.drop(2)
    }
    val outer = target.outerClass
    if (outer != null && keys.isNotEmpty() && keys[0] == outer.simpleName) {
        return keys.drop(1)
    }
    return null
}

/**
 * Compiler-generated leftovers that must not warn: record accessors and the
 * `toString`/`hashCode`/`equals` trio (implicit unless declared — and declared
 * ones already matched above, so leftovers here are implicit), and enum
 * `values()`/`valueOf(String)` (which `javac` forbids redeclaring, so they can
 * never be stale). Record accessors are instance methods sharing a component
 * field's name; a static zero-arg method with a field's name is user code and
 * still mismatches.
 */
private fun isImplicitMember(target: ClassInfo, entry: BytecodeEntry): Boolean {
    if (entry.kind == EntryKind.FIELD) return false
    if (target.kind == TypeKind.RECORD && entry.kind == EntryKind.METHOD && !entry.isStatic) {
        if (entry.erasedKeys.isEmpty() &&
            (entry.name == "toString" || entry.name == "hashCode" || target.fields.any { it.name == entry.name })
        ) {
            return true
        }
        if (entry.name == "equals" && entry.erasedKeys == listOf("Object")) return true
    }
    if (target.kind == TypeKind.ENUM && entry.kind == EntryKind.METHOD) {
        if (entry.name == "values" && entry.erasedKeys.isEmpty()) return true
        if (entry.name == "valueOf" && entry.erasedKeys == listOf("String")) return true
    }
    return false
}

private fun renderBytecodeEntry(binary: String, entry: BytecodeEntry): String {
    val params = entry.erasedKeys.joinToString(", ")
    return if (entry.kind == EntryKind.FIELD) "$binary#${entry.name}" else "$binary#${entry.name}($params)"
}

private fun renderSourceEntry(binary: String, source: DeclaredSourceMember): String {
    val params = source.parameterTypes.map(::sourceTypeKey).joinToString(", ")
    return when (sourceKindOf(source.kind)) {
        EntryKind.FIELD -> "$binary#${source.name}"
        else -> "$binary#${source.name}($params)"
    }
}

/** Erased bytecode type to pairing key: arrays/varargs collapse to the element. */
private fun bytecodeTypeKey(type: TypeName): String = when (type) {
    is TypeName.ArrayType -> bytecodeTypeKey(type.elementType)
    is TypeName.PrimitiveType -> type.simpleName
    is TypeName.ClassType -> typeKeyOf(type.simpleName)
}

/** Generic-signature type to pairing key: type variables keep their name. */
private fun signatureTypeKey(signature: TypeSignature): String = when (signature) {
    is TypeVariableSignature -> signature.name
    is BaseTypeSignature -> signature.primitive.keyword
    is ArrayTypeSignature -> signatureTypeKey(signature.elementType)
    is ClassTypeSignature -> typeKeyOf(signature.simpleName)
    is VoidSignature -> "void"
}
