package dev.jdx.site

/**
 * schema.org JSON-LD for search engines and answer engines: what the product is
 * (SoftwareApplication), where a page sits (BreadcrumbList), what a page is (TechArticle),
 * and question/answer pairs (FAQPage). Values come from the same facts the pages render,
 * so the structured data cannot disagree with the visible text.
 */
object StructuredData {
    private const val CONTEXT = "https://schema.org"

    fun website(site: SiteContext): Map<String, Any?> = linkedMapOf(
        "@context" to CONTEXT,
        "@type" to "WebSite",
        "name" to "jdx",
        "url" to site.baseUrl,
        "description" to TAGLINE,
    )

    fun softwareApplication(site: SiteContext): Map<String, Any?> = linkedMapOf<String, Any?>(
        "@context" to CONTEXT,
        "@type" to "SoftwareApplication",
        "name" to "jdx",
        "description" to TAGLINE,
        "url" to site.baseUrl,
        "applicationCategory" to "DeveloperApplication",
        "applicationSubCategory" to "Code navigation for AI coding agents",
        "operatingSystem" to "Linux, macOS, Windows",
        "softwareRequirements" to "Java 21 or later",
        "license" to "https://www.apache.org/licenses/LICENSE-2.0",
        "isAccessibleForFree" to true,
        "offers" to linkedMapOf("@type" to "Offer", "price" to "0", "priceCurrency" to "USD"),
        "codeRepository" to site.repoUrl,
        "downloadUrl" to "${site.repoUrl}/releases/latest",
        "installUrl" to site.absolute("docs/installation/"),
        "softwareHelp" to site.absolute("docs/"),
        "keywords" to KEYWORDS.joinToString(", "),
    ).apply {
        site.latestVersion?.let { put("softwareVersion", it) }
        site.latestReleaseDate?.let { put("dateModified", it) }
    }

    fun techArticle(site: SiteContext, path: String, title: String, description: String, modified: String?): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "@context" to CONTEXT,
            "@type" to "TechArticle",
            "headline" to title,
            "description" to description,
            "url" to site.absolute(path),
            "inLanguage" to "en",
            "isPartOf" to linkedMapOf("@type" to "WebSite", "name" to "jdx", "url" to site.baseUrl),
            "about" to linkedMapOf("@type" to "SoftwareApplication", "name" to "jdx", "url" to site.baseUrl),
        ).apply { modified?.let { put("dateModified", it) } }

    fun breadcrumbs(site: SiteContext, trail: List<Pair<String, String>>): Map<String, Any?> = linkedMapOf(
        "@context" to CONTEXT,
        "@type" to "BreadcrumbList",
        "itemListElement" to trail.mapIndexed { index, (name, path) ->
            linkedMapOf("@type" to "ListItem", "position" to index + 1, "name" to name, "item" to site.absolute(path))
        },
    )

    fun faqPage(questions: List<Pair<String, String>>): Map<String, Any?> = linkedMapOf(
        "@context" to CONTEXT,
        "@type" to "FAQPage",
        "mainEntity" to questions.map { (question, answer) ->
            linkedMapOf(
                "@type" to "Question",
                "name" to question,
                "acceptedAnswer" to linkedMapOf("@type" to "Answer", "text" to answer),
            )
        },
    )

    const val TAGLINE: String =
        "jdx is an IDE for AI agents, as a command line tool: members, method bodies, usages, " +
            "type hierarchies and call graphs for any JVM jar, Maven coordinate or the JDK — one precise answer per command."

    val KEYWORDS: List<String> = listOf(
        "AI coding agent", "MCP server", "Java", "Kotlin", "JVM", "jar inspection", "decompiler",
        "find usages", "call hierarchy", "javap alternative", "Claude Code", "Cursor", "Codex",
    )
}
