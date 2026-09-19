package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative tests for the workspace layer (T-015, TESTING.md §4 — the task's
 * *generating* family alongside the example suites).
 *
 * Whatever the fuzzer invents — quotes, backslashes, `#`, unicode, empty lists —
 * the TOML codec must round-trip, the resolver must merge in order, and name
 * validation must answer without throwing.
 */
class WorkspacePropertyTest {

    private val nastyString: Arb<String> = Arb.string(0..24)

    private val validName: Arb<String> =
        Arb.list(Arb.of(('a'..'z').toList() + ('0'..'9').toList() + listOf('.', '_', '-')), 1..16)
            .map { chars -> "w" + chars.joinToString("") }

    @Test
    fun `toml encode-decode is a fixed point for generated definitions`() = runBlocking<Unit> {
        checkAll(
            1_000,
            validName,
            Arb.list(nastyString, 0..5),
            Arb.boolean(),
            Arb.list(nastyString, 0..3),
            Arb.list(nastyString, 0..3),
        ) { name, jars, includeJdk, coords, repos ->
            val original = WorkspaceDefinition(name, jars, includeJdk, coords, repos)
            val decoded = WorkspaceToml.decode(WorkspaceToml.encode(original), "$name.toml")
            (decoded.isSuccess) shouldBe true
            decoded.getOrThrow() shouldBe original
        }
    }

    @Test
    fun `decode never throws on generated text`() = runBlocking<Unit> {
        checkAll(1_000, nastyString, nastyString) { text, fileName ->
            // Completion without throwing is the assertion; the result may be either side.
            val decoded = WorkspaceToml.decode(text, "$fileName.toml")
            ((decoded.isSuccess || decoded.isFailure)) shouldBe true
        }
    }

    @Test
    fun `resolver merges explicit jars in front of workspace jars`() = runBlocking<Unit> {
        checkAll(
            1_000,
            Arb.list(nastyString, 0..4),
            Arb.list(nastyString, 0..4),
            Arb.boolean(),
            Arb.list(nastyString, 0..3),
            Arb.list(nastyString, 0..3),
            Arb.list(nastyString, 0..3),
            Arb.list(nastyString, 0..3),
        ) { explicit, stored, jdk, explicitCoords, storedCoords, explicitRepos, storedRepos ->
            val lookup = mapOf("ws" to WorkspaceDefinition("ws", stored, jdk, storedCoords, storedRepos))
            val result = WorkspaceResolver.resolve(
                explicitJars = explicit,
                explicitNoJdk = false,
                flagWorkspace = "ws",
                loadWorkspace = { lookup[it] },
                listNames = { listOf("ws") },
                explicitCoords = explicitCoords,
                explicitRepos = explicitRepos,
            )
            (result is WorkspaceResolver.Result.success) shouldBe true
            val resolved = (result as WorkspaceResolver.Result.success).value
            resolved.jarSpecs shouldBe explicit + stored
            resolved.coords shouldBe explicitCoords + storedCoords
            resolved.repos shouldBe explicitRepos + storedRepos
            resolved.includeJdk shouldBe jdk
            resolved.workspaceName shouldBe "ws"
        }
    }

    @Test
    fun `discovered roots merge explicit-first and never shadow a workspace`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(nastyString, 0..4), Arb.list(nastyString, 0..4)) { explicit, discovered ->
            val lookup = mapOf("ws" to WorkspaceDefinition("ws", listOf("ws.jar"), true))
            // No workspace selected: explicit, then discovered.
            val auto = WorkspaceResolver.resolve(
                explicitJars = explicit,
                discoveredJars = discovered,
                discoveredSelection = "auto",
                loadWorkspace = { lookup[it] },
                listNames = { listOf("ws") },
            )
            (auto is WorkspaceResolver.Result.success) shouldBe true
            (auto as WorkspaceResolver.Result.success).value.jarSpecs shouldBe explicit + discovered
            // Workspace selected: discovery ignored entirely.
            val named = WorkspaceResolver.resolve(
                explicitJars = explicit,
                flagWorkspace = "ws",
                discoveredJars = discovered,
                discoveredSelection = "auto",
                loadWorkspace = { lookup[it] },
                listNames = { listOf("ws") },
            )
            (named is WorkspaceResolver.Result.success) shouldBe true
            (named as WorkspaceResolver.Result.success).value.jarSpecs shouldBe explicit + listOf("ws.jar")
        }
    }

    @Test
    fun `name validation never throws on generated strings`() = runBlocking<Unit> {
        checkAll(1_000, nastyString) { raw ->
            // Null (valid) or an error (invalid) — never an exception.
            validateWorkspaceName(raw) == null || (validateWorkspaceName(raw)?.isNotEmpty() == true) shouldBe true
        }
    }
}
