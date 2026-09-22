package dev.jdx.sources

import com.github.javaparser.JavaParser
import com.github.javaparser.ParseStart
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Providers
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.TypeName

/**
 * Kind of source declaration a [SourceBody] was sliced from. Initialiser
 * blocks (`<clinit>`) have no kind here: they are unreachable by member ref
 * and always resolve to [JavaBodyResult.MemberNotFound].
 */
public enum class SourceBodyKind {
    METHOD,
    CONSTRUCTOR,
    FIELD,
    ENUM_ENTRY,
}

/**
 * One verbatim member body sliced from a `.java` file: the AST node's
 * 1-based inclusive `[startLine, endLine]` range cut out of the original
 * text — ground-truth bytes, never pretty-printed.
 *
 * [parameterTypes] and [returnType] are source spellings (`List<? extends T>`,
 * `String...`, `void`); bytecode stays the authority on structure (D-009),
 * so callers pair these with `ClassInfo` rather than trusting them. [name] is
 * `<init>` for constructors and record compact constructors (whose record
 * components are implicit, so [parameterTypes] is empty), matching the ref
 * grammar; otherwise the declared name. Body-less declarations
 * (abstract/native methods, fields) slice to their declaration text, and a
 * multi-declarator field (`int a, b;`) slices to the whole declaration.
 */
public data class SourceBody(
    public val kind: SourceBodyKind,
    public val name: String,
    public val parameterTypes: List<String>,
    public val returnType: String?,
    public val file: String,
    public val startLine: Int,
    public val endLine: Int,
    public val text: String,
)

/**
 * One member a `.java` file declares, for listings and for the
 * `SOURCES_VERSION_MISMATCH` pairing T-028 will build on this seam.
 */
public data class DeclaredSourceMember(
    public val kind: SourceBodyKind,
    public val name: String,
    public val parameterTypes: List<String>,
)

/**
 * Outcome of [findJavaBodies]: a value on every agent-reachable path, never
 * a throw. Malformed sources, hostile names and unreadable entries all land
 * in [ParseError] or a not-found case with the problem named.
 */
public sealed interface JavaBodyResult {
    /** Every source overload matching the ref; several when under-specified. */
    public data class Found(public val bodies: List<SourceBody>) : JavaBodyResult

    /** This root holds no `.java` file for the declaring type. */
    public data object NoSource : JavaBodyResult

    /** The declaring type only ships `.kt` here — Kotlin parsing is T-039. */
    public data object NotJava : JavaBodyResult

    /** The file parses, but declares no such member (or no such nested type). */
    public data object MemberNotFound : JavaBodyResult

    /** The file is unreadable or does not parse; [message] names the cause. */
    public data class ParseError(public val message: String) : JavaBodyResult
}

/**
 * Outcome of [listJavaMembers]: same root/parse cases as [JavaBodyResult],
 * plus [TypeNotFound] when the file parses but names no such (nested) type.
 */
public sealed interface JavaMemberList {
    public data class Listed(public val members: List<DeclaredSourceMember>) : JavaMemberList
    public data object NoSource : JavaMemberList
    public data object NotJava : JavaMemberList
    public data object TypeNotFound : JavaMemberList
    public data class ParseError(public val message: String) : JavaMemberList
}

/**
 * JavaParser integration over the T-071 [SourceRoot] seam (T-021, first M3
 * parsing slice): `.java` member bodies as verbatim text with line ranges.
 *
 * JavaParser only ever reads text — no inspected class is loaded, so the
 * D-017 never-execute rule holds trivially. Parsing is single-shot per call
 * (JavaParser is millisecond-scale, unlike the Kotlin PSI in D-008, so no
 * lazy classloader or daemon-warm state is needed); an instance parser is
 * built per call so no global `StaticJavaParser` configuration is touched.
 */
public fun findJavaBodies(root: SourceRoot, ref: MemberSymbolRef): JavaBodyResult {
    return when (val loaded = loadJavaUnit(root, ref.declaringType.binaryName)) {
        is JavaUnit.Unit -> {
            val target = navigateToType(loaded.compilationUnit, ref.declaringType.binaryName)
                ?: return JavaBodyResult.MemberNotFound
            val matches = narrowBySignature(matchByName(target, ref), ref)
            if (matches.isEmpty()) return JavaBodyResult.MemberNotFound
            val bodies = matches.mapNotNull { sliceBody(loaded, it, ref) }
            if (bodies.isEmpty()) {
                JavaBodyResult.ParseError(
                    "source read error: ${loaded.path} has no position for ${ref.name}",
                )
            } else {
                JavaBodyResult.Found(bodies)
            }
        }
        is JavaUnit.NoSource -> JavaBodyResult.NoSource
        is JavaUnit.NotJava -> JavaBodyResult.NotJava
        is JavaUnit.ParseError -> JavaBodyResult.ParseError(loaded.message)
    }
}

/**
 * Every member the declaring type's `.java` file declares, in file order.
 * Overloads appear once per declaration and multi-declarator fields once per
 * variable; synthetic/bridge members never appear — they exist only in
 * bytecode, which is the point of the T-028 comparison this listing feeds.
 */
public fun listJavaMembers(root: SourceRoot, binaryName: String): JavaMemberList {
    return when (val loaded = loadJavaUnit(root, binaryName)) {
        is JavaUnit.Unit -> {
            val target = navigateToType(loaded.compilationUnit, binaryName)
                ?: return JavaMemberList.TypeNotFound
            JavaMemberList.Listed(declaredMembersOf(target))
        }
        is JavaUnit.NoSource -> JavaMemberList.NoSource
        is JavaUnit.NotJava -> JavaMemberList.NotJava
        is JavaUnit.ParseError -> JavaMemberList.ParseError(loaded.message)
    }
}

/**
 * The `binaryName → parsed `.java`` step shared by both queries: resolve the
 * source file through [findJavaSourcePath] (direct outer file, else a
 * same-file top-level sibling from the T-074 package-scoped scan; `.java`
 * wins by construction), read it, and parse it. `.kt`-only hits stop at
 * [JavaUnit.NotJava] — this slice never attempts Kotlin (T-039).
 */
internal sealed interface JavaUnit {
    public data class Unit(
        public val path: String,
        public val lines: List<String>,
        public val compilationUnit: CompilationUnit,
    ) : JavaUnit

    public data object NoSource : JavaUnit
    public data object NotJava : JavaUnit
    public data class ParseError(public val message: String) : JavaUnit
}

internal fun loadJavaUnit(root: SourceRoot, binaryName: String): JavaUnit {
    if (binaryName.isBlank()) return JavaUnit.NoSource
    val path = try {
        findJavaSourcePath(root, binaryName)
    } catch (e: Exception) {
        return JavaUnit.ParseError(
            "source read error: cannot list ${root.displayName}: ${e.message}",
        )
    } ?: return JavaUnit.NoSource
    if (!path.endsWith(".java")) return JavaUnit.NotJava
    val text = try {
        root.openSource(path).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        return JavaUnit.ParseError("source read error: cannot read $path: ${e.message}")
    }
    return parseJavaUnit(path, text)
}

/**
 * Parses one `.java` file; positions are sliced from [text] afterward, so
 * callers must pass the exact bytes that were parsed.
 */
internal fun parseJavaUnit(path: String, text: String): JavaUnit {
    // BLEEDING_EDGE, deliberately: this parser never compiles what it reads,
    // it only slices ranges — so the newest grammar accepts the most sources
    // (records, sealed types, text blocks) instead of failing them as syntax
    // errors. Fixtures using records pin this (JavaBodiesTest).
    val configuration = ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    val parsed = try {
        JavaParser(configuration)
            .parse(ParseStart.COMPILATION_UNIT, Providers.provider(text))
    } catch (e: Exception) {
        return JavaUnit.ParseError("source read error: $path does not parse: ${e.message}")
    }
    if (!parsed.isSuccessful || !parsed.result.isPresent) {
        val first = parsed.problems.firstOrNull()?.message?.singleLine()?.take(200)
            ?: "unknown parse failure"
        return JavaUnit.ParseError("source read error: $path does not parse: $first")
    }
    // Split once: every body below is a verbatim sub-slice of these lines.
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    return JavaUnit.Unit(path, lines, parsed.result.get())
}

private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

/**
 * Walks the `$`-nesting of [binaryName] down from the file's top-level
 * types. Anonymous and local classes have no binary-name segment to walk to,
 * so members declared inside them are unreachable and resolve to not-found.
 * A `$`-shape that cannot exist (empty segments, JFR-style `$$` names)
 * returns null instead of throwing.
 */
internal fun navigateToType(compilationUnit: CompilationUnit, binaryName: String): TypeDeclaration<*>? {
    val segments = binaryName.substringAfterLast('.', binaryName).split('$')
    if (segments.any { it.isEmpty() }) return null
    var current = compilationUnit.types.firstOrNull { it.nameAsString == segments.first() }
        ?: return null
    for (nested in segments.drop(1)) {
        current = current.members.filterIsInstance<TypeDeclaration<*>>()
            .firstOrNull { it.nameAsString == nested }
            ?: return null
    }
    return current
}

/** Name-level match: methods and fields by declared name, `<init>` to every constructor. */
internal fun matchByName(target: TypeDeclaration<*>, ref: MemberSymbolRef): List<BodyDeclaration<*>> {
    if (ref.name == "<init>") {
        return target.members.filter {
            it.isConstructorDeclaration || it.isCompactConstructorDeclaration
        }
    }
    // Static initialisers (`<clinit>`) and anything else unnameable: no match.
    if (ref.name == "<clinit>") return emptyList()
    val found = target.members.filter { member ->
        member.isMethodDeclaration && member.asMethodDeclaration().nameAsString == ref.name ||
            member.isFieldDeclaration &&
            member.asFieldDeclaration().variables.any { it.nameAsString == ref.name }
    }.toMutableList<BodyDeclaration<*>>()
    if (target.isEnumDeclaration) {
        target.asEnumDeclaration().entries
            .filter { it.nameAsString == ref.name }
            .mapTo(found) { it }
    }
    return found
}

/**
 * Signature narrowing over the name matches. An under-specified ref (no
 * parameter list) keeps every overload — the exit-2 candidate set T-022
 * renders. Otherwise arity decides first (compact record constructors take
 * their components implicitly, so arity cannot drop them; fields and enum
 * entries never match a parameterised ref at all); surviving overloads narrow further by
 * source-simple-name equality per parameter (FQ `java.lang.Object` matches
 * source `Object`), then by return type when the ref carries one (the
 * covariant/bridge disambiguator). Total disagreement with the declared
 * parameters is [JavaBodyResult.MemberNotFound], never a guess.
 */
internal fun narrowBySignature(
    candidates: List<BodyDeclaration<*>>,
    ref: MemberSymbolRef,
): List<BodyDeclaration<*>> {
    val wanted = ref.parameterTypes ?: return candidates
    val arityKept = candidates.filter { passesArity(it, wanted.size) }
    if (arityKept.size <= 1) return arityKept.filter { memberParamsMatch(it, wanted) }
    val paramsKept = arityKept.filter { memberParamsMatch(it, wanted) }
    if (paramsKept.isEmpty()) return emptyList()
    val returnType = ref.returnType
    if (paramsKept.size > 1 && returnType != null) {
        val returnKept = paramsKept.filter { memberReturnMatches(it, returnType.simpleName) }
        if (returnKept.isNotEmpty()) return returnKept
    }
    return paramsKept
}

/**
 * Arity gate for a parameterised ref. Compact record constructors always
 * pass — their record components are implicit, so arity cannot judge them
 * (the per-parameter check below keeps them too). Fields and enum entries
 * carry no parameter list per the grammar, so a parameterised ref never
 * matches them.
 */
private fun passesArity(member: BodyDeclaration<*>, wantedSize: Int): Boolean = when {
    member.isCompactConstructorDeclaration -> true
    member.isMethodDeclaration -> member.asMethodDeclaration().parameters.size == wantedSize
    member.isConstructorDeclaration -> member.asConstructorDeclaration().parameters.size == wantedSize
    else -> false
}

private fun memberParamsMatch(member: BodyDeclaration<*>, wanted: List<TypeName>): Boolean {
    // Compact constructors take the record components implicitly: unprovable,
    // so they survive any parameterised `<init>` ref.
    if (member.isCompactConstructorDeclaration) return true
    val declared = when {
        member.isMethodDeclaration -> member.asMethodDeclaration().parameters.map { it.typeAsString }
        member.isConstructorDeclaration -> member.asConstructorDeclaration().parameters.map { it.typeAsString }
        else -> return false
    }
    if (declared.size != wanted.size) return false
    return declared.zip(wanted).all { (source, want) -> sourceTypeKey(source) == typeKeyOf(want.simpleName) }
}

private fun memberReturnMatches(member: BodyDeclaration<*>, wantedSimpleName: String): Boolean {
    if (!member.isMethodDeclaration) return false
    return sourceTypeKey(member.asMethodDeclaration().typeAsString) == typeKeyOf(wantedSimpleName)
}

/**
 * Normalises one side of a source-vs-ref type comparison to a bare simple
 * name: strips annotations, generics, arrays and varargs, then keeps the last
 * segment (`java.lang.Object` and `Object` both key as `Object`;
 * `String...` and `String[]` both key as `String`).
 */
internal fun sourceTypeKey(sourceSpelling: String): String = typeKeyOf(
    sourceSpelling.trim()
        .replace(Regex("@\\w+(\\([^)]*\\))?\\s*"), "")
        .substringBefore('<')
        .removeSuffix("..."),
)

internal fun typeKeyOf(spelling: String): String = spelling
    .trim()
    .removeSuffix("...")
    .replace(Regex("(\\[\\])+$"), "")
    .substringAfterLast('.')
    .substringAfterLast('$')

private fun declaredMembersOf(target: TypeDeclaration<*>): List<DeclaredSourceMember> {
    val executables = target.members.mapNotNull { member ->
        when {
            member.isMethodDeclaration -> {
                val method = member.asMethodDeclaration()
                DeclaredSourceMember(
                    SourceBodyKind.METHOD,
                    method.nameAsString,
                    method.parameters.map { it.typeAsString },
                )
            }
            member.isConstructorDeclaration -> {
                val ctor = member.asConstructorDeclaration()
                DeclaredSourceMember(
                    SourceBodyKind.CONSTRUCTOR,
                    "<init>",
                    ctor.parameters.map { it.typeAsString },
                )
            }
            member.isCompactConstructorDeclaration -> {
                DeclaredSourceMember(SourceBodyKind.CONSTRUCTOR, "<init>", emptyList())
            }
            member.isAnnotationMemberDeclaration -> {
                // Annotation elements (`String[] names() default {};`) are
                // methods in bytecode but annotation-member declarations in
                // JavaParser — without this branch every annotation type
                // would read as mismatched (T-028).
                val element = member.asAnnotationMemberDeclaration()
                DeclaredSourceMember(
                    SourceBodyKind.METHOD,
                    element.nameAsString,
                    emptyList(),
                )
            }
            else -> null
        }
    }
    // A multi-declarator field (`int a, b;`) lists one member per variable.
    val fields = target.members.filter { it.isFieldDeclaration }.flatMap { member ->
        member.asFieldDeclaration().variables.map { variable ->
            DeclaredSourceMember(SourceBodyKind.FIELD, variable.nameAsString, emptyList())
        }
    }
    val entries = if (target.isEnumDeclaration) {
        target.asEnumDeclaration().entries.map {
            DeclaredSourceMember(SourceBodyKind.ENUM_ENTRY, it.nameAsString, emptyList())
        }
    } else {
        emptyList()
    }
    // Record components (`record Point(int x, int y)`) live in the header,
    // not in the member list — but the compiler emits a field per component,
    // so the T-028 pairing lists them as fields (a removed component then
    // warns instead of passing silently).
    val components = if (target.isRecordDeclaration) {
        target.asRecordDeclaration().parameters.map {
            DeclaredSourceMember(SourceBodyKind.FIELD, it.nameAsString, emptyList())
        }
    } else {
        emptyList()
    }
    return executables + fields + entries + components
}

/** Slices one matched declaration to a verbatim [SourceBody]. */
internal fun sliceBody(loaded: JavaUnit.Unit, member: BodyDeclaration<*>, ref: MemberSymbolRef): SourceBody? {
    val range = member.range.orElse(null) ?: return null
    // Ranges are 1-based inclusive over the lines the parser itself saw, so
    // any violation is a parser invariant break, not caller error.
    if (range.begin.line < 1 || range.end.line < range.begin.line || range.end.line > loaded.lines.size) {
        return null
    }
    val text = loaded.lines.subList(range.begin.line - 1, range.end.line).joinToString("\n")
    return when {
        member.isMethodDeclaration -> {
            val method = member.asMethodDeclaration()
            SourceBody(
                SourceBodyKind.METHOD,
                method.nameAsString,
                method.parameters.map { it.typeAsString },
                method.typeAsString,
                loaded.path,
                range.begin.line,
                range.end.line,
                text,
            )
        }
        member.isConstructorDeclaration -> {
            val ctor = member.asConstructorDeclaration()
            SourceBody(
                SourceBodyKind.CONSTRUCTOR,
                "<init>",
                ctor.parameters.map { it.typeAsString },
                null,
                loaded.path,
                range.begin.line,
                range.end.line,
                text,
            )
        }
        member.isCompactConstructorDeclaration -> {
            SourceBody(
                SourceBodyKind.CONSTRUCTOR,
                "<init>",
                emptyList(),
                null,
                loaded.path,
                range.begin.line,
                range.end.line,
                text,
            )
        }
        member.isFieldDeclaration -> {
            SourceBody(
                SourceBodyKind.FIELD,
                ref.name,
                emptyList(),
                null,
                loaded.path,
                range.begin.line,
                range.end.line,
                text,
            )
        }
        member is EnumConstantDeclaration -> {
            SourceBody(
                SourceBodyKind.ENUM_ENTRY,
                member.nameAsString,
                emptyList(),
                null,
                loaded.path,
                range.begin.line,
                range.end.line,
                text,
            )
        }
        else -> null
    }
}
