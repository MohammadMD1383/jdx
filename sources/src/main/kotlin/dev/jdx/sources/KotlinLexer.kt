package dev.jdx.sources

import dev.jdx.core.model.Origin

/**
 * Brace-matching `.kt` extractor (PROPOSAL.md §12.2 / §22, issue #16).
 *
 * The compiler sidecar stays the precise path. This lexer is the offline
 * fallback: it recovers common top-level and member `fun` / `val` / `var` /
 * `class` / `object` / `enum` shapes, including extension receivers, `suspend`,
 * default arguments, primary-constructor properties, accessors, and enum
 * entries with class bodies. Strings (plain and raw), character literals,
 * line comments, and nested block comments are skipped before braces are
 * counted, so a `}` inside them cannot close a declaration.
 *
 * It never throws. A scan that cannot keep braces, strings, or comments
 * balanced is [KotlinParse.Unavailable] so the caller degrades to
 * decompile/`javap`. A balanced file that still had to skip a construct it
 * does not understand is [KotlinParse.Parsed] with `confident = false`: a
 * member it did recover is served (provenance [Origin.LEXER]); a member it
 * did not recover degrades instead of pretending the sources omit it.
 */
public fun lexKotlinFile(text: String, fileName: String = ""): KotlinParse {
    val label = fileName.ifBlank { "file" }
    return try {
        val scan = KtScan(text)
        scan.scan()
        if (!scan.ok) {
            return KotlinParse.Unavailable(
                "kotlin lexer could not scan $label: ${scan.detail}",
            )
        }
        val parser = KtDeclParser(text, scan.tokens)
        val file = parser.parseFile()
        if (!parser.ok) {
            return KotlinParse.Unavailable(
                "kotlin lexer could not match braces in $label",
            )
        }
        KotlinParse.Parsed(file, origin = Origin.LEXER, confident = parser.confident)
    } catch (e: Exception) {
        KotlinParse.Unavailable(
            "kotlin lexer failed on $label: ${e.message ?: e.javaClass.simpleName}",
        )
    }
}

/**
 * A [KotlinSourceParser] that never touches the compiler sidecar.
 * [close] is a no-op; the object holds no file handles.
 */
public object LexerKotlinParser : KotlinSourceParser {
    override val available: Boolean = true
    override val detail: String =
        "kotlin lexer (brace-matched .kt, no compiler sidecar)"

    override fun parseKotlin(text: String, fileName: String): KotlinParse =
        lexKotlinFile(text, fileName)

    override fun close(): Unit = Unit
}

private enum class Kind {
    IDENT, STRING, CHAR, NL,
    LBRACE, RBRACE, LPAREN, RPAREN, LBRACK, RBRACK,
    LT, GT, EQ, COLON, COMMA, DOT, SEMI, QUESTION, AT, STAR, OTHER,
}

private data class Tok(
    val kind: Kind,
    val start: Int,
    val end: Int,
    val text: String,
    val doc: DocSpan?,
)

private data class DocSpan(val start: Int, val end: Int, val raw: String)

private class KtScan(private val text: String) {
    var ok: Boolean = true
    var detail: String = "unbalanced input"
    val tokens: MutableList<Tok> = mutableListOf()
    private var pendingDoc: DocSpan? = null
    private var brace: Int = 0
    private var paren: Int = 0
    private var bracket: Int = 0

    fun scan() {
        var i = 0
        if (i < text.length && text[i] == '\uFEFF') i++
        if (text.startsWith("#!", i)) {
            val nl = text.indexOf('\n', i)
            i = if (nl < 0) text.length else nl
        }
        while (i < text.length && ok) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '\n' -> {
                    emit(Kind.NL, i, i + 1, "\n", takeDoc = false)
                    i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> i = lineComment(i)
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val next = blockComment(i)
                    if (next < 0) {
                        fail("unclosed block comment")
                        return
                    }
                    i = next
                }
                c == '"' -> {
                    val start = i
                    val next = stringAt(i)
                    if (next < 0) {
                        fail("unclosed string")
                        return
                    }
                    emit(Kind.STRING, start, next, "\"")
                    i = next
                }
                c == '\'' -> {
                    val start = i
                    val next = charAt(i)
                    if (next < 0) {
                        fail("unclosed character literal")
                        return
                    }
                    emit(Kind.CHAR, start, next, "'")
                    i = next
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close < 0) {
                        fail("unclosed identifier")
                        return
                    }
                    emit(Kind.IDENT, i, close + 1, text.substring(i + 1, close))
                    i = close + 1
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    emit(Kind.IDENT, start, i, text.substring(start, i))
                }
                c.isDigit() -> {
                    val start = i
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
                    emit(Kind.OTHER, start, i, text.substring(start, i))
                }
                c == '-' && i + 1 < text.length && text[i + 1] == '>' -> {
                    emit(Kind.OTHER, i, i + 2, "->")
                    i += 2
                }
                else -> {
                    val kind = when (c) {
                        '{' -> Kind.LBRACE
                        '}' -> Kind.RBRACE
                        '(' -> Kind.LPAREN
                        ')' -> Kind.RPAREN
                        '[' -> Kind.LBRACK
                        ']' -> Kind.RBRACK
                        '<' -> Kind.LT
                        '>' -> Kind.GT
                        '=' -> Kind.EQ
                        ':' -> Kind.COLON
                        ',' -> Kind.COMMA
                        '.' -> Kind.DOT
                        ';' -> Kind.SEMI
                        '?' -> Kind.QUESTION
                        '@' -> Kind.AT
                        '*' -> Kind.STAR
                        else -> Kind.OTHER
                    }
                    emit(kind, i, i + 1, c.toString())
                    i++
                }
            }
        }
        if (!ok) return
        if (brace != 0 || paren != 0 || bracket != 0) {
            fail("unbalanced delimiter")
        }
    }

    private fun fail(message: String) {
        ok = false
        detail = message
    }

    private fun emit(kind: Kind, start: Int, end: Int, text: String, takeDoc: Boolean = true) {
        when (kind) {
            Kind.LBRACE -> brace++
            Kind.RBRACE -> {
                brace--
                if (brace < 0) fail("unbalanced delimiter")
            }
            Kind.LPAREN -> paren++
            Kind.RPAREN -> {
                paren--
                if (paren < 0) fail("unbalanced delimiter")
            }
            Kind.LBRACK -> bracket++
            Kind.RBRACK -> {
                bracket--
                if (bracket < 0) fail("unbalanced delimiter")
            }
            else -> Unit
        }
        val doc = if (takeDoc) pendingDoc else null
        if (takeDoc) pendingDoc = null
        tokens += Tok(kind, start, end, text, doc)
    }

    private fun lineComment(start: Int): Int {
        val nl = text.indexOf('\n', start)
        return if (nl < 0) text.length else nl
    }

    private fun blockComment(start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < text.length && depth > 0) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                }
                else -> i++
            }
        }
        if (depth != 0) return -1
        val raw = text.substring(start, i)
        if (raw.startsWith("/**") && !raw.startsWith("/**/")) {
            pendingDoc = DocSpan(start, i, raw)
        }
        return i
    }

    private fun stringAt(start: Int): Int {
        if (text.startsWith("\"\"\"", start)) return rawString(start)
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == '"' -> return i + 1
                c == '\n' -> return -1
                c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                    val after = interpolation(i + 1)
                    if (after < 0) return -1
                    i = after
                }
                c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                    i += 2
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                }
                else -> i++
            }
        }
        return -1
    }

    private fun rawString(start: Int): Int {
        var i = start + 3
        while (i < text.length) {
            if (text.startsWith("\"\"\"", i)) return i + 3
            if (text[i] == '$' && i + 1 < text.length && text[i + 1] == '{') {
                val after = interpolation(i + 1)
                if (after < 0) return -1
                i = after
                continue
            }
            if (text[i] == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                i += 2
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                continue
            }
            i++
        }
        return -1
    }

    /** Index after the `}` that closes a `${` interpolation, or -1. */
    private fun interpolation(openBrace: Int): Int {
        var i = openBrace + 1
        var depth = 1
        while (i < text.length && depth > 0) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    val nl = text.indexOf('\n', i)
                    i = if (nl < 0) text.length else nl
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val next = blockComment(i)
                    if (next < 0) return -1
                    i = next
                }
                c == '"' -> {
                    val next = stringAt(i)
                    if (next < 0) return -1
                    i = next
                }
                c == '\'' -> {
                    val next = charAt(i)
                    if (next < 0) return -1
                    i = next
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return if (depth == 0) i else -1
    }

    private fun charAt(start: Int): Int {
        var i = start + 1
        if (i >= text.length) return -1
        if (text[i] == '\\') {
            i++
            if (i >= text.length) return -1
            if (text[i] == 'u') {
                i++
                var hex = 0
                while (hex < 4 && i < text.length && text[i].isHex()) {
                    i++
                    hex++
                }
            } else {
                i++
            }
        } else if (text[i] == '\n' || text[i] == '\'') {
            return -1
        } else {
            i++
        }
        if (i >= text.length || text[i] != '\'') return -1
        return i + 1
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

private data class Flags(val isSuspend: Boolean, val isEnum: Boolean)

private data class RichParam(
    val param: KotlinParam,
    val property: Boolean,
    val start: Int,
    val end: Int,
)

private class KtDeclParser(
    private val text: String,
    private val tokens: List<Tok>,
) {
    var ok: Boolean = true
    var confident: Boolean = true
    private var index: Int = 0
    private var packageName: String = ""
    private var fileDoc: DocSpan? = null

    fun parseFile(): KotlinFile {
        val decls = parseDeclList()
        return KotlinFile(
            packageName = packageName,
            declarations = decls,
            fileDocText = fileDoc?.let { kdocInnerOf(it.raw) },
            fileDocStartOffset = fileDoc?.start,
            fileDocEndOffset = fileDoc?.end,
        )
    }

    private fun parseDeclList(): List<KotlinDecl> {
        val out = mutableListOf<KotlinDecl>()
        while (!eof()) {
            skipSep()
            if (eof() || peekKind() == Kind.RBRACE) break
            val before = index
            when (val key = keywordAhead()) {
                "fun" -> parseFunction()?.let { out += it }
                "val", "var" -> parseProperty()?.let { out += it }
                "class", "interface" -> parseClassLike()?.let { out += it }
                "object", "companion" -> parseObject()?.let { out += it }
                "constructor" -> parseConstructor()?.let { out += it }
                "package" -> skipPackage()
                "import", "typealias" -> skipStatement()
                "init" -> skipInit()
                else -> {
                    confident = false
                    if (!skipStatement()) break
                }
            }
            if (index == before) {
                ok = false
                break
            }
        }
        return out
    }

    private fun parseFunction(): KotlinDecl? {
        val doc = leadingDoc()
        val declStart = peek()?.start ?: return null
        val flags = readPrefix()
        if (peekText() != "fun") {
            confident = false
            return null
        }
        advance()
        if (peekText() == "interface") return parseClassLike(flags, doc, declStart, keywordPending = true)
        skipTypeParams()
        val callable = readCallableName { it.kind == Kind.LPAREN }
        if (callable == null) {
            confident = false
            skipStatement()
            return null
        }
        val params = if (peekKind() == Kind.LPAREN) parseParams().map { it.param } else emptyList()
        val ret = readReturnType()
        val sigEnd = tokens.getOrNull(index - 1)?.end ?: callable.nameStart
        val end = finishBody(sigEnd)
        return functionDecl(callable.name, params, ret, callable.receiver, flags.isSuspend, doc, declStart, end)
    }

    private fun parseProperty(): KotlinDecl? {
        val doc = leadingDoc()
        val declStart = peek()?.start ?: return null
        readPrefix()
        if (peekText() != "val" && peekText() != "var") {
            confident = false
            return null
        }
        advance()
        skipTypeParams()
        val callable = readCallableName { stopProperty(it) }
        if (callable == null) {
            confident = false
            skipStatement()
            return null
        }
        if (peekKind() == Kind.COLON) {
            advance()
            readType(stopComma = false)
        }
        var end = tokens.getOrNull(index - 1)?.end ?: callable.nameStart
        if (peekKind() == Kind.EQ || peekText() == "by") {
            advance()
            end = scanExpr()
        }
        end = consumeAccessors(end)
        return propertyDecl(callable.name, callable.receiver, doc, declStart, end)
    }

    private fun parseClassLike(
        preset: Flags? = null,
        presetDoc: DocSpan? = null,
        presetStart: Int? = null,
        keywordPending: Boolean = false,
    ): KotlinDecl? {
        val doc = presetDoc ?: leadingDoc()
        val declStart = presetStart ?: peek()?.start ?: return null
        val flags = preset ?: readPrefix()
        if (!keywordPending) {
            if (peekText() != "class" && peekText() != "interface") {
                confident = false
                return null
            }
        }
        if (peekText() == "class" || peekText() == "interface") advance()
        skipTypeParams()
        if (peekKind() != Kind.IDENT) {
            confident = false
            skipStatement()
            return null
        }
        val name = peekText()
        advance()
        val ctor = readPrimaryCtor()
        val headerEnd = skipHeaderTail()
        val body: List<KotlinDecl>
        val end: Int
        if (peekKind() == Kind.LBRACE) {
            if (flags.isEnum) {
                val parsed = parseEnumBody()
                body = parsed.decls
                end = parsed.end
            } else {
                body = parseBraceDecls()
                end = tokens.getOrNull(index - 1)?.end ?: headerEnd
            }
        } else {
            body = emptyList()
            end = headerEnd
        }
        val props = ctor?.properties.orEmpty().map { propertyDecl(it.param.name, null, null, it.start, it.end) }
        return KotlinDecl(
            kind = KotlinDeclKind.CLASS,
            name = name,
            startOffset = doc?.start ?: declStart,
            endOffset = end,
            docText = doc?.let { kdocInnerOf(it.raw) },
            docStartOffset = doc?.start,
            docEndOffset = doc?.end,
            children = props + body,
            primaryCtor = ctor?.let { ctorDecl(it) },
        )
    }

    private fun parseObject(): KotlinDecl? {
        val doc = leadingDoc()
        val declStart = peek()?.start ?: return null
        readPrefix()
        var companion = false
        if (peekText() == "companion") {
            companion = true
            advance()
        }
        if (peekText() != "object") {
            confident = false
            skipStatement()
            return null
        }
        advance()
        val name = if (peekKind() == Kind.IDENT) {
            val value = peekText()
            advance()
            value
        } else if (companion) {
            "Companion"
        } else {
            confident = false
            skipStatement()
            return null
        }
        val headerEnd = skipHeaderTail()
        val children: List<KotlinDecl>
        val end: Int
        if (peekKind() == Kind.LBRACE) {
            children = parseBraceDecls()
            end = tokens.getOrNull(index - 1)?.end ?: headerEnd
        } else {
            children = emptyList()
            end = headerEnd
        }
        return KotlinDecl(
            kind = KotlinDeclKind.OBJECT,
            name = name,
            startOffset = doc?.start ?: declStart,
            endOffset = end,
            docText = doc?.let { kdocInnerOf(it.raw) },
            docStartOffset = doc?.start,
            docEndOffset = doc?.end,
            children = children,
            isCompanion = companion,
        )
    }

    private fun parseConstructor(): KotlinDecl? {
        val doc = leadingDoc()
        val declStart = peek()?.start ?: return null
        readPrefix()
        if (peekText() != "constructor") {
            confident = false
            return null
        }
        advance()
        val params = if (peekKind() == Kind.LPAREN) parseParams().map { it.param } else emptyList()
        val headerEnd = skipHeaderTail()
        val end = if (peekKind() == Kind.LBRACE) skipGroup(Kind.LBRACE, Kind.RBRACE) else headerEnd
        return KotlinDecl(
            kind = KotlinDeclKind.CONSTRUCTOR,
            name = "<init>",
            params = params,
            startOffset = doc?.start ?: declStart,
            endOffset = end,
            docText = doc?.let { kdocInnerOf(it.raw) },
            docStartOffset = doc?.start,
            docEndOffset = doc?.end,
        )
    }

    private fun parseEnumBody(): EnumBody {
        advance() // '{'
        val entries = mutableListOf<KotlinDecl>()
        while (!eof()) {
            // A semicolon here starts the member list. skipSep would swallow it.
            while (peekKind() == Kind.NL) advance()
            if (eof() || peekKind() == Kind.RBRACE || peekKind() == Kind.SEMI) break
            if (peekKind() == Kind.COMMA) {
                advance()
                continue
            }
            val entry = parseEnumEntry()
            if (entry == null) {
                confident = false
                if (!skipStatement()) break
                continue
            }
            entries += entry
        }
        val members = if (peekKind() == Kind.SEMI) {
            advance()
            parseDeclList()
        } else {
            emptyList()
        }
        val end = if (peekKind() == Kind.RBRACE) {
            val close = advance()
            close?.end ?: 0
        } else {
            ok = false
            tokens.getOrNull(index - 1)?.end ?: 0
        }
        return EnumBody(entries + members, end)
    }

    private fun parseEnumEntry(): KotlinDecl? {
        val doc = leadingDoc()
        val start = peek()?.start ?: return null
        skipAnnotations()
        if (peekKind() != Kind.IDENT) return null
        val name = peekText()
        advance()
        var end = tokens[index - 1].end
        if (peekKind() == Kind.LPAREN) end = skipGroup(Kind.LPAREN, Kind.RPAREN)
        val children = if (peekKind() == Kind.LBRACE) {
            val kids = parseBraceDecls()
            end = tokens.getOrNull(index - 1)?.end ?: end
            kids
        } else {
            emptyList()
        }
        return KotlinDecl(
            kind = KotlinDeclKind.ENUM_ENTRY,
            name = name,
            startOffset = doc?.start ?: start,
            endOffset = end,
            docText = doc?.let { kdocInnerOf(it.raw) },
            docStartOffset = doc?.start,
            docEndOffset = doc?.end,
            children = children,
        )
    }

    private fun parseBraceDecls(): List<KotlinDecl> {
        advance() // '{'
        val decls = parseDeclList()
        if (peekKind() == Kind.RBRACE) advance() else ok = false
        return decls
    }

    private data class PrimaryCtor(val params: List<RichParam>, val start: Int, val end: Int) {
        val properties: List<RichParam> = params.filter { it.property && it.param.name.isNotEmpty() }
    }

    private data class EnumBody(val decls: List<KotlinDecl>, val end: Int)

    private data class CallableName(val name: String, val receiver: String?, val nameStart: Int)

    private fun readPrimaryCtor(): PrimaryCtor? {
        val save = index
        skipAnnotations()
        while (peekText() in VISIBILITY || peekText() == "constructor") advance()
        if (peekKind() != Kind.LPAREN) {
            index = save
            return null
        }
        val start = peek()?.start ?: save
        val params = parseParams()
        val end = tokens.getOrNull(index - 1)?.end ?: start
        return PrimaryCtor(params, start, end)
    }

    private fun parseParams(): List<RichParam> {
        if (peekKind() != Kind.LPAREN) return emptyList()
        advance()
        val params = mutableListOf<RichParam>()
        while (!eof() && peekKind() != Kind.RPAREN) {
            if (peekKind() == Kind.COMMA || peekKind() == Kind.NL) {
                advance()
                continue
            }
            val before = index
            params += parseOneParam()
            if (index == before) {
                confident = false
                advance()
            }
        }
        if (peekKind() == Kind.RPAREN) advance() else ok = false
        return params
    }

    private fun parseOneParam(): RichParam {
        val start = peek()?.start ?: 0
        skipAnnotations()
        var property = false
        while (peekText() in PARAM_MODIFIERS) {
            if (peekText() == "val" || peekText() == "var") property = true
            advance()
        }
        if (peekKind() == Kind.LPAREN) {
            skipGroup(Kind.LPAREN, Kind.RPAREN)
            var type: String? = null
            if (peekKind() == Kind.COLON) {
                advance()
                type = readType(stopComma = true)
            }
            val hasDefault = consumeDefault()
            val end = tokens.getOrNull(index - 1)?.end ?: start
            return RichParam(KotlinParam("", type, hasDefault), property = false, start, end)
        }
        val name = if (peekKind() == Kind.IDENT) {
            val value = peekText()
            advance()
            value
        } else {
            ""
        }
        var type: String? = null
        if (peekKind() == Kind.COLON) {
            advance()
            type = readType(stopComma = true)
        }
        val hasDefault = consumeDefault()
        val end = tokens.getOrNull(index - 1)?.end ?: start
        return RichParam(KotlinParam(name, type, hasDefault), property, start, end)
    }

    private fun consumeDefault(): Boolean {
        if (peekKind() != Kind.EQ) return false
        advance()
        var paren = 0
        var brace = 0
        var bracket = 0
        var angle = 0
        while (!eof()) {
            val t = peek()!!
            if (paren == 0 && brace == 0 && bracket == 0 && angle == 0 &&
                (t.kind == Kind.COMMA || t.kind == Kind.RPAREN)
            ) {
                break
            }
            when (t.kind) {
                Kind.LPAREN -> paren++
                Kind.RPAREN -> if (paren == 0) break else paren--
                Kind.LBRACE -> brace++
                Kind.RBRACE -> if (brace == 0) break else brace--
                Kind.LBRACK -> bracket++
                Kind.RBRACK -> if (bracket == 0) break else bracket--
                Kind.LT -> angle++
                Kind.GT -> if (angle > 0) angle--
                else -> Unit
            }
            advance()
        }
        return true
    }

    private fun readCallableName(stop: (Tok) -> Boolean): CallableName? {
        val chunkStart = index
        var angle = 0
        var paren = 0
        while (!eof()) {
            val t = peek()!!
            if (angle == 0 && paren == 0 && stop(t)) break
            when (t.kind) {
                Kind.LT -> angle++
                Kind.GT -> if (angle > 0) angle--
                Kind.LPAREN -> paren++
                Kind.RPAREN -> if (paren == 0) break else paren--
                else -> Unit
            }
            advance()
        }
        if (chunkStart == index) return null
        val chunk = tokens.subList(chunkStart, index)
        val nameTok = chunk.lastOrNull { it.kind == Kind.IDENT } ?: return null
        val nameAt = chunk.indexOfLast { it.start == nameTok.start && it.kind == Kind.IDENT }
        var depth = 0
        var receiverStart: Int? = null
        var dotAt: Int? = null
        for (cursor in 0 until nameAt) {
            val t = chunk[cursor]
            when (t.kind) {
                Kind.LT -> depth++
                Kind.GT -> if (depth > 0) depth--
                Kind.DOT -> if (depth == 0) {
                    if (receiverStart == null) receiverStart = chunk.first().start
                    dotAt = t.start
                }
                else -> Unit
            }
        }
        val receiver = if (dotAt != null && receiverStart != null && dotAt > receiverStart) {
            text.substring(receiverStart, dotAt).trim().ifBlank { null }
        } else {
            null
        }
        return CallableName(nameTok.text, receiver, nameTok.start)
    }

    private fun readReturnType(): String? {
        var type: String? = null
        if (peekKind() == Kind.COLON) {
            advance()
            type = readType(stopComma = false)
        }
        if (peekText() == "where") {
            advance()
            var angle = 0
            var paren = 0
            while (!eof()) {
                val t = peek()!!
                if (angle == 0 && paren == 0 && (t.kind == Kind.LBRACE || t.kind == Kind.EQ)) break
                when (t.kind) {
                    Kind.LT -> angle++
                    Kind.GT -> if (angle > 0) angle--
                    Kind.LPAREN -> paren++
                    Kind.RPAREN -> if (paren > 0) paren--
                    else -> Unit
                }
                advance()
            }
        }
        return type
    }

    private fun readType(stopComma: Boolean): String? {
        val startTok = peek() ?: return null
        val start = startTok.start
        var end = start
        var angle = 0
        var paren = 0
        var bracket = 0
        var saw = false
        while (!eof()) {
            val t = peek()!!
            val boundary = angle == 0 && paren == 0 && bracket == 0 && (
                t.kind == Kind.EQ || t.kind == Kind.LBRACE || t.kind == Kind.NL ||
                    t.kind == Kind.RBRACE || t.kind == Kind.SEMI || t.text == "where" ||
                    t.text == "by" || (stopComma && t.kind == Kind.COMMA) ||
                    t.text == "get" || t.text == "set"
                )
            if (boundary) break
            when (t.kind) {
                Kind.LT -> angle++
                Kind.GT -> if (angle > 0) angle--
                Kind.LPAREN -> paren++
                Kind.RPAREN -> if (paren == 0) break else paren--
                Kind.LBRACK -> bracket++
                Kind.RBRACK -> if (bracket == 0) break else bracket--
                else -> Unit
            }
            end = t.end
            saw = true
            advance()
        }
        if (!saw || end <= start) return null
        return text.substring(start, end).replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }

    private fun finishBody(sigEnd: Int): Int {
        val save = index
        skipSep()
        return when (peekKind()) {
            Kind.LBRACE -> skipGroup(Kind.LBRACE, Kind.RBRACE)
            Kind.EQ -> {
                advance()
                scanExpr()
            }
            else -> {
                if (peekText() == "by") {
                    advance()
                    scanExpr()
                } else {
                    index = save
                    sigEnd
                }
            }
        }
    }

    private fun consumeAccessors(initial: Int): Int {
        var end = initial
        while (true) {
            val save = index
            skipSep()
            skipAnnotations()
            while (peekText() in VISIBILITY) advance()
            if (peekText() == "get" || peekText() == "set") {
                advance()
                if (peekKind() == Kind.LPAREN) parseParams()
                if (peekKind() == Kind.COLON) {
                    advance()
                    readType(stopComma = false)
                }
                end = when (peekKind()) {
                    Kind.EQ -> {
                        advance()
                        scanExpr()
                    }
                    Kind.LBRACE -> skipGroup(Kind.LBRACE, Kind.RBRACE)
                    else -> tokens.getOrNull(index - 1)?.end ?: end
                }
            } else {
                index = save
                break
            }
        }
        return end
    }

    private fun scanExpr(): Int {
        var paren = 0
        var brace = 0
        var bracket = 0
        var end = tokens.getOrNull(index - 1)?.end ?: 0
        while (!eof()) {
            val t = peek()!!
            when (t.kind) {
                Kind.LPAREN -> {
                    paren++
                    advance()
                    end = t.end
                }
                Kind.RPAREN -> {
                    if (paren == 0) return end
                    paren--
                    advance()
                    end = t.end
                }
                Kind.LBRACE -> {
                    brace++
                    advance()
                    end = t.end
                }
                Kind.RBRACE -> {
                    if (brace == 0) return end
                    brace--
                    advance()
                    end = t.end
                }
                Kind.LBRACK -> {
                    bracket++
                    advance()
                    end = t.end
                }
                Kind.RBRACK -> {
                    if (bracket == 0) return end
                    bracket--
                    advance()
                    end = t.end
                }
                Kind.NL -> {
                    if (paren == 0 && brace == 0 && bracket == 0 && isBoundary(index + 1)) return end
                    advance()
                }
                Kind.SEMI -> {
                    if (paren == 0 && brace == 0 && bracket == 0) {
                        advance()
                        return t.end
                    }
                    advance()
                    end = t.end
                }
                else -> {
                    advance()
                    end = t.end
                }
            }
        }
        return end
    }

    private fun isBoundary(from: Int): Boolean {
        var cursor = from
        while (cursor < tokens.size && tokens[cursor].kind == Kind.NL) cursor++
        if (cursor >= tokens.size) return true
        val t = tokens[cursor]
        if (t.kind == Kind.RBRACE || t.kind == Kind.SEMI || t.kind == Kind.AT) return true
        if (t.text in DECL_WORDS || t.text in MODIFIERS || t.text == "enum" ||
            t.text == "get" || t.text == "set" || t.text == "companion"
        ) {
            return true
        }
        return false
    }

    private fun skipHeaderTail(): Int {
        var end = tokens.getOrNull(index - 1)?.end ?: 0
        var paren = 0
        var angle = 0
        var brace = 0
        while (!eof()) {
            val t = peek()!!
            if (t.kind == Kind.LBRACE && paren == 0 && angle == 0 && brace == 0) return end
            if (t.kind == Kind.NL && paren == 0 && angle == 0 && brace == 0) return end
            when (t.kind) {
                Kind.LBRACE -> brace++
                Kind.RBRACE -> {
                    if (brace == 0) return end
                    brace--
                }
                Kind.LPAREN -> paren++
                Kind.RPAREN -> if (paren > 0) paren--
                Kind.LT -> angle++
                Kind.GT -> if (angle > 0) angle--
                else -> Unit
            }
            advance()
            end = t.end
        }
        return end
    }

    private fun skipTypeParams() {
        if (peekKind() != Kind.LT) return
        var angle = 0
        while (!eof()) {
            when (peekKind()) {
                Kind.LT -> angle++
                Kind.GT -> {
                    angle--
                    advance()
                    if (angle == 0) return
                    continue
                }
                else -> Unit
            }
            advance()
        }
    }

    private fun skipPackage() {
        val doc = leadingDoc()
        if (doc != null && fileDoc == null) fileDoc = doc
        advance()
        val start = peek()?.start
        var end = start
        while (peekKind() == Kind.IDENT || peekKind() == Kind.DOT) {
            end = peek()?.end
            advance()
        }
        if (packageName.isEmpty() && start != null && end != null && end > start) {
            packageName = text.substring(start, end)
        }
        if (peekKind() == Kind.NL || peekKind() == Kind.SEMI) advance()
    }

    private fun skipInit() {
        readPrefix()
        if (peekText() == "init") advance()
        if (peekKind() == Kind.LBRACE) skipGroup(Kind.LBRACE, Kind.RBRACE)
    }

    private fun skipStatement(): Boolean {
        if (eof()) return false
        var paren = 0
        var brace = 0
        var angle = 0
        while (!eof()) {
            val t = peek()!!
            if (paren == 0 && brace == 0 && angle == 0 &&
                (t.kind == Kind.NL || t.kind == Kind.SEMI || t.kind == Kind.RBRACE)
            ) {
                if (t.kind != Kind.RBRACE) advance()
                return true
            }
            when (t.kind) {
                Kind.LPAREN -> paren++
                Kind.RPAREN -> if (paren > 0) paren--
                Kind.LBRACE -> brace++
                Kind.RBRACE -> if (brace == 0) return true else brace--
                Kind.LT -> angle++
                Kind.GT -> if (angle > 0) angle--
                else -> Unit
            }
            advance()
        }
        return true
    }

    private fun readPrefix(): Flags {
        var suspend = false
        var isEnum = false
        while (!eof()) {
            skipSep()
            if (eof()) break
            if (peekKind() == Kind.AT) {
                skipAnnotations()
                continue
            }
            if (peekText() == "context" && lookKind(1) == Kind.LPAREN) {
                advance()
                skipGroup(Kind.LPAREN, Kind.RPAREN)
                continue
            }
            val word = peekText()
            if (word == "enum") {
                isEnum = true
                advance()
                continue
            }
            if (word !in MODIFIERS) break
            if (word == "suspend") suspend = true
            advance()
        }
        return Flags(suspend, isEnum)
    }

    private fun skipAnnotations() {
        while (peekKind() == Kind.AT) {
            advance()
            while (!eof()) {
                val kind = peekKind()
                if (kind == Kind.LPAREN || kind == Kind.NL || kind == Kind.AT || kind == Kind.LBRACE) break
                if (kind == Kind.IDENT || kind == Kind.DOT || kind == Kind.COLON) {
                    advance()
                    continue
                }
                break
            }
            if (peekKind() == Kind.LPAREN) skipGroup(Kind.LPAREN, Kind.RPAREN)
        }
    }

    private fun keywordAhead(): String? {
        var cursor = index
        while (cursor < tokens.size) {
            val t = tokens[cursor]
            if (t.kind == Kind.NL || t.kind == Kind.SEMI) {
                cursor++
                continue
            }
            if (t.kind == Kind.AT) {
                cursor = afterAnnotation(cursor)
                continue
            }
            if (t.text == "context" && tokens.getOrNull(cursor + 1)?.kind == Kind.LPAREN) {
                cursor = afterGroup(cursor + 1, Kind.LPAREN, Kind.RPAREN)
                continue
            }
            if (t.text in MODIFIERS || t.text == "enum") {
                cursor++
                continue
            }
            return t.text
        }
        return null
    }

    private fun afterAnnotation(start: Int): Int {
        var cursor = start + 1
        while (cursor < tokens.size) {
            val kind = tokens[cursor].kind
            if (kind == Kind.LPAREN || kind == Kind.NL || kind == Kind.AT || kind == Kind.LBRACE) break
            if (kind == Kind.IDENT || kind == Kind.DOT || kind == Kind.COLON) {
                cursor++
                continue
            }
            break
        }
        if (cursor < tokens.size && tokens[cursor].kind == Kind.LPAREN) {
            return afterGroup(cursor, Kind.LPAREN, Kind.RPAREN)
        }
        return cursor
    }

    private fun afterGroup(openAt: Int, open: Kind, close: Kind): Int {
        var depth = 0
        var cursor = openAt
        while (cursor < tokens.size) {
            when (tokens[cursor].kind) {
                open -> depth++
                close -> {
                    depth--
                    cursor++
                    if (depth == 0) return cursor
                    continue
                }
                else -> Unit
            }
            cursor++
        }
        return tokens.size
    }

    private fun skipGroup(open: Kind, close: Kind): Int {
        var depth = 0
        var end = peek()?.end ?: return 0
        while (!eof()) {
            val t = advance() ?: break
            end = t.end
            when (t.kind) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return end
                }
                else -> Unit
            }
        }
        ok = false
        return end
    }

    private fun skipSep() {
        while (peekKind() == Kind.NL || peekKind() == Kind.SEMI) advance()
    }

    private fun stopProperty(t: Tok): Boolean =
        t.kind == Kind.COLON || t.kind == Kind.EQ || t.kind == Kind.NL ||
            t.kind == Kind.LBRACE || t.text == "by" || t.text == "get" || t.text == "set"

    private fun leadingDoc(): DocSpan? = peek()?.doc

    private fun functionDecl(
        name: String,
        params: List<KotlinParam>,
        ret: String?,
        receiver: String?,
        suspend: Boolean,
        doc: DocSpan?,
        declStart: Int,
        end: Int,
    ): KotlinDecl = KotlinDecl(
        kind = KotlinDeclKind.FUNCTION,
        name = name,
        params = params,
        returnType = ret,
        receiverType = receiver,
        isSuspend = suspend,
        startOffset = doc?.start ?: declStart,
        endOffset = end,
        docText = doc?.let { kdocInnerOf(it.raw) },
        docStartOffset = doc?.start,
        docEndOffset = doc?.end,
    )

    private fun propertyDecl(
        name: String,
        receiver: String?,
        doc: DocSpan?,
        start: Int,
        end: Int,
    ): KotlinDecl = KotlinDecl(
        kind = KotlinDeclKind.PROPERTY,
        name = name,
        receiverType = receiver,
        startOffset = doc?.start ?: start,
        endOffset = end,
        docText = doc?.let { kdocInnerOf(it.raw) },
        docStartOffset = doc?.start,
        docEndOffset = doc?.end,
    )

    private fun ctorDecl(ctor: PrimaryCtor): KotlinDecl = KotlinDecl(
        kind = KotlinDeclKind.CONSTRUCTOR,
        name = "<init>",
        params = ctor.params.map { it.param },
        startOffset = ctor.start,
        endOffset = ctor.end,
        docText = null,
        docStartOffset = null,
        docEndOffset = null,
    )

    private fun eof(): Boolean = index >= tokens.size
    private fun peek(): Tok? = tokens.getOrNull(index)
    private fun peekKind(): Kind? = peek()?.kind
    private fun peekText(): String = peek()?.text ?: ""
    private fun lookKind(ahead: Int): Kind? = tokens.getOrNull(index + ahead)?.kind
    private fun advance(): Tok? = tokens.getOrNull(index++)
}

private val MODIFIERS: Set<String> = setOf(
    "public", "private", "protected", "internal",
    "open", "final", "abstract", "override",
    "suspend", "inline", "infix", "operator", "tailrec",
    "external", "expect", "actual", "const", "lateinit",
    "data", "sealed", "inner", "value", "annotation",
    "crossinline", "noinline", "reified", "vararg",
    "out", "in",
)

private val DECL_WORDS: Set<String> = setOf(
    "fun", "val", "var", "class", "interface", "object", "typealias",
    "constructor", "init", "package", "import", "companion",
)

private val VISIBILITY: Set<String> = setOf("public", "private", "protected", "internal")

private val PARAM_MODIFIERS: Set<String> = VISIBILITY + setOf("val", "var", "vararg", "noinline", "crossinline")
