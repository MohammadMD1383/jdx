package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.TypeName

/**
 * Kotlin declaration kinds the T-039 PSI seam surfaces. Only member-like
 * declarations are queryable: [CLASS] and [OBJECT] are navigation scopes,
 * never results.
 */
public enum class KotlinDeclKind {
    FUNCTION,
    PROPERTY,
    CONSTRUCTOR,
    CLASS,
    OBJECT,
    ENUM_ENTRY,
}

/**
 * One value parameter of a Kotlin function, constructor or primary-constructor
 * header: the declared [name] (`""` when anonymous), the source spelling of
 * its type ([typeText], `null` when elided), and whether it declares a
 * default value (`= ...`).
 */
public data class KotlinParam(
    public val name: String,
    public val typeText: String?,
    public val hasDefault: Boolean,
)

/**
 * One Kotlin declaration with its PSI text range: 0-based
 * `[startOffset, endOffset)` offsets into the exact text that was parsed.
 * Line numbers are derived from those offsets, so slices are ground-truth
 * bytes, never pretty-printed — the same contract as [SourceBody].
 *
 * [params] are the value parameters (functions, secondary and explicit
 * primary constructors, class primary headers); [receiverType] is the
 * extension-receiver spelling (`fun Foo.bar()`), counted as a leading
 * parameter when matching JVM-arity queries. [children] are the nested
 * declarations of a class/object; [primaryCtor] is the explicit primary
 * constructor (`null` when implicit — an implicit `<init>` has no source
 * counterpart, mirroring the Java seam). [docText] is the KDoc inner content
 * (delimiters gone, leading stars intact — the shape `core`'s
 * `renderJavadoc` reads), `null` when absent or blank.
 */
public data class KotlinDecl(
    public val kind: KotlinDeclKind,
    public val name: String,
    public val params: List<KotlinParam> = emptyList(),
    public val returnType: String? = null,
    public val receiverType: String? = null,
    public val isSuspend: Boolean = false,
    public val startOffset: Int,
    public val endOffset: Int,
    public val docText: String?,
    public val docStartOffset: Int?,
    public val docEndOffset: Int?,
    public val children: List<KotlinDecl> = emptyList(),
    public val primaryCtor: KotlinDecl? = null,
    public val isCompanion: Boolean = false,
)

/**
 * One parsed `.kt` file: its package plus top-level declarations in file
 * order. Produced by [KotlinSourceParser.parseKotlin] (real PSI ranges when
 * the sidecar is installed); every query below is pure over this model, so
 * tier 1 owns the matching laws without a compiler.
 */
public data class KotlinFile(
    public val packageName: String,
    public val declarations: List<KotlinDecl>,
)

/**
 * Kotlin member bodies + KDoc over the T-038 loader seam (T-039, last T-036
 * slice): `.kt` member bodies and KDoc with real PSI ranges (PROPOSAL.md
 * §12.2). Stays bytecode-authoritative (D-009): overload ambiguity is decided
 * from bytecode first by the caller; these functions only slice the winning
 * root's paired `.kt` text. Property-aware (a `nickname` query slices the
 * `KtProperty`, never the synthetic getter). Never throws; a missing sidecar
 * reads as [JavaBodyResult.ParserUnavailable] (the caller degrades to
 * decompile/javap), never a failure.
 *
 * Results reuse the Java seam types ([SourceBody], [SourceDoc],
 * [DeclaredSourceMember]) so renderers and provenance stay shared: a Kotlin
 * property slices as [SourceBodyKind.FIELD], a constructor as
 * [SourceBodyKind.CONSTRUCTOR], everything else as [SourceBodyKind.METHOD].
 */
public fun findKotlinBodies(
    root: SourceRoot,
    ref: MemberSymbolRef,
    parser: KotlinSourceParser,
    aka: Set<String> = emptySet(),
): JavaBodyResult {
    // No availability pre-check: the resolution inside distinguishes "no
    // `.kt` here" (`NoSource`, keep the Java answer) from "`.kt` here but
    // no usable PSI" (`ParserUnavailable`, degrade).
    return try {
        findKotlinBodiesOrThrow(root, ref, parser, aka)
    } catch (e: Exception) {
        JavaBodyResult.ParseError(
            "source read error: Kotlin sources for ${ref.declaringType.binaryName} unreadable: " +
                (e.message ?: e.javaClass.simpleName),
        )
    }
}

private fun findKotlinBodiesOrThrow(
    root: SourceRoot,
    ref: MemberSymbolRef,
    parser: KotlinSourceParser,
    aka: Set<String>,
): JavaBodyResult {
    val binary = ref.declaringType.binaryName
    if (binary.isBlank()) return JavaBodyResult.NoSource
    val path = findKotlinSourcePath(root, binary, parser) ?: return JavaBodyResult.NoSource
    // A resolved path without usable PSI degrades (the scan above only
    // returns unconfirmed paths in that case); no path at all stays a miss.
    if (!parser.available) return JavaBodyResult.ParserUnavailable(parser.detail)
    val text = readKotlinText(root, path) ?: return JavaBodyResult.ParseError(
        "source read error: cannot read $path",
    )
    val parsed = when (val parsed = parser.parseKotlin(text, path.substringAfterLast('/'))) {
        is KotlinParse.Parsed -> parsed.file
        is KotlinParse.Unavailable -> return JavaBodyResult.ParserUnavailable(parsed.detail)
        is KotlinParse.Failed -> return JavaBodyResult.ParseError(
            "source read error: $path does not parse: ${parsed.message}",
        )
    }
    val scope = navigateKotlin(parsed, binary) ?: return JavaBodyResult.MemberNotFound
    val matches = narrowKotlinBySignature(matchKotlinByName(scope, ref.name, aka), ref)
    if (matches.isEmpty()) return JavaBodyResult.MemberNotFound
    val lines = splitKotlinLines(text)
    val bodies = matches.mapNotNull { sliceKotlinBody(path, lines, text, it) }
    if (bodies.isEmpty()) {
        return JavaBodyResult.ParseError(
            "source read error: $path has no position for ${ref.name}",
        )
    }
    return JavaBodyResult.Found(bodies)
}

/**
 * Every documented Kotlin source overload matching [ref]; several when
 * under-specified. Undocumented declarations resolve to
 * [JavaDocResult.MemberNotFound] (not empty `Found`) so callers fall through
 * to supertype lookup without a second emptiness check — mirroring
 * [findMemberDocs].
 */
public fun findKotlinMemberDocs(
    root: SourceRoot,
    ref: MemberSymbolRef,
    parser: KotlinSourceParser,
    aka: Set<String> = emptySet(),
): JavaDocResult {
    // No availability pre-check (see [findKotlinBodies]).
    return try {
        val binary = ref.declaringType.binaryName
        if (binary.isBlank()) return JavaDocResult.NoSource
        val path = findKotlinSourcePath(root, binary, parser) ?: return JavaDocResult.NoSource
        if (!parser.available) return JavaDocResult.ParserUnavailable(parser.detail)
        val text = readKotlinText(root, path) ?: return JavaDocResult.ParseError(
            "source read error: cannot read $path",
        )
        val parsed = when (val parsed = parser.parseKotlin(text, path.substringAfterLast('/'))) {
            is KotlinParse.Parsed -> parsed.file
            is KotlinParse.Unavailable -> return JavaDocResult.ParserUnavailable(parsed.detail)
            is KotlinParse.Failed -> return JavaDocResult.ParseError(
                "source read error: $path does not parse: ${parsed.message}",
            )
        }
        val scope = navigateKotlin(parsed, binary) ?: return JavaDocResult.MemberNotFound
        val matches = narrowKotlinBySignature(matchKotlinByName(scope, ref.name, aka), ref)
        if (matches.isEmpty()) return JavaDocResult.MemberNotFound
        val lines = splitKotlinLines(text)
        val docs = matches.mapNotNull { sliceKotlinDoc(path, lines, text, it) }
        if (docs.isEmpty()) JavaDocResult.MemberNotFound else JavaDocResult.Found(docs)
    } catch (e: Exception) {
        JavaDocResult.ParseError(
            "source read error: Kotlin sources for ${ref.declaringType.binaryName} unreadable: " +
                (e.message ?: e.javaClass.simpleName),
        )
    }
}

/**
 * A Kotlin type's own KDoc, or [JavaDocResult.TypeUndocumented] when absent.
 * File facades have no file-doc in v1 (their members carry the docs).
 */
public fun findKotlinTypeDoc(
    root: SourceRoot,
    binaryName: String,
    parser: KotlinSourceParser,
): JavaDocResult {
    // No availability pre-check (see [findKotlinBodies]).
    return try {
        if (binaryName.isBlank()) return JavaDocResult.NoSource
        val path = findKotlinSourcePath(root, binaryName, parser) ?: return JavaDocResult.NoSource
        if (!parser.available) return JavaDocResult.ParserUnavailable(parser.detail)
        val text = readKotlinText(root, path) ?: return JavaDocResult.ParseError(
            "source read error: cannot read $path",
        )
        val parsed = when (val parsed = parser.parseKotlin(text, path.substringAfterLast('/'))) {
            is KotlinParse.Parsed -> parsed.file
            is KotlinParse.Unavailable -> return JavaDocResult.ParserUnavailable(parsed.detail)
            is KotlinParse.Failed -> return JavaDocResult.ParseError(
                "source read error: $path does not parse: ${parsed.message}",
            )
        }
        val target = navigateKotlinTarget(parsed, binaryName) ?: return JavaDocResult.TypeNotFound
        val doc = sliceKotlinTypeDoc(path, splitKotlinLines(text), text, target)
            ?: return JavaDocResult.TypeUndocumented
        JavaDocResult.Found(listOf(doc))
    } catch (e: Exception) {
        JavaDocResult.ParseError(
            "source read error: Kotlin sources for $binaryName unreadable: " +
                (e.message ?: e.javaClass.simpleName),
        )
    }
}

/**
 * Every member the declaring type's `.kt` file declares, in file order —
 * the Kotlin side of the T-028 pairing. Functions list with source
 * parameter spellings, constructors (explicit primary first, then
 * secondaries) as `<init>`, properties and enum entries as fields.
 * Synthetic members (`$default` stubs, accessors, `componentN`/`copy`)
 * never appear — they exist only in bytecode.
 */
public fun listKotlinMembers(
    root: SourceRoot,
    binaryName: String,
    parser: KotlinSourceParser,
): JavaMemberList {
    // No availability pre-check (see [findKotlinBodies]).
    return try {
        if (binaryName.isBlank()) return JavaMemberList.NoSource
        val path = findKotlinSourcePath(root, binaryName, parser) ?: return JavaMemberList.NoSource
        if (!parser.available) return JavaMemberList.ParserUnavailable(parser.detail)
        val text = readKotlinText(root, path) ?: return JavaMemberList.ParseError(
            "source read error: cannot read $path",
        )
        val parsed = when (val parsed = parser.parseKotlin(text, path.substringAfterLast('/'))) {
            is KotlinParse.Parsed -> parsed.file
            is KotlinParse.Unavailable -> return JavaMemberList.ParserUnavailable(parsed.detail)
            is KotlinParse.Failed -> return JavaMemberList.ParseError(
                "source read error: $path does not parse: ${parsed.message}",
            )
        }
        val scope = navigateKotlin(parsed, binaryName) ?: return JavaMemberList.TypeNotFound
        JavaMemberList.Listed(listedKotlinMembers(scope))
    } catch (e: Exception) {
        JavaMemberList.ParseError(
            "source read error: Kotlin sources for $binaryName unreadable: " +
                (e.message ?: e.javaClass.simpleName),
        )
    }
}

private fun listedKotlinMembers(scope: KtScope): List<DeclaredSourceMember> {
    val ctors = listOfNotNull(scope.primaryCtor).map {
        DeclaredSourceMember(SourceBodyKind.CONSTRUCTOR, "<init>", it.params.map { param -> param.typeText ?: "Object" })
    }
    val rest = scope.declarations.mapNotNull { decl ->
        when (decl.kind) {
            KotlinDeclKind.FUNCTION -> DeclaredSourceMember(
                SourceBodyKind.METHOD,
                decl.name,
                // The extension receiver counts as the leading JVM parameter
                // (the facade static's first argument) — pair over it (T-081).
                decl.matchParams().map { param -> param.typeText ?: "Object" },
            )
            KotlinDeclKind.CONSTRUCTOR -> DeclaredSourceMember(
                SourceBodyKind.CONSTRUCTOR,
                "<init>",
                decl.params.map { param -> param.typeText ?: "Object" },
            )
            KotlinDeclKind.PROPERTY, KotlinDeclKind.ENUM_ENTRY -> DeclaredSourceMember(
                SourceBodyKind.FIELD,
                decl.name,
                emptyList(),
            )
            KotlinDeclKind.CLASS, KotlinDeclKind.OBJECT -> null
        }
    }
    return ctors + rest
}

/**
 * The `binaryName → .kt file` mapping: the T-071 direct hit first (a `.kt`
 * file named for the class), then a package-scoped scan (same directory
 * only, never a full walk — mirroring [findSiblingJavaSource]) for the file
 * declaring the class, then the file-facade convention (`FooKt` lives in
 * `Foo.kt`). Candidates confirming by PSI parse win; never throws.
 */
public fun findKotlinSourcePath(
    root: SourceRoot,
    binaryName: String,
    parser: KotlinSourceParser,
): String? {
    if (binaryName.isBlank()) return null
    return try {
        findKotlinSourcePathOrThrow(root, binaryName, parser)
    } catch (_: Exception) {
        null
    }
}

private fun findKotlinSourcePathOrThrow(
    root: SourceRoot,
    binaryName: String,
    parser: KotlinSourceParser,
): String? {
    val direct: String? = try {
        root.findSource(binaryName)
    } catch (_: Exception) {
        null
    }
    if (direct != null && direct.endsWith(".kt")) return direct
    val outer = binaryName.substringBefore('$')
    if (outer.isEmpty() || outer.isBlank()) return direct?.takeIf { it.endsWith(".java") }
    val outerSimple = outer.substringAfterLast('.')
    if (outerSimple.isEmpty()) return null
    val lastSimple = binaryName.substringAfterLast('.').substringAfterLast('$')
    if (lastSimple.isEmpty()) return null
    val packageSlash = outer.substringBeforeLast('.', "").replace('.', '/')
    val prefix = if (packageSlash.isEmpty()) "" else "$packageSlash/"
    val candidates: List<String> = try {
        root.sourcePaths()
    } catch (_: Exception) {
        return null
    }.filter { path ->
        path.endsWith(".kt") &&
            path.startsWith(prefix) &&
            '/' !in path.removePrefix(prefix)
    }
    // A class declaration wins over the facade convention: a literal `FooKt`
    // class in `Bar.kt` must resolve to `Bar.kt`, not `Foo.kt`.
    // Without usable PSI there is nothing to confirm with: the first text
    // mention routes to the degradation path (the caller degrades to
    // decompile/install-hint rather than failing), while no mention at all
    // stays a miss so sourceless roots keep their `NoSource` answers.
    var textHit: String? = null
    var sawUnavailable = false
    for (path in candidates) {
        val text = readKotlinText(root, path) ?: continue
        if (!containsWord(text, lastSimple)) continue
        if (textHit == null) textHit = path
        if (!parser.available) return path
        when (val parsed = parser.parseKotlin(text, path.substringAfterLast('/'))) {
            is KotlinParse.Parsed -> if (declaresBinary(parsed.file, binaryName)) return path
            is KotlinParse.Unavailable -> sawUnavailable = true
            is KotlinParse.Failed -> Unit
        }
    }
    // PSI unusable (broken env): a text mention is still evidence of `.kt`
    // flesh — route to degradation, not to a miss.
    if (sawUnavailable && textHit != null) return textHit
    // File facades (`KotlinShapesKt`) never share their binary name with a
    // declaration: the facade of `Foo.kt` is `FooKt` by compiler convention.
    if (outerSimple.endsWith("Kt") && '$' !in binaryName) {
        val base = outerSimple.dropLast(2)
        if (base.isNotEmpty()) {
            val facade = "$prefix$base.kt"
            if (facade in candidates) return facade
        }
    }
    return null
}

/** Whether the parsed file declares the full `$`-nesting of [binaryName]. */
internal fun declaresBinary(file: KotlinFile, binaryName: String): Boolean =
    navigateKotlinTarget(file, binaryName) != null

/** One navigation scope: the declarations visible at a file or class level. */
internal data class KtScope(
    val declarations: List<KotlinDecl>,
    val primaryCtor: KotlinDecl?,
)

/**
 * Navigates the `$`-nesting of [binaryName] to its member scope: file
 * top-level for facades, the class/object's children otherwise. Anonymous
 * and local classes have no binary-name segment to walk to, so members
 * declared inside them are unreachable and resolve to null.
 */
internal fun navigateKotlin(file: KotlinFile, binaryName: String): KtScope? {
    val target = navigateKotlinTarget(file, binaryName)
    if (target != null) return KtScope(target.children, target.primaryCtor)
    // Facades (`FooKt`) declare no such class: their members are the file's
    // top-level functions and properties.
    val segments = binaryName.substringAfterLast('.', binaryName).split('$')
    if (segments.any { it.isEmpty() }) return null
    if (segments.size == 1 && segments.single().endsWith("Kt")) {
        return KtScope(file.declarations, null)
    }
    return null
}

/** Navigates to the target class/object declaration itself, or null. */
internal fun navigateKotlinTarget(file: KotlinFile, binaryName: String): KotlinDecl? {
    val segments = binaryName.substringAfterLast('.', binaryName).split('$')
    if (segments.any { it.isEmpty() }) return null
    var current = file.declarations.firstOrNull {
        (it.kind == KotlinDeclKind.CLASS || it.kind == KotlinDeclKind.OBJECT) && it.name == segments.first()
    } ?: return null
    for (nested in segments.drop(1)) {
        current = current.children.firstOrNull {
            (it.kind == KotlinDeclKind.CLASS || it.kind == KotlinDeclKind.OBJECT) && it.name == nested
        } ?: return null
    }
    return current
}

/**
 * Name-level match over one scope: functions, properties and enum entries by
 * declared name (plus [aka] Kotlin/JVM alternate spellings from the bytecode
 * match), `<init>` to the explicit primary plus every secondary
 * constructor. A property also matches its JVM accessor spellings
 * (`getNickname`/`setNickname`/`isNickname` for `nickname`) so JVM-spelled
 * queries land on the property declaration, never the synthetic accessor.
 * Companion members answer for their containing class (the `@JvmStatic`
 * bridge): direct children first, companion children when nothing matches.
 */
internal fun matchKotlinByName(scope: KtScope, refName: String, aka: Set<String>): List<KotlinDecl> {
    if (refName == "<init>") {
        val primary = listOfNotNull(scope.primaryCtor)
        return primary + scope.declarations.filter { it.kind == KotlinDeclKind.CONSTRUCTOR }
    }
    if (refName == "<clinit>") return emptyList()
    val names = aka + refName
    val direct = scope.declarations.filter { decl ->
        when (decl.kind) {
            KotlinDeclKind.FUNCTION, KotlinDeclKind.ENUM_ENTRY -> decl.name in names
            KotlinDeclKind.PROPERTY -> decl.name in names || refName in accessorNames(decl.name)
            KotlinDeclKind.CONSTRUCTOR, KotlinDeclKind.CLASS, KotlinDeclKind.OBJECT -> false
        }
    }
    if (direct.isNotEmpty()) return direct
    return scope.declarations
        .filter { it.kind == KotlinDeclKind.OBJECT && it.isCompanion }
        .flatMap { matchKotlinByName(KtScope(it.children, it.primaryCtor), refName, aka) }
}

/** JVM accessor spellings of a Kotlin property (`nickname` → `getNickname`, …). */
public fun accessorNames(propertyName: String): Set<String> {
    if (propertyName.isEmpty()) return emptySet()
    val capitalised = propertyName.replaceFirstChar { it.uppercaseChar() }
    return setOf("get$capitalised", "set$capitalised", "is$capitalised")
}

/**
 * Signature narrowing over the name matches — the T-021 arity-first rule in
 * Kotlin spelling. An under-specified ref (no parameter list) keeps every
 * overload. Otherwise arity decides first, then source-simple-name equality
 * per parameter; surviving overloads narrow further by return type when the
 * ref carries one. Kotlin specifics:
 *
 * - `suspend` JVM spellings (trailing `Continuation`) match the source
 *   arity; the hidden parameter never appears in sources.
 * - extension receivers count as a leading parameter (the JVM static's first
 *   argument).
 * - trailing default arguments may be omitted by the query (the
 *   `@JvmOverloads` overloads are real bytecode members, but the source
 *   declares one function).
 * - Kotlin primitives/boxings (`Int`, `Integer`) and `Any` key as their JVM
 *   counterparts (`int`, `Object`).
 */
internal fun narrowKotlinBySignature(
    candidates: List<KotlinDecl>,
    ref: MemberSymbolRef,
): List<KotlinDecl> {
    val wanted = ref.parameterTypes ?: return candidates
    val arityKept = candidates.filter { passesKotlinArity(it, wanted.size) }
    if (arityKept.size <= 1) return arityKept.filter { kotlinParamsMatch(it, wanted) }
    val paramsKept = arityKept.filter { kotlinParamsMatch(it, wanted) }
    if (paramsKept.isEmpty()) return emptyList()
    val returnType = ref.returnType
    if (paramsKept.size > 1 && returnType != null) {
        val returnKept = paramsKept.filter {
            it.returnType != null && kotlinTypeKey(it.returnType) == kotlinTypeKey(returnType.simpleName)
        }
        if (returnKept.isNotEmpty()) return returnKept
    }
    return paramsKept
}

/** Effective match parameters: the extension receiver first, then the value params. */
internal fun KotlinDecl.matchParams(): List<KotlinParam> {
    val receiver = receiverType?.let { KotlinParam("receiver", it, hasDefault = false) }
    return listOfNotNull(receiver) + params
}

private fun passesKotlinArity(decl: KotlinDecl, wantedSize: Int): Boolean {
    val effective = decl.matchParams()
    if (decl.kind == KotlinDeclKind.PROPERTY || decl.kind == KotlinDeclKind.ENUM_ENTRY) {
        return wantedSize == 0
    }
    if (effective.size == wantedSize) return true
    // A `suspend` function queried in JVM spelling carries the hidden
    // trailing `Continuation` the source never declares.
    if (decl.kind == KotlinDeclKind.FUNCTION && decl.isSuspend &&
        wantedSize == effective.size + 1
    ) {
        return true
    }
    // Trailing default arguments may be omitted: `withDefault(int)` names
    // `withDefault(first: Int, second: String = "d")`.
    if (decl.kind == KotlinDeclKind.FUNCTION && wantedSize < effective.size) {
        val omitted = effective.drop(wantedSize)
        if (omitted.isNotEmpty() && omitted.all { it.hasDefault }) return true
    }
    return false
}

private fun kotlinParamsMatch(decl: KotlinDecl, wanted: List<TypeName>): Boolean {
    if (decl.kind == KotlinDeclKind.PROPERTY || decl.kind == KotlinDeclKind.ENUM_ENTRY) {
        return wanted.isEmpty()
    }
    val effective = decl.matchParams()
    val compare = if (decl.kind == KotlinDeclKind.FUNCTION && decl.isSuspend &&
        wanted.size == effective.size + 1 && isContinuation(wanted.last())
    ) {
        wanted.dropLast(1) to effective
    } else {
        wanted to effective
    }
    val (wantParams, haveParams) = compare
    if (wantParams.size > haveParams.size) return false
    if (wantParams.size < haveParams.size) {
        // Only trailing defaults may be omitted (checked in
        // [passesKotlinArity]; re-checked here so the predicate is total).
        val omitted = haveParams.drop(wantParams.size)
        if (omitted.isEmpty() || omitted.any { !it.hasDefault }) return false
    }
    return wantParams.zip(haveParams).all { (want, have) ->
        have.typeText == null || kotlinTypeKey(have.typeText) == kotlinTypeKey(want.simpleName)
    }
}

private fun isContinuation(type: TypeName): Boolean {
    val binary = (type as? TypeName.ClassType)?.binaryName
    if (binary == "kotlin.coroutines.Continuation") return true
    return type.simpleName == "Continuation"
}

/**
 * Normalises one side of a Kotlin-source-vs-ref type comparison to a JVM
 * pairing key: strips annotations, generics and nullability, then maps
 * Kotlin spellings (`Int`, `Any?`) onto the JVM keys the ref grammar uses
 * (`int`, `Object`).
 */
internal fun kotlinTypeKey(spelling: String): String {
    val cleaned = spelling.trim()
        .replace(Regex("@\\w+(\\([^)]*\\))?\\s*"), "")
        .substringBefore('<')
        .trim()
        .trimEnd('?')
        .trim()
        .removeSuffix("...")
        .trim()
    val simple = sourceTypeKey(cleaned)
    return KOTLIN_TYPE_KEYS[simple] ?: simple
}

private val KOTLIN_TYPE_KEYS: Map<String, String> = mapOf(
    "Int" to "int",
    "Long" to "long",
    "Short" to "short",
    "Byte" to "byte",
    "Char" to "char",
    "Boolean" to "boolean",
    "Float" to "float",
    "Double" to "double",
    "Unit" to "void",
    "Integer" to "int",
    "Character" to "char",
    "Void" to "void",
    "Any" to "Object",
)

/** Slices one matched declaration to a verbatim [SourceBody]. */
internal fun sliceKotlinBody(
    path: String,
    lines: List<String>,
    text: String,
    decl: KotlinDecl,
): SourceBody? {
    val range = kotlinLineRange(lines, text, decl) ?: return null
    val (startLine, endLine) = range
    val bodyText = lines.subList(startLine - 1, endLine).joinToString("\n")
    return when (decl.kind) {
        KotlinDeclKind.FUNCTION -> SourceBody(
            SourceBodyKind.METHOD,
            decl.name,
            decl.params.map { it.typeText ?: "Object" },
            decl.returnType,
            path,
            startLine,
            endLine,
            bodyText,
        )
        KotlinDeclKind.CONSTRUCTOR -> SourceBody(
            SourceBodyKind.CONSTRUCTOR,
            "<init>",
            decl.params.map { it.typeText ?: "Object" },
            null,
            path,
            startLine,
            endLine,
            bodyText,
        )
        KotlinDeclKind.PROPERTY, KotlinDeclKind.ENUM_ENTRY -> SourceBody(
            SourceBodyKind.FIELD,
            decl.name,
            emptyList(),
            null,
            path,
            startLine,
            endLine,
            bodyText,
        )
        KotlinDeclKind.CLASS, KotlinDeclKind.OBJECT -> null
    }
}

/**
 * Derives the 1-based inclusive `[startLine, endLine]` range of a PSI node:
 * offsets count newlines in the parsed text, and a KDoc comment the PSI
 * range includes is trimmed (bodies never carry docs — `doc` serves those).
 * `null` when the offsets are out of bounds (a parser invariant break, not
 * caller error).
 */
internal fun kotlinLineRange(
    lines: List<String>,
    text: String,
    decl: KotlinDecl,
): Pair<Int, Int>? {
    if (lines.isEmpty()) return null
    if (decl.startOffset < 0 || decl.endOffset < decl.startOffset || decl.endOffset > text.length) {
        return null
    }
    var startLine = lineOfOffset(text, decl.startOffset)
    val endLine = lineOfOffset(text, maxOf(decl.endOffset - 1, decl.startOffset))
    if (endLine < startLine || startLine < 1 || endLine > lines.size) return null
    val docEnd = decl.docEndOffset
    if (docEnd != null && docEnd >= 0 && docEnd <= text.length) {
        val docEndLine = lineOfOffset(text, docEnd)
        if (docEndLine >= startLine && docEndLine < endLine) {
            startLine = docEndLine + 1
        }
    }
    return startLine to endLine
}

/** 1-based line number of a 0-based char offset (clamped into the text). */
internal fun lineOfOffset(text: String, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    var line = 1
    for (index in 0 until at) {
        if (text[index] == '\n') line++
    }
    return line
}

/** Slices one matched declaration's KDoc, or `null` when absent or blank. */
internal fun sliceKotlinDoc(
    path: String,
    lines: List<String>,
    text: String,
    decl: KotlinDecl,
): SourceDoc? {
    val comment = decl.docText ?: return null
    if (comment.isBlank()) return null
    val docEnd = decl.docEndOffset
    val docStart = decl.docStartOffset
    if (docEnd == null || docStart == null) return null
    if (docStart < 0 || docEnd < docStart || docEnd > text.length) return null
    val startLine = lineOfOffset(text, docStart)
    val endLine = lineOfOffset(text, maxOf(docEnd - 1, docStart))
    if (startLine < 1 || endLine < startLine || endLine > lines.size) return null
    val kind = when (decl.kind) {
        KotlinDeclKind.FUNCTION -> SourceDocKind.METHOD
        KotlinDeclKind.CONSTRUCTOR -> SourceDocKind.CONSTRUCTOR
        KotlinDeclKind.PROPERTY, KotlinDeclKind.ENUM_ENTRY -> SourceDocKind.FIELD
        KotlinDeclKind.CLASS, KotlinDeclKind.OBJECT -> return null
    }
    val name = if (kind == SourceDocKind.CONSTRUCTOR) "<init>" else decl.name
    return SourceDoc(kind, name, path, startLine, endLine, comment)
}

/** Slices a type's own KDoc, or `null` when absent or blank. */
internal fun sliceKotlinTypeDoc(
    path: String,
    lines: List<String>,
    text: String,
    target: KotlinDecl,
): SourceDoc? {
    val comment = target.docText ?: return null
    if (comment.isBlank()) return null
    val docEnd = target.docEndOffset
    val docStart = target.docStartOffset
    if (docEnd == null || docStart == null) return null
    if (docStart < 0 || docEnd < docStart || docEnd > text.length) return null
    val startLine = lineOfOffset(text, docStart)
    val endLine = lineOfOffset(text, maxOf(docEnd - 1, docStart))
    if (startLine < 1 || endLine < startLine || endLine > lines.size) return null
    return SourceDoc(SourceDocKind.TYPE, target.name, path, startLine, endLine, comment)
}

/**
 * The KDoc inner content of a raw doc comment: delimiters gone, leading
 * stars intact (the shape `core`'s `renderJavadoc` reads —
 * [findKotlinMemberDocs] stores this, so `--raw` and rendering agree with
 * the javadoc path). `null` when the text is not a doc comment or is blank.
 */
internal fun kdocInnerOf(rawComment: String): String? {
    val trimmed = rawComment.trim()
    if (!trimmed.startsWith("/**") || !trimmed.endsWith("*/") || trimmed.length < 6) return null
    val inner = trimmed.removePrefix("/**").removeSuffix("*/")
    return inner.ifBlank { null }
}

/** Whether [word] occurs in [text] as a whole word (never throws). */
internal fun containsWord(text: String, word: String): Boolean {
    if (word.isEmpty()) return false
    return try {
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)
    } catch (_: Exception) {
        false
    }
}

private fun readKotlinText(root: SourceRoot, path: String): String? = try {
    root.openSource(path).use { it.readBytes().toString(Charsets.UTF_8) }
} catch (_: Exception) {
    null
}

/**
 * Display lines of one `.kt` text: newlines with `\r` stripped. Split — not
 * shared with the service's `splitTextLines` (which drops the trailing empty
 * line) — because [bodyOutcome] re-reads the file with this same plain split
 * and indexes [SourceBody.startLine]/[endLine] into it.
 */
internal fun splitKotlinLines(text: String): List<String> =
    text.split('\n').map { it.removeSuffix("\r") }
