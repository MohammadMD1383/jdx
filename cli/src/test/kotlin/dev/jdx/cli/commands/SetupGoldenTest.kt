package dev.jdx.cli.commands

import dev.jdx.cli.service.SetupService
import dev.jdx.testsupport.golden.GoldenFiles
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Golden tests for `jdx setup` (issue #33 family): merged `opencode.json`
 * and `kilo.json` bytes pinned to committed files under
 * `src/test/resources/golden/setup/`.
 *
 * One test pins all six files: [GoldenFiles.verifyAll] fails on orphans, so
 * splitting agents into separate tests would fail each with the other's
 * files as orphans. Every home and project dir is a fresh temp dir — never
 * the real home. Rewrite with
 * `./gradlew :cli:tier2Test -Pgolden.update=true` — then read the diff before
 * committing it. A golden updated without reading is a test deleted.
 */
@Tag("tier2")
class SetupGoldenTest {

    @Test
    fun `merged agent configs are byte-pinned`(@TempDir root: Path) {
        val home = root.resolve("home").also { Files.createDirectories(it) }
        val project = root.resolve("project").also { Files.createDirectories(it) }
        val contents = mutableMapOf<String, String>()

        val fresh = SetupService(home, project)
        val freshOutcome = fresh.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.OPENCODE,
                scope = SetupService.Scope.PROJECT,
            ),
        ) as SetupService.SetupOutcome.Installed
        contents["fresh-install.json"] = Files.readString(freshOutcome.path)

        val mergeDir = root.resolve("merge").also { Files.createDirectories(it) }
        Files.writeString(
            mergeDir.resolve("opencode.json"),
            """{"model":"anthropic/x","mcp":{"other":{"type":"local","command":["other"],"enabled":true},"servers":{"v2other":{"type":"local","command":["v2other"]}}}}""",
        )
        val merge = SetupService(home, mergeDir)
        val mergeOutcome = merge.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.OPENCODE,
                scope = SetupService.Scope.PROJECT,
            ),
        ) as SetupService.SetupOutcome.Installed
        contents["merge-preserves.json"] = Files.readString(mergeOutcome.path)

        val removeOutcome = merge.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.OPENCODE,
                scope = SetupService.Scope.PROJECT,
                remove = true,
            ),
        ) as SetupService.SetupOutcome.Removed
        contents["remove-keeps-others.json"] = Files.readString(removeOutcome.path)

        val kiloProject = root.resolve("kilo-project").also { Files.createDirectories(it) }
        val kiloFresh = SetupService(home, kiloProject)
        val kiloFreshOutcome = kiloFresh.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.KILO,
                scope = SetupService.Scope.PROJECT,
            ),
        ) as SetupService.SetupOutcome.Installed
        contents["fresh-install-kilo.json"] = Files.readString(kiloFreshOutcome.path)

        val kiloMergeDir = root.resolve("kilo-merge").also { Files.createDirectories(it) }
        Files.writeString(
            kiloMergeDir.resolve("kilo.json"),
            """{"model":"kilo","mcp":{"other":{"type":"local","command":["other"],"enabled":true}}}""",
        )
        val kiloMerge = SetupService(home, kiloMergeDir)
        val kiloMergeOutcome = kiloMerge.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.KILO,
                scope = SetupService.Scope.PROJECT,
            ),
        ) as SetupService.SetupOutcome.Installed
        contents["merge-preserves-kilo.json"] = Files.readString(kiloMergeOutcome.path)

        val kiloRemoveOutcome = kiloMerge.run(
            SetupService.SetupRequest(
                agent = SetupService.Agent.KILO,
                scope = SetupService.Scope.PROJECT,
                remove = true,
            ),
        ) as SetupService.SetupOutcome.Removed
        contents["remove-keeps-others-kilo.json"] = Files.readString(kiloRemoveOutcome.path)

        GoldenFiles.verifyAll(File("src/test/resources/golden/setup"), contents)
    }
}
