package dev.jdx.core.ref

import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MavenCoordinate
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.SymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import dev.jdx.core.model.arrayTypeName

/**
 * Parses the javadoc-style symbol reference grammar from PROPOSAL.md §6 (D-014):
 * generous on input, canonical on output. Pure string work — no IO, no resolution
 * against any workspace. An under-specified ref (short names, missing parameter
 * list) is a legal parse; deciding what it *means* is resolution's job (D-016).
 *
 * Disambiguation rules, since the grammar is deliberately generous (D-025):
 *  - `$` in a type name always separates nesting; `.` separates nesting **or** the
 *    package from the class. The split uses the Java naming convention: a run of
 *    leading dot-separated segments whose first character is lowercase is the
 *    package, except that the final segment is always the class. So
 *    `java.util.Map.Entry`, `java.util.Map$Entry` and `Map.Entry`/`Map$Entry`
 *    normalise as the acceptance table requires; a package with an uppercase first
 *    segment cannot be expressed (round-trip limitation, see D-025).
 *  - `.` acts as the member separator only when a parameter list follows, or when
 *    the segment is `<init>`/`<clinit>`; a bare `Type.name` is a nested type
 *    (indistinguishable from a field without resolution).
 *  - Params starting `(` are first tried as a complete JVM method descriptor
 *    (`(Ljava/lang/Object;)Ljava/lang/String;`); if that fails they are a
 *    comma-separated source-form parameter list.
 *  - A `*` anywhere in a member-less ref makes it a package glob
 *    ([PackageSymbolRef] keeps the pattern verbatim; `search`/`ls` consume it).
 *  - `(...)` — a bare ellipsis parameter list — means "parameters unspecified",
 *    the same under-specified form as no parameter list at all.
 */
public object SymbolRefParser {

    public fun parse(text: String): SymbolRefParseResult {
        val lead = text.indexOfFirst { !it.isWhitespace() }
        if (lead == -1) return SymbolRefParseResult.Failure("empty reference", 0)
        val base = lead
        val body = text.substring(lead).trimEnd()
        return try {
            SymbolRefParseResult.Ok(parseTop(body, base))
        } catch (e: RefSyntaxException) {
            SymbolRefParseResult.Failure(e.message ?: "syntax error", e.position)
        } catch (e: IllegalArgumentException) {
            // The model's own invariants (TypeName etc.) reject malformed names with
            // require() — reachable from the descriptor path, whose internals we do not
            // re-validate. Agent input reaching this is a parse failure, never a crash.
            SymbolRefParseResult.Failure("malformed reference: ${e.message}", 0)
        }
    }

    // ---------------------------------------------------------------- top level

    private fun parseTop(s: String, base: Int): SymbolRef {
        // A '/' starts a coordinate prefix only when it precedes any member syntax;
        // inside a parameter list '/' is either a descriptor separator (legal) or
        // garbage (rejected later by name validation).
        val memberZoneStart = earliestOf(s.indexOf('#'), s.indexOf("::"), s.indexOf('('))
        val slash = s.indexOf('/')

        var coordinate: MavenCoordinate? = null
        var rest = s
        var restBase = base
        if (slash != -1 && (memberZoneStart == -1 || slash < memberZoneStart)) {
            val parts = s.substring(0, slash).split(':')
            if (parts.size != 3 || parts.any { it.isBlank() }) {
                fail("a Maven coordinate before '/' must be group:artifact:version", base + slash)
            }
            coordinate = MavenCoordinate(parts[0].trim(), parts[1].trim(), parts[2].trim())
            rest = s.substring(slash + 1)
            restBase = base + slash + 1
            if (rest.isBlank()) fail("missing type after coordinate '/'", restBase)
            val secondSlash = rest.indexOf('/')
            if (secondSlash != -1) fail("'/' is only valid between a coordinate and a type", restBase + secondSlash)
        }

        val split = splitTypeAndMember(rest)
        val typeText = split.first
        val memberText = split.second
        val memberBase = restBase + split.third

        // Package globs: any '*' in a member-less ref. Patterns are kept verbatim.
        if (memberText == null) {
            if (typeText.contains('*')) {
                if (coordinate != null) {
                    fail("coordinate scoping is not supported for package globs", restBase)
                }
                return parsePackagePattern(typeText.trim(), restBase)
            }
            return TypeSymbolRef(parseTypeName(typeText, restBase), coordinate)
        }
        if (typeText.contains('*')) fail("a glob pattern cannot have members", restBase)
        return parseMember(memberText, memberBase, parseTypeName(typeText, restBase), coordinate)
    }

    /** Splits `rest` into (typeText, memberText | null, offset of memberText within rest). */
    private fun splitTypeAndMember(rest: String): Triple<String, String?, Int> {
        val hash = rest.indexOf('#')
        val doubleColon = rest.indexOf("::")
        if (hash != -1 || doubleColon != -1) {
            val at = when {
                hash != -1 && (doubleColon == -1 || hash < doubleColon) -> hash
                else -> doubleColon
            }
            val typeEnd = at
            if (rest.substring(0, typeEnd).isBlank()) fail("member reference needs a declaring type", 0)
            val memberStart = if (rest[at] == '#') at + 1 else at + 2
            if (rest.substring(memberStart).isBlank()) fail("missing member name after separator", memberStart)
            return Triple(rest.substring(0, typeEnd), rest.substring(memberStart), memberStart)
        }
        val paren = rest.indexOf('(')
        if (paren != -1) {
            // '.' as member separator, disambiguated by the parameter list.
            val lastDot = rest.lastIndexOf('.', paren)
            if (lastDot <= 0) fail("member reference needs a declaring type before '('", paren)
            if (rest.substring(0, lastDot).isBlank()) fail("member reference needs a declaring type", lastDot)
            return Triple(rest.substring(0, lastDot), rest.substring(lastDot + 1), lastDot + 1)
        }
        val lastDot = rest.lastIndexOf('.')
        if (lastDot != -1 && lastDot < rest.length - 1 && rest[lastDot + 1] == '<') {
            // `Type.<init>` without a parameter list.
            if (rest.substring(0, lastDot).isBlank()) fail("member reference needs a declaring type", lastDot)
            return Triple(rest.substring(0, lastDot), rest.substring(lastDot + 1), lastDot + 1)
        }
        return Triple(rest, null, rest.length)
    }

    private fun parsePackagePattern(pattern: String, base: Int): PackageSymbolRef {
        for ((k, c) in pattern.withIndex()) {
            if (c.isWhitespace() || c in "#:,;[]()<>'\"") {
                fail("invalid character '$c' in package pattern", base + k)
            }
        }
        return PackageSymbolRef(pattern)
    }

    // ---------------------------------------------------------------- members

    private fun parseMember(
        text: String,
        base: Int,
        declaringType: TypeName,
        coordinate: MavenCoordinate?,
    ): MemberSymbolRef {
        val paren = text.indexOf('(')
        val name = validateMemberName(
            (if (paren == -1) text else text.substring(0, paren)).trim(),
            base,
        )
        if (paren == -1) return MemberSymbolRef(declaringType, name, null, null, coordinate)

        val afterParen = text.substring(paren)
        // Exact-descriptor form: the whole remainder must be a valid method descriptor.
        val descriptor = JvmDescriptor.parse(afterParen)
        if (descriptor is JvmDescriptor.Method) {
            return MemberSymbolRef(declaringType, name, descriptor.parameters, descriptor.returnType, coordinate)
        }

        // Source-form parameter list: `( a , b )[ : returnType ]`.
        val close = afterParen.indexOf(')')
        if (close == -1) fail("unclosed parameter list", base + paren)
        val paramsRaw = afterParen.substring(1, close)
        val parameterTypes: List<TypeName>? = when (paramsRaw.trim()) {
            "" -> emptyList()
            "..." -> null // ellipsis: parameters unspecified, same as no list
            else -> {
                val pieces = paramsRaw.split(',')
                var cursor = 0
                pieces.map { piece ->
                    val paramStartInPiece = piece.indexOfFirst { !it.isWhitespace() }
                    if (paramStartInPiece == -1) {
                        fail("empty parameter", base + paren + 1 + cursor)
                    }
                    val paramBase = base + paren + 1 + cursor + paramStartInPiece
                    cursor += piece.length + 1
                    parseParamOrReturnType(piece.trim(), paramBase, allowVoid = false)
                }
            }
        }

        var returnType: TypeName? = null
        val afterRaw = afterParen.substring(close + 1)
        val afterTrim = afterRaw.trim()
        if (afterTrim.isNotEmpty()) {
            val afterLead = afterRaw.length - afterRaw.trimStart().length
            if (afterTrim[0] != ':') {
                fail("unexpected text after parameter list", base + paren + close + 1 + afterLead)
            }
            val returnRaw = afterTrim.substring(1)
            val returnLead = returnRaw.length - returnRaw.trimStart().length
            val returnTrim = returnRaw.trim()
            if (returnTrim.isEmpty()) {
                fail("missing return type after ':'", base + paren + close + 1 + afterLead + 1 + returnLead)
            }
            returnType = parseParamOrReturnType(
                returnTrim,
                base + paren + close + 1 + afterLead + 1 + returnLead,
                allowVoid = true,
            )
        }
        return MemberSymbolRef(declaringType, name, parameterTypes, returnType, coordinate)
    }

    private fun validateMemberName(name: String, base: Int): String {
        if (name.isEmpty()) fail("missing member name", base)
        if (name == "<init>" || name == "<clinit>") return name
        if (name[0] == '<') fail("only <init> and <clinit> may start with '<'", base)
        for ((k, c) in name.withIndex()) {
            if (c.isWhitespace() || c in ".#$():;,[]/<>*") {
                fail("invalid character '$c' in member name", base + k)
            }
        }
        return name
    }

    // ---------------------------------------------------------------- type names

    /**
     * Parses a class type name (dotted and/or dollar form, D-025 heuristic) or a
     * primitive keyword. Arrays are handled by [parseParamOrReturnType].
     */
    private fun parseTypeName(text: String, base: Int): TypeName {
        val t = text.trim()
        if (t.isEmpty()) fail("missing type name", base)
        JvmPrimitive.entries.firstOrNull { it.keyword == t }?.let { return TypeName.PrimitiveType(it) }

        val firstDollar = t.indexOf('$')
        val head = if (firstDollar == -1) t else t.substring(0, firstDollar)
        val nested = if (firstDollar == -1) emptyList() else t.substring(firstDollar + 1).split('$')
        if (firstDollar != -1) {
            var idx = firstDollar
            for (segment in nested) {
                idx++ // past '$'
                if (segment.isEmpty()) fail("empty nesting segment after '$'", base + idx - 1)
                checkSegmentChars(segment, base + idx)
                idx += segment.length
            }
        }

        val dotSegments = head.split('.')
        var cursor = 0
        for (segment in dotSegments) {
            if (segment.isEmpty()) fail("empty name segment", base + cursor)
            checkSegmentChars(segment, base + cursor)
            cursor += segment.length + 1
        }

        // D-025: leading lowercase-initial segments are the package; the final
        // dot-segment is always the class. Single-segment names have no package.
        val maxPackageSegments = dotSegments.size - 1
        var packageCount = 0
        while (packageCount < maxPackageSegments && dotSegments[packageCount][0].isLowerCase()) {
            packageCount++
        }
        val packageName = dotSegments.subList(0, packageCount).joinToString(".")
        val classSegments = dotSegments.subList(packageCount, dotSegments.size) + nested
        return TypeName.ClassType(packageName, classSegments)
    }

    /** Characters that can never appear inside a name segment of a source-form type. */
    private val invalidSegmentChars = ";[]()<>*/#:\$,'\""

    private fun checkSegmentChars(segment: String, segmentBase: Int) {
        for ((k, c) in segment.withIndex()) {
            if (c.isWhitespace() || c in invalidSegmentChars) {
                fail("invalid character '$c' in type name", segmentBase + k)
            }
        }
    }

    /**
     * Parses one parameter or return type: a class name or primitive keyword,
     * optionally an array (`[]` per dimension) or varargs (`...`, one dimension).
     * Varargs-ness is not preserved — in a reference it is indistinguishable from
     * an array, exactly as in the erased JVM descriptor.
     */
    private fun parseParamOrReturnType(text: String, base: Int, allowVoid: Boolean): TypeName {
        var dims = 0
        var name = text
        if (name.endsWith("...")) {
            dims++
            name = name.dropLast(3).trim()
        }
        while (name.endsWith("[]")) {
            dims++
            name = name.dropLast(2).trim()
        }
        if (name.isEmpty()) fail("missing type", base)

        if (name == "void") {
            if (!allowVoid) fail("void is not a parameter type", base)
            if (dims > 0) fail("void cannot be an array type", base)
            return TypeName.PrimitiveType(JvmPrimitive.VOID)
        }
        val element = parseTypeName(name, base)
        return if (dims == 0) element else arrayTypeName(element, dims)
    }

    // ---------------------------------------------------------------- internals

    private fun earliestOf(vararg indices: Int): Int = indices.filter { it != -1 }.minOrNull() ?: -1

    /** Control-flow exception, caught exactly once in [parse]; never escapes. */
    private class RefSyntaxException(
        message: String,
        val position: Int,
    ) : RuntimeException(message)

    private fun fail(message: String, position: Int): Nothing = throw RefSyntaxException(message, position)
}
