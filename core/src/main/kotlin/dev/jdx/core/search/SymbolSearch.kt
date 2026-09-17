package dev.jdx.core.search

/**
 * Pure name-matching behind `jdx search` (T-017, PROPOSAL.md §7.2).
 *
 * Produced by nothing (pure string work, like the ref parser); consumed by the
 * `index` module's search orchestration, which enumerates candidate names from
 * open roots and calls back here. No IO, no jars — unit-testable in tier 1.
 *
 * Matching modes, in the order `search` applies them (D-031):
 * 1. `--regex`: [matchesRegex] against the binary name and the simple name.
 * 2. Glob (the pattern [isGlobPattern]): [matchesGlob], `*`/`?`/`[...]`.
 *    `{a,b}` alternation is accepted as a glob marker but matches literally —
 *    brace expansion is a documented non-goal (D-031).
 * 3. Plain text: case-insensitive substring ([matchesSubstring]) **or**
 *    IntelliJ-style camel-hump initials ([camelHumpMatches]).
 * 4. `--fuzzy` fallback when modes 1–3 find nothing: [fuzzyMatches]
 *    (Levenshtein ≤ 2 on simple names).
 */
public object SymbolSearch {

    /** A pattern is a glob when it carries `*`, `?`, `[` or `{` (D-031). */
    public fun isGlobPattern(pattern: String): Boolean =
        pattern.any { it == '*' || it == '?' || it == '[' || it == '{' }

    /**
     * Glob match of [glob] against [value], case-sensitive. `*` spans any run
     * (including `.` and `$`), `?` is exactly one character, `[...]` is one
     * character class. Every other character — including `.` — is literal.
     */
    public fun matchesGlob(glob: String, value: String): Boolean =
        globToRegex(glob).matches(value)

    /** Compiles a glob to a full-match regex; callers cache the result per query. */
    public fun globToRegex(glob: String): Regex {
        val body = buildString {
            var index = 0
            while (index < glob.length) {
                when (val char = glob[index]) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '[' -> {
                        val close = glob.indexOf(']', index + 1)
                        if (close == -1) {
                            append("\\[")
                        } else {
                            var inner = glob.substring(index + 1, close)
                            if (inner.isEmpty()) {
                                // An empty class `[]` matches nothing in glob
                                // semantics; read the `[` literally (the `]`
                                // stays literal on its own turn).
                                append("\\[")
                            } else {
                                append('[')
                                // A leading `^` is literal in globs but negation
                                // in regex — escape it so `[^a]` means `{^, a}`.
                                // (This also fixes `[^]`: the escaped caret leaves
                                // a non-empty class, which Java accepts.)
                                if (inner.startsWith("^")) {
                                    append("\\^")
                                    inner = inner.drop(1)
                                }
                                for ((innerIndex, innerChar) in inner.withIndex()) {
                                    // `[`, `\` and `&` are regex-special inside a
                                    // class (`&&` is intersection) but literal in
                                    // globs. `-` is a range only between ascending
                                    // alphanumerics (`a-z`); anywhere else Java
                                    // rejects it (reversed ranges throw) while
                                    // globs mean it literally — so escape it.
                                    // `!` keeps its shared literal meaning.
                                    if (innerChar == '[' || innerChar == '\\' || innerChar == '&') {
                                        append('\\')
                                    } else if (innerChar == '-' && !isRangeDash(inner, innerIndex)) {
                                        append('\\')
                                    }
                                    append(innerChar)
                                }
                                append(']')
                                index = close
                            }
                        }
                    }
                    '\\', '.', '^', '$', '+', '(', ')', '|', '{', '}' -> {
                        append('\\').append(char)
                    }
                    else -> append(char)
                }
                index++
            }
        }
        return Regex(body)
    }

    /**
     * Regex match of [pattern] against [value] (`containsMatchIn`). An invalid
     * pattern is `false`, never a throw — malformed input is an agent-triggerable
     * path, and errors are values here (use [isValidRegex] to report it as exit 3).
     */
    public fun matchesRegex(pattern: String, value: String): Boolean {
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
        return regex.containsMatchIn(value)
    }

    /** True when [pattern] compiles as a regex. */
    public fun isValidRegex(pattern: String): Boolean =
        runCatching { Regex(pattern) }.isSuccess

    /**
     * Case-insensitive substring: the cheapest "just find it" match, tried
     * before camel-hump so `hash` finds `HashMap` without capitals.
     */
    public fun matchesSubstring(pattern: String, value: String): Boolean =
        value.contains(pattern, ignoreCase = true)

    /**
     * IntelliJ-style camel-hump initials (`HMap` → `HashMap`, `gJson` →
     * `getJson`, `NPE` → `NullPointerException`).
     *
     * Each pattern character consumes the next matching value character in
     * order: an uppercase pattern character must land on an uppercase value
     * character (a hump start — skipping the lowercase run before it), while a
     * lowercase or digit pattern character matches the next value character
     * case-insensitively. The value may have any trailing run left over.
     */
    public fun camelHumpMatches(pattern: String, value: String): Boolean {
        if (pattern.isEmpty()) return true
        var valueIndex = 0
        for (patternChar in pattern) {
            if (patternChar.isUpperCase()) {
                // Skip to the next hump start; a lowercase landing is not a hump.
                while (valueIndex < value.length && !value[valueIndex].isUpperCase()) valueIndex++
                if (valueIndex >= value.length || value[valueIndex] != patternChar) return false
                valueIndex++
            } else {
                if (valueIndex >= value.length) return false
                if (!value[valueIndex].equals(patternChar, ignoreCase = true)) {
                    // A lowercase pattern char may also start a later hump region:
                    // resync by scanning forward for it (lets `gJson` skip `et`).
                    var found = -1
                    var scan = valueIndex + 1
                    while (scan < value.length) {
                        if (value[scan].equals(patternChar, ignoreCase = true)) {
                            found = scan
                            break
                        }
                        scan++
                    }
                    if (found == -1) return false
                    valueIndex = found + 1
                } else {
                    valueIndex++
                }
            }
        }
        return true
    }

    /** A `-` inside a glob class is a range only as `alnum-alnum` ascending. */
    private fun isRangeDash(inner: String, dashAt: Int): Boolean {
        if (dashAt <= 0 || dashAt >= inner.length - 1) return false
        val left = inner[dashAt - 1]
        val right = inner[dashAt + 1]
        return left.isLetterOrDigit() && right.isLetterOrDigit() && left <= right
    }

    /** Edit distance between simple names (the `--fuzzy` fallback and did-you-mean). */
    public fun levenshtein(first: String, second: String): Int {
        if (first == second) return 0
        var previous = IntArray(second.length + 1) { it }
        for (i in 1..first.length) {
            val current = IntArray(second.length + 1)
            current[0] = i
            for (j in 1..second.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (first[i - 1] == second[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    /**
     * Fuzzy match of [pattern] against a simple [name]: true within two edits.
     * Compared case-sensitively on purpose — case drift is what substring and
     * hump matching already cover; fuzz covers typos (`JsonParsr`).
     */
    public fun fuzzyMatches(pattern: String, name: String, maxDistance: Int = 2): Boolean =
        levenshtein(pattern, name) <= maxDistance
}
