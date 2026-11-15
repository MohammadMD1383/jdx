package dev.jdx.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Path-leak matching must not false-positive when the build machine's
 * checkout directory is a prefix of a documented token (repo at `/workspace`
 * vs `…/workspaces/…` or `no index/workspace`).
 */
class SiteBuilderPathTest {

    @Test
    fun `documented workspaces and exit-code phrases are not machine paths`() {
        SiteBuilder.containsMachinePath(
            "<code>&lt;config-dir&gt;/workspaces/&lt;name&gt;.toml</code>",
            "/workspace",
        ) shouldBe false
        SiteBuilder.containsMachinePath(
            "4 no index/workspace · 5 artifact read error",
            "/workspace",
        ) shouldBe false
    }

    @Test
    fun `real absolute paths under the machine root still match`() {
        SiteBuilder.containsMachinePath("ran in /workspace/foo", "/workspace") shouldBe true
        SiteBuilder.containsMachinePath("cwd=/workspace", "/workspace") shouldBe true
        SiteBuilder.containsMachinePath("/workspace", "/workspace") shouldBe true
    }
}
