package dev.jdx.sources

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Providers
import com.github.javaparser.ast.CompilationUnit

/**
 * Same-file top-level siblings (`srcmap`, T-074).
 *
 * The T-071 `SourceRoot.findSource` mapping is per outer binary name:
 * `dev.jdx.fixtures.Nesting$Inner` lives in `dev/jdx/fixtures/Nesting.java`.
 * That misses same-file top-level siblings — `Tag`, `Tags` and `Matrix` all
 * live in `Annos.java`, so `dev.jdx.fixtures.Tag` resolved to no source file
 * and `body`/`source`/`doc` degraded instead of slicing.
 *
 * This file is the remainder of T-020 that T-028 split out: every top-level
 * type declared in the same `.java` file maps to that file. The scan stays
 * package-scoped (files in the same directory only), never a full sources
 * walk, so a miss on a large `src.zip` parses dozens of files at most.
 * Structure still comes from bytecode (D-009): this only answers "which file
 * holds the flesh".
 */
public fun findJavaSourcePath(root: SourceRoot, binaryName: String): String? {
    if (binaryName.isBlank()) return null
    val direct: String? = try {
        root.findSource(binaryName)
    } catch (_: Exception) {
        null
    }
    if (direct != null && direct.endsWith(".java")) return direct
    val sibling: String? = try {
        findSiblingJavaSource(root, binaryName)
    } catch (_: Exception) {
        null
    }
    // A sibling `.java` file wins over a `.kt` direct hit: the Kotlin file
    // names a different language's flesh, while the sibling is exact. When no
    // sibling exists the direct hit (`.kt` or null) is preserved so callers
    // keep their `NotJava`/missing-file branches.
    return sibling ?: direct
}

/**
 * Package-scoped sibling scan: the `.java` file in the same directory whose
 * top-level types declare the wanted outer simple name. Returns `null` when
 * the name is hostile, the listing is unreadable, or no sibling declares it.
 * Never throws: every per-file failure (unreadable entry, unparseable text)
 * skips that file.
 */
internal fun findSiblingJavaSource(root: SourceRoot, binaryName: String): String? {
    val outer = binaryName.substringBefore('$')
    if (outer.isEmpty() || outer.isBlank()) return null
    val simpleName = outer.substringAfterLast('.')
    if (simpleName.isEmpty()) return null
    val packageSlash = outer.substringBeforeLast('.', "").replace('.', '/')
    val prefix = if (packageSlash.isEmpty()) "" else "$packageSlash/"
    val candidates: List<String> = try {
        root.sourcePaths()
    } catch (_: Exception) {
        return null
    }.filter { path ->
        path.endsWith(".java") &&
            path.startsWith(prefix) &&
            '/' !in path.removePrefix(prefix)
    }
    for (path in candidates) {
        val text = try {
            root.openSource(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            continue
        }
        val unit = parseLenientCompilationUnit(text) ?: continue
        if (unit.types.any { it.nameAsString == simpleName }) return path
    }
    return null
}

/**
 * Top-level type names of one `.java` text, or `null` when it does not parse.
 * Pure so tier 1 can own it; the sibling scan above is the only caller.
 */
internal fun topLevelTypeNamesOf(text: String): List<String>? =
    parseLenientCompilationUnit(text)?.types?.map { it.nameAsString }

private fun parseLenientCompilationUnit(text: String): CompilationUnit? {
    // Same grammar choice as `parseJavaUnit`: slicing never compiles, so the
    // newest grammar accepts the most sources instead of failing them.
    val configuration = ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    return try {
        val parsed = JavaParser(configuration)
            .parse(com.github.javaparser.ParseStart.COMPILATION_UNIT, Providers.provider(text))
        if (!parsed.isSuccessful || !parsed.result.isPresent) null else parsed.result.get()
    } catch (_: Exception) {
        null
    }
}
