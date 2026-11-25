package dev.jdx.site

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The catalogue and Related sections are what give crawlers a descriptive link to
 * command pages that are in the sitemap but not yet discovered. These checks do not
 * need the binary; the site build checks the groups against `jdx --help`.
 */
class ReferenceLinksTest {
    @Test
    fun `every command group entry is unique`() {
        ReferencePages.COMMAND_GROUPS.flatMap { it.second }.shouldBeUnique()
    }

    @Test
    fun `the undiscovered commands are grouped and have a short related list`() {
        val commands = listOf("batch", "bench", "daemon", "help", "services", "upgrade", "version", "ws")
        val grouped = ReferencePages.COMMAND_GROUPS.flatMap { it.second }
        grouped.shouldContainAll(commands)
        ReferencePages.RELATED.keys shouldBe commands.toSet()
        for (name in commands) {
            val links = ReferencePages.RELATED.getValue(name)
            check(links.size in 2..5) { "$name has ${links.size} related links; keep 2–5" }
            check(links.any { it is ReferencePages.RelatedTarget.Page }) {
                "$name should link the guide where the command is used"
            }
            check(links.any { it is ReferencePages.RelatedTarget.Command }) {
                "$name should link a related command"
            }
        }
    }

    @Test
    fun `crawl targets are the docs paths Search Console listed`() {
        SiteBuilder.INDEXABLE_PATHS.shouldContainAll(
            listOf(
                "docs/reference/batch/",
                "docs/reference/bench/",
                "docs/reference/daemon/",
                "docs/reference/help/",
                "docs/reference/services/",
                "docs/reference/upgrade/",
                "docs/reference/version/",
                "docs/reference/ws/",
                "docs/agents-md/",
                "docs/contributing/",
                "docs/design/",
                "docs/testing/",
                "docs/kotlin-jar-api/",
            ),
        )
    }
}
