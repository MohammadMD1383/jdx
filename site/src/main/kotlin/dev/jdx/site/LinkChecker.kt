package dev.jdx.site

/**
 * Verifies every internal `href`/`src` in the generated site: the target file must exist and
 * a `#fragment` must name an element id on the target page. Runs on the final HTML, so it
 * catches broken links from every source — Markdown, templates and generated pages alike.
 */
object LinkChecker {
    private val reference = Regex("""\s(?:href|src)="([^"]*)"""")
    private val id = Regex("""\sid="([^"]*)"""")

    /**
     * [files] maps output paths (`docs/quickstart/index.html`, `favicon.svg`) to their content
     * (null for binary files). Returns one message per broken link; empty means clean.
     */
    fun check(basePath: String, files: Map<String, String?>): List<String> {
        val idsByFile = files.filterValues { it != null }.mapValues { (_, html) ->
            id.findAll(html!!).map { it.groupValues[1] }.toSet()
        }
        val problems = sortedSetOf<String>()
        for ((file, content) in files) {
            if (content == null || !file.endsWith(".html")) continue
            for (match in reference.findAll(content)) {
                val raw = match.groupValues[1].replace("&amp;", "&")
                val target = resolve(basePath, file, raw) ?: continue
                val (targetPath, fragment) = target
                val targetFile = when {
                    targetPath.isEmpty() || targetPath.endsWith("/") -> targetPath + "index.html"
                    else -> targetPath
                }
                if (targetFile !in files) {
                    problems += "$file: link to missing page `$raw`"
                    continue
                }
                if (fragment != null && fragment.isNotEmpty() && fragment !in idsByFile[targetFile].orEmpty()) {
                    problems += "$file: link to missing anchor `$raw`"
                }
            }
        }
        return problems.toList()
    }

    /** Internal links as (site-relative path, fragment); null for external/special links. */
    private fun resolve(basePath: String, fromFile: String, raw: String): Pair<String, String?>? {
        if (raw.isEmpty() || raw.startsWith("mailto:") || raw.startsWith("data:")) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw) || raw.startsWith("//")) return null
        val fragment = raw.substringAfter('#', "").takeIf { '#' in raw }
        val path = raw.substringBefore('#').substringBefore('?')
        return when {
            path.isEmpty() -> fromFile to fragment // same page
            path.startsWith(basePath) -> path.removePrefix(basePath) to fragment
            path.startsWith("/") -> "\u0000outside-base:$path" to fragment
            else -> fromFile.substringBeforeLast('/', "").let { dir -> (if (dir.isEmpty()) path else "$dir/$path") } to fragment
        }
    }
}
