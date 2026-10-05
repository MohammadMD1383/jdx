package dev.jdx.site

/**
 * THE place that decides which repository Markdown becomes which site page, and where it sits
 * in the documentation hierarchy (Getting started -> Guides -> Reference -> Advanced).
 *
 * Adding a doc: put the file under `docs/guide/` (or wherever it belongs) and add one
 * [DocSource] line below. `SiteManifestTest` fails when a Markdown file in the repository is
 * neither listed here nor in [excludedMarkdown], so a new doc cannot silently miss the site.
 *
 * URLs are flat (`docs/<slug>/`) on purpose: moving a page between sections must never
 * change its URL — that would break inbound links and search rankings.
 */
object SiteManifest {
    /** One Markdown file published as one documentation page. */
    data class DocSource(
        val sourcePath: String,
        val path: String,
        /** Short sidebar label; defaults to the doc's H1. */
        val navTitle: String? = null,
        /** Meta description; defaults to the doc's first paragraph. */
        val description: String? = null,
        /** Whether inline `jdx <cmd> --flag` mentions are checked against the real CLI. */
        val lintCommands: Boolean = true,
    )

    data class Section(val title: String, val anchor: String, val summary: String, val docs: List<DocSource>)

    val gettingStarted = Section(
        title = "Getting started",
        anchor = "getting-started",
        summary = "What jdx is, how to install it, your first queries, and wiring it into your AI agent.",
        docs = listOf(
            DocSource("docs/guide/introduction.md", "docs/introduction/", navTitle = "Introduction"),
            DocSource("docs/guide/installation.md", "docs/installation/", navTitle = "Installation"),
            DocSource("docs/guide/quickstart.md", "docs/quickstart/", navTitle = "Quickstart"),
            DocSource("docs/guide/ai-agents.md", "docs/ai-agents/", navTitle = "Use with AI agents"),
        ),
    )

    val guides = Section(
        title = "Guides",
        anchor = "guides",
        summary = "Task-oriented guides: naming symbols, choosing a classpath, reading and navigating code, upgrades, output, and serving.",
        docs = listOf(
            DocSource("docs/guide/symbol-references.md", "docs/symbol-references/", navTitle = "Symbol references"),
            DocSource("docs/guide/classpath.md", "docs/classpath/", navTitle = "Classpath & workspaces"),
            DocSource("docs/guide/reading-code.md", "docs/reading-code/", navTitle = "Reading code"),
            DocSource("docs/guide/navigating-code.md", "docs/navigating-code/", navTitle = "Usages, hierarchy & calls"),
            DocSource("docs/guide/api-diff.md", "docs/api-diff/", navTitle = "API diff for upgrades"),
            DocSource("docs/guide/output.md", "docs/output/", navTitle = "Output, JSON & exit codes"),
            DocSource("docs/guide/serving.md", "docs/serving/", navTitle = "MCP, daemon, HTTP & batch"),
        ),
    )

    /** Reference is mostly generated from the CLI itself; see [ReferencePages]. */
    val reference = Section(
        title = "Reference",
        anchor = "reference",
        summary = "Every command and flag, generated from the jdx binary itself on every build.",
        docs = listOf(
            DocSource(
                "docs/COMMANDS.md",
                "docs/reference/",
                navTitle = "Command catalogue",
                description = "Every jdx command with its IDE equivalent and key flags: read, search, graph, API diff, workspaces, serving.",
            ),
        ),
    )

    val advanced = Section(
        title = "Advanced",
        anchor = "advanced",
        summary = "The full design specification, the testing strategy, and how this agent-built project is run.",
        docs = listOf(
            DocSource(
                "docs/PROPOSAL.md",
                "docs/design/",
                navTitle = "Design specification",
                description = "The full jdx design: symbol grammar, command surface, output design, index, decompilation, Kotlin support, serving, security.",
                lintCommands = false, // the spec also describes deferred, unshipped surface
            ),
            DocSource(
                "docs/TESTING.md",
                "docs/testing/",
                navTitle = "Testing strategy",
                description = "How jdx is tested: TDD core, javap-differential, property-based, metamorphic, fault-injection and corpus soak tests, mutation gates.",
                lintCommands = false,
            ),
            DocSource("docs/guide/built-by-agents.md", "docs/built-by-agents/", navTitle = "Built by agents"),
            DocSource(
                "AGENTS.md",
                "docs/agents-md/",
                navTitle = "AGENTS.md",
                description = "The rules every contributor — human or AI agent — follows in the jdx repository: principles, locked decisions, exit codes, build and test gates.",
                lintCommands = false,
            ),
            DocSource(
                "CONTRIBUTING.md",
                "docs/contributing/",
                navTitle = "Contributing",
                description = "How to contribute to jdx: conventions, code style, the command checklist, testing and documentation discipline.",
                lintCommands = false,
            ),
        ),
    )

    val sections: List<Section> = listOf(gettingStarted, guides, reference, advanced)

    /** Product pages backed by Markdown (the rest of the product pages are generated). */
    val faq = DocSource(
        "docs/FAQ.md",
        "faq/",
        description = "Answers about jdx: what it is, how it differs from javap and grep, safety, supported agents, Kotlin, performance and licensing.",
    )

    val allDocSources: List<DocSource> = sections.flatMap { it.docs } + faq

    /**
     * Markdown deliberately NOT on the site, with the reason. Module notes are contributor
     * internals; the README is quoted section-by-section by the landing page instead.
     */
    val excludedMarkdown: Map<Regex, String> = mapOf(
        Regex("README\\.md") to "quoted by the landing page (LandingPage) section by section",
        Regex("[a-z]+/AGENTS\\.md") to "per-module contributor notes, linked from the repository",
        Regex("\\.github/.*") to "GitHub metadata",
        Regex("site/.*") to "the site generator's own notes",
        Regex("testfixtures/.*") to "test fixtures",
    )
}
