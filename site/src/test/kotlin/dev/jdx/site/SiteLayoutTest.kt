package dev.jdx.site

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The homepage command cards and the doc grids are the pages an agent actually reads.
 * Two layout bugs shipped together: a long usage line painted out of its card (#133)
 * and cards in one row stopped sharing a top edge (#132). Both are CSS contracts —
 * a template that inserts a wrap opportunity, and a stylesheet that lets the grid
 * shrink — so a refactor that puts `height:100%` or an unbreakable token back goes red
 * here instead of on the published site.
 */
class SiteLayoutTest {

    private val css: String = javaClass.getResource("/site/site.css")!!.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `pipe-separated usage lines gain a wrap opportunity without changing the text`() {
        Html.withSoftBreaks("jdx setup --agent opencode|claude-code|cursor --scope project|system") shouldBe
            "jdx setup --agent opencode|<wbr>claude-code|<wbr>cursor --scope project|<wbr>system"
        // The break is markup, not a character of the command, and the rest is escaped.
        Html.withSoftBreaks("a<b|c&d") shouldBe "a&lt;b|<wbr>c&amp;d"
        Html.withSoftBreaks("no bars here") shouldBe "no bars here"
    }

    @Test
    fun `card grids wrap long tokens and rows share a height`() {
        // One column on a narrow viewport, and the item may be narrower than its text.
        css shouldContain "minmax(min(100%,15.5rem),1fr)"
        css shouldContain "minmax(min(100%,16rem),1fr)"
        css shouldContain "minmax(min(100%,17.5rem),1fr)"
        css shouldContain "minmax(min(100%,11rem),1fr)"
        css shouldContain "minmax(min(100%,10rem),1fr)"
        rule(".command-grid > li,.card-grid > li,.agent-list > li,.guide-list > li") shouldContain "min-width:0"
        rule(".command-grid a,.card") shouldContain "min-width:0"
        rule(".command-grid a,.card") shouldContain "overflow-wrap:anywhere"
        // Percentage height on the link made the row height depend on the link that
        // depends on the row, so neighbouring topics stopped lining up.
        rule(".command-grid a,.card") shouldNotContain "height:100%"
        rule(".command-grid code,.card code,.card strong") shouldContain "overflow-wrap:anywhere"
        // The description is a child of the card. overflow-wrap is not inherited (#152).
        rule(".command-grid span,.card span") shouldContain "min-width:0"
        rule(".command-grid span,.card span") shouldContain "overflow-wrap:anywhere"
        rule(".card time") shouldContain "min-width:0"
        rule(".card time") shouldContain "overflow-wrap:anywhere"
        rule("h1,h2,h3,h4") shouldContain "overflow-wrap:anywhere"
        rule(".hero h1,.page-hero h1") shouldContain "overflow-wrap:anywhere"
        rule(".prose p,.prose li,.prose h1,.prose h2,.prose h3,.prose h4") shouldContain "overflow-wrap:anywhere"
        rule(".prose :not(pre)>code") shouldContain "overflow-wrap:anywhere"
        rule(".callout") shouldContain "overflow-wrap:anywhere"
        rule(".page-meta") shouldContain "overflow-wrap:anywhere"
        rule(".guide-list a") shouldContain "overflow-wrap:anywhere"
        rule(".prose") shouldContain "overflow-wrap:anywhere"
        rule(".prose") shouldContain "min-width:0"
        rule(".code") shouldContain "max-width:100%"
        rule(".code pre") shouldContain "max-width:100%"
        rule(".docs-main") shouldContain "min-width:0"
    }

    /** The declaration block of the rule whose selector is exactly [selector]. */
    private fun rule(selector: String): String {
        // Anchor on a line or rule boundary so `.code` does not match `.terminal .code`.
        val match = Regex("""(?:^|[}\n])${Regex.escape(selector)}\{""").find(css)
            ?: error("missing rule: $selector")
        val start = match.range.last + 1
        val end = css.indexOf('}', start)
        return css.substring(start, end)
    }
}
