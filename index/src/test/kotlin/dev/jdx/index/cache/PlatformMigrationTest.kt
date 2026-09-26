package dev.jdx.index.cache

import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

@Tag("tier2")
class PlatformMigrationTest {

    @Test
    fun `absent legacy migrates nothing`() {
        val base = Files.createTempDirectory("jdx-migrate-test")
        val legacy = base.resolve("old")
        val resolved = base.resolve("new")
        PlatformMigration.migrateOnce(legacy, resolved, "cache").shouldBeNull()
    }

    @Test
    fun `legacy moves when resolved is absent`() {
        val base = Files.createTempDirectory("jdx-migrate-test")
        val legacy = base.resolve("old").also { Files.createDirectories(it) }
        Files.writeString(legacy.resolve("probe.txt"), "x")
        val resolved = base.resolve("new")
        val note = PlatformMigration.migrateOnce(legacy, resolved, "cache")
        note.shouldNotBeNull()
        Files.isDirectory(resolved) shouldBe true
        Files.exists(legacy) shouldBe false
    }

    @Test
    fun `both existing warns and keeps the new location`() {
        val base = Files.createTempDirectory("jdx-migrate-test")
        val legacy = base.resolve("old").also { Files.createDirectories(it) }
        val resolved = base.resolve("new").also { Files.createDirectories(it) }
        Files.writeString(resolved.resolve("existing.txt"), "keep")
        val note = PlatformMigration.migrateOnce(legacy, resolved, "cache")
        note.shouldNotBeNull()
        Files.isDirectory(legacy) shouldBe true
        Files.isDirectory(resolved) shouldBe true
    }

    @Test
    fun `same path never migrates`() {
        val base = Files.createTempDirectory("jdx-migrate-test")
        PlatformMigration.migrateOnce(base, base, "cache").shouldBeNull()
    }
}
