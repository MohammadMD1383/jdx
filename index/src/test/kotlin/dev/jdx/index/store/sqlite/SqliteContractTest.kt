package dev.jdx.index.store.sqlite

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Storage-contract tests for [SqliteIndexStore] (T-013, D-013).
 *
 * These two checks inherently need a PRAGMA, so they live in the implementation
 * package next to the code they pin. This is contract verification, not a
 * back door: production callers must still speak only `IndexStore` (see `:index`
 * `ModuleInfo`, rule 2), and everything else in the suite does.
 */
@Tag("tier2")
class SqliteContractTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `the database runs in WAL mode`() {
        val file = tempDir.resolve("wal.db")
        SqliteIndexStore.open(file).use { /* created */ }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode").use { rows ->
                    rows.next()
                    rows.getString(1).lowercase() shouldBe "wal"
                }
            }
        }
    }

    @Test
    fun `a database from a newer jdx refuses to open instead of misreading rows`() {
        val file = tempDir.resolve("future.db")
        SqliteIndexStore.open(file).use { /* stamp CURRENT */ }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version=999")
            }
        }
        val failure = shouldThrow<IndexStoreException> {
            SqliteIndexStore.open(file)
        }
        failure.message shouldContain "999"
        failure.message shouldContain "1"
    }
}
