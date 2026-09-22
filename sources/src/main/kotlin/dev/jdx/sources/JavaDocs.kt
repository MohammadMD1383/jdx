package dev.jdx.sources

import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.comments.JavadocComment
import dev.jdx.core.model.MemberSymbolRef

/**
 * Which declaration a [SourceDoc] was sliced from. Initialiser blocks
 * (`<clinit>`) have no kind here: they are unreachable by member ref and
 * always resolve to [JavaDocResult.MemberNotFound].
 */
public enum class SourceDocKind {
    TYPE,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    ENUM_ENTRY,
}

/**
 * One javadoc comment sliced from a `.java` file: the raw [JavadocComment]
 * content (delimiters gone, leading stars intact — the `core` renderer
 * strips those) plus the 1-based inclusive `[startLine, endLine]` range of
 * the comment itself, so provenance points at the doc, not the declaration.
 * Blank comments are never produced — they count as undocumented.
 */
public data class SourceDoc(
    public val kind: SourceDocKind,
    public val name: String,
    public val file: String,
    public val startLine: Int,
    public val endLine: Int,
    public val rawComment: String,
)

/**
 * Outcome of [findTypeDoc]/[findMemberDocs]: a value on every agent-reachable
 * path, never a throw. Undocumented declarations resolve to
 * [MemberNotFound]/[TypeNotFound] (not empty `Found`) so callers fall through
 * to supertype lookup without a second emptiness check.
 */
public sealed interface JavaDocResult {
    /** Every matching declaration carrying a non-blank javadoc comment. */
    public data class Found(public val docs: List<SourceDoc>) : JavaDocResult

    /** This root holds no `.java` file for the declaring type. */
    public data object NoSource : JavaDocResult

    /** The declaring type only ships `.kt` here — Kotlin parsing is T-039. */
    public data object NotJava : JavaDocResult

    /** The file parses, but the member is unknown or undocumented here. */
    public data object MemberNotFound : JavaDocResult

    /** The file parses, but names no such (nested) type — a stale pairing, not a doc gap. */
    public data object TypeNotFound : JavaDocResult

    /** The file parses and names the type, but it carries no (non-blank) javadoc comment. */
    public data object TypeUndocumented : JavaDocResult

    /** The file is unreadable or does not parse; [message] names the cause. */
    public data class ParseError(public val message: String) : JavaDocResult
}

/**
 * Javadoc extraction over the T-071 [SourceRoot] seam (T-025): raw comment
 * text with file ranges. Matching reuses the T-021 member matchers
 * ([matchByName], [narrowBySignature]) so `body` and `doc` agree on which
 * source declaration a ref names; rendering to plain text is the `core`
 * `renderJavadoc` pure function (core stays dependency-free).
 *
 * JavaParser only ever reads text — no inspected class is loaded, so the
 * D-017 never-execute rule holds trivially.
 */
public fun findTypeDoc(root: SourceRoot, binaryName: String): JavaDocResult {
    return when (val loaded = loadJavaUnit(root, binaryName)) {
        is JavaUnit.Unit -> {
            val target = navigateToType(loaded.compilationUnit, binaryName)
                ?: return JavaDocResult.TypeNotFound
            sliceTypeDoc(loaded, target)?.let { JavaDocResult.Found(listOf(it)) }
                ?: JavaDocResult.TypeUndocumented
        }
        is JavaUnit.NoSource -> JavaDocResult.NoSource
        is JavaUnit.NotJava -> JavaDocResult.NotJava
        is JavaUnit.ParseError -> JavaDocResult.ParseError(loaded.message)
    }
}

/**
 * Every documented source overload matching [ref]; several when
 * under-specified (the exit-2 set `jdx doc` renders — ambiguity itself is
 * decided from bytecode first, D-009, by the caller).
 */
public fun findMemberDocs(root: SourceRoot, ref: MemberSymbolRef): JavaDocResult {
    return when (val loaded = loadJavaUnit(root, ref.declaringType.binaryName)) {
        is JavaUnit.Unit -> {
            val target = navigateToType(loaded.compilationUnit, ref.declaringType.binaryName)
                ?: return JavaDocResult.MemberNotFound
            val matches = narrowBySignature(matchByName(target, ref), ref)
            if (matches.isEmpty()) return JavaDocResult.MemberNotFound
            val docs = matches.mapNotNull { sliceMemberDoc(loaded, it, ref) }
            if (docs.isEmpty()) JavaDocResult.MemberNotFound else JavaDocResult.Found(docs)
        }
        is JavaUnit.NoSource -> JavaDocResult.NoSource
        is JavaUnit.NotJava -> JavaDocResult.NotJava
        is JavaUnit.ParseError -> JavaDocResult.ParseError(loaded.message)
    }
}

/** Slices a type's own javadoc comment, or `null` when absent or blank. */
internal fun sliceTypeDoc(loaded: JavaUnit.Unit, target: TypeDeclaration<*>): SourceDoc? {
    val comment = target.javadocComment.orElse(null) ?: return null
    if (comment.content.isBlank()) return null
    val range = comment.range.orElse(null) ?: target.range.orElse(null) ?: return null
    return SourceDoc(
        SourceDocKind.TYPE,
        target.nameAsString,
        loaded.path,
        range.begin.line,
        range.end.line,
        comment.content,
    )
}

/** Slices one matched declaration's javadoc comment, or `null` when absent or blank. */
internal fun sliceMemberDoc(loaded: JavaUnit.Unit, member: BodyDeclaration<*>, ref: MemberSymbolRef): SourceDoc? {
    val comment = docCommentOf(member) ?: return null
    if (comment.content.isBlank()) return null
    val range = comment.range.orElse(null) ?: member.range.orElse(null) ?: return null
    val kind = when {
        member.isMethodDeclaration || member.isAnnotationMemberDeclaration -> SourceDocKind.METHOD
        member.isConstructorDeclaration || member.isCompactConstructorDeclaration -> SourceDocKind.CONSTRUCTOR
        member.isFieldDeclaration -> SourceDocKind.FIELD
        member is EnumConstantDeclaration -> SourceDocKind.ENUM_ENTRY
        else -> return null
    }
    val name = if (kind == SourceDocKind.CONSTRUCTOR) "<init>" else ref.name
    return SourceDoc(
        kind,
        name,
        loaded.path,
        range.begin.line,
        range.end.line,
        comment.content,
    )
}

/** The attached javadoc comment of one matched declaration, if any. */
internal fun docCommentOf(member: BodyDeclaration<*>): JavadocComment? = when {
    member.isMethodDeclaration -> member.asMethodDeclaration().javadocComment.orElse(null)
    member.isAnnotationMemberDeclaration ->
        member.asAnnotationMemberDeclaration().javadocComment.orElse(null)
    member.isConstructorDeclaration -> member.asConstructorDeclaration().javadocComment.orElse(null)
    member.isCompactConstructorDeclaration -> member.asCompactConstructorDeclaration().javadocComment.orElse(null)
    member.isFieldDeclaration -> member.asFieldDeclaration().javadocComment.orElse(null)
    member is EnumConstantDeclaration -> member.javadocComment.orElse(null)
    else -> null
}
