package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx ws ...` (T-015).
 *
 * No disk, no jars: every subcommand runs against an [InMemoryWorkspaceStore][dev.jdx.index.workspace.InMemoryWorkspaceStore]
 * and the process exit is injected, so the full matrix — including the non-zero exits —
 * runs in tier 1. File-backed persistence is tier 1 too (`WorkspaceStoreTest`, fabricated
 * homes); service reads over real workspace jars are tier 2 (`WorkspaceServiceTest`).
 */
class WsCommandsTest {

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun run(group: WsCommand, argv: List<String>): Pair<String, Int?> {
        // Capture *around* the parse: the WsExit throw must not take the printed
        // text with it (the failure output is what the assertions read).
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val thrown = try {
            group.parse(argv)
            null
        } catch (e: WsExit) {
            e
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8) to thrown?.code
    }

    // -- create ---------------------------------------------------------------

    @Test
    fun `create stores ordered roots and prints the next command`() {
        val (group, store) = testWsGroup()
        val (output, code) = run(group, listOf("create", "mc", "--jars", "a.jar", "--jars", "b/*.jar"))
        code shouldBe null
        output shouldContain "workspace 'mc' created"
        output shouldContain "next: jdx -w mc members <type>"
        store.load("mc")?.jars shouldBe listOf("a.jar", "b/*.jar")
    }

    @Test
    fun `create honours no-jdk`() {
        val (group, store) = testWsGroup()
        run(group, listOf("create", "mc", "--no-jdk"))
        store.load("mc")?.includeJdk shouldBe false
    }

    @Test
    fun `create refuses an existing name with exit 1`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "mc", "--jars", "a.jar"))
        val (output, code) = run(group, listOf("create", "mc", "--jars", "b.jar"))
        code shouldBe 1
        output shouldContain "already exists"
    }

    @Test
    fun `create rejects bad names and contradictory flags with exit 3`() {
        val (group, _) = testWsGroup()
        val (_, badName) = run(group, listOf("create", "../evil"))
        badName shouldBe 3
        val (bothJdk, bothCode) = run(group, listOf("create", "mc", "--jdk", "--no-jdk"))
        bothCode shouldBe 3
        bothJdk shouldContain "mutually exclusive"
    }

    @Test
    fun `create rejects src naming the owning task`() {
        val (group, _) = testWsGroup()
        val (srcOut, srcCode) = run(group, listOf("create", "mc", "--src", "src/main/java"))
        srcCode shouldBe 3
        srcOut shouldContain "T-031"
    }

    @Test
    fun `create stores coord roots and rejects malformed ones`() {
        val (group, store) = testWsGroup()
        val (output, code) = run(group, listOf("create", "mc", "--coord", "com.google.code.gson:gson:2.14.0"))
        code shouldBe null
        output shouldContain "workspace 'mc' created"
        store.load("mc")?.coords shouldBe listOf("com.google.code.gson:gson:2.14.0")
        val (badOut, badCode) = run(group, listOf("create", "bad", "--coord", "not-a-coordinate"))
        badCode shouldBe 3
        badOut shouldContain "group:artifact:version"
    }

    @Test
    fun `info shows stored coords`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "mc", "--coord", "com.google.code.gson:gson:2.14.0"))
        val (output, code) = run(group, listOf("info", "mc"))
        code shouldBe null
        output shouldContain "coords:\n    com.google.code.gson:gson:2.14.0"
    }

    @Test
    fun `create stores repo roots and rejects malformed ones`() {
        val (group, store) = testWsGroup()
        val (output, code) = run(
            group,
            listOf("create", "mc", "--repo", "https://repo.example.com/maven2"),
        )
        code shouldBe null
        output shouldContain "workspace 'mc' created"
        store.load("mc")?.repos shouldBe listOf("https://repo.example.com/maven2")
        val (badOut, badCode) = run(group, listOf("create", "bad", "--repo", "ftp://mirror.example.com/maven2"))
        badCode shouldBe 3
        badOut shouldContain "ftp://mirror.example.com/maven2"
    }

    @Test
    fun `info shows stored repos`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "mc", "--repo", "https://repo.example.com/maven2"))
        val (output, code) = run(group, listOf("info", "mc"))
        code shouldBe null
        output shouldContain "repos:\n    https://repo.example.com/maven2"
    }

    // -- list / info ----------------------------------------------------------

    @Test
    fun `list marks the default and reports the count`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "zeta"))
        run(group, listOf("create", "alpha"))
        run(group, listOf("use", "alpha"))
        val (output, code) = run(group, listOf("list"))
        code shouldBe null
        output shouldContain "workspaces (2)"
        output shouldContain "  alpha (default)"
        // Sorted: alpha before zeta.
        (output.indexOf("alpha") < output.indexOf("zeta")) shouldBe true
    }

    @Test
    fun `list on empty names the create command`() {
        val (group, _) = testWsGroup()
        val (output, code) = run(group, listOf("list"))
        code shouldBe null
        output shouldContain "no workspaces"
        output shouldContain "jdx ws create"
    }

    @Test
    fun `info shows ordered roots and the default marker`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "mc", "--jars", "a.jar", "--jars", "b.jar", "--no-jdk"))
        val (before, _) = run(group, listOf("info", "mc"))
        before shouldContain "  jars:\n    a.jar\n    b.jar"
        before shouldContain "jdk: excluded"
        before shouldContain "default: no"
        run(group, listOf("use", "mc"))
        val (after, _) = run(group, listOf("info", "mc"))
        after shouldContain "default: yes"
    }

    @Test
    fun `info on a missing workspace exits 1 with a hint`() {
        val (group, _) = testWsGroup()
        run(group, listOf("create", "mc"))
        val (output, code) = run(group, listOf("info", "mx"))
        code shouldBe 1
        output shouldContain "no such workspace 'mx'"
        output shouldContain "Did you mean: mc"
    }

    // -- remove / use / add ---------------------------------------------------

    @Test
    fun `remove deletes and clears a pointing default`() {
        val (group, store) = testWsGroup()
        run(group, listOf("create", "mc"))
        run(group, listOf("use", "mc"))
        val (output, code) = run(group, listOf("remove", "mc"))
        code shouldBe null
        output shouldContain "workspace 'mc' removed"
        store.activeName() shouldBe null
        val (_, missing) = run(group, listOf("remove", "mc"))
        missing shouldBe 1
    }

    @Test
    fun `use sets and clears the default`() {
        val (group, store) = testWsGroup()
        run(group, listOf("create", "mc"))
        val (set, setCode) = run(group, listOf("use", "mc"))
        setCode shouldBe null
        set shouldContain "default workspace is now 'mc'"
        store.activeName() shouldBe "mc"
        val (cleared, clearCode) = run(group, listOf("use", "--clear"))
        clearCode shouldBe null
        cleared shouldContain "cleared"
        store.activeName() shouldBe null
    }

    @Test
    fun `use on a missing workspace exits 1 without selecting`() {
        val (group, store) = testWsGroup()
        val (output, code) = run(group, listOf("use", "ghost"))
        code shouldBe 1
        output shouldContain "no such workspace 'ghost'"
        store.activeName() shouldBe null
    }

    @Test
    fun `use without a name and without clear is a usage error`() {
        val (group, _) = testWsGroup()
        val (_, code) = run(group, listOf("use"))
        code shouldBe 3
        val (_, both) = run(group, listOf("use", "mc", "--clear"))
        both shouldBe 3
    }

    @Test
    fun `add appends one root keeping order and dedupes`() {
        val (group, store) = testWsGroup()
        run(group, listOf("create", "mc", "--jars", "a.jar"))
        val (output, code) = run(group, listOf("add", "mc", "b.jar"))
        code shouldBe null
        output shouldContain "now has 2 root(s)"
        store.load("mc")?.jars shouldBe listOf("a.jar", "b.jar")
        val (again, againCode) = run(group, listOf("add", "mc", "a.jar"))
        againCode shouldBe null
        again shouldContain "already present"
        store.load("mc")?.jars shouldBe listOf("a.jar", "b.jar")
    }

    @Test
    fun `add on a missing workspace names create`() {
        val (group, _) = testWsGroup()
        val (output, code) = run(group, listOf("add", "mc", "a.jar"))
        code shouldBe 1
        output shouldContain "jdx ws create mc"
    }

    // -- json parity ----------------------------------------------------------

    @Test
    fun `every ws command carries the same facts in json`() {
        val (group, _) = testWsGroup()
        for (argv in listOf(
            listOf("create", "mc", "--jars", "a.jar"),
            listOf("list"),
            listOf("info", "mc"),
            listOf("add", "mc", "b.jar"),
            listOf("use", "mc"),
        )) {
            val (output, _) = run(group, argv + "--json")
            val parsed = Json.parseToJsonElement(output.trim()).jsonObject
            parsed["jdx"]?.jsonPrimitive?.content shouldBe "1"
            parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        }
        val (removed, _) = run(group, listOf("remove", "mc", "--json"))
        Json.parseToJsonElement(removed.trim()).jsonObject["ok"]?.jsonPrimitive?.content shouldBe "true"
        val (missing, _) = run(group, listOf("info", "ghost", "--json"))
        val parsed = Json.parseToJsonElement(missing.trim()).jsonObject
        parsed["ok"]?.jsonPrimitive?.content shouldBe "false"
        parsed["command"]?.jsonPrimitive?.content shouldBe "ws info"
    }

    @Test
    fun `ws runs through the root cli`() {
        val (ws, _) = testWsGroup()
        val output = captureStdout {
            JdxCli().subcommands(ws).parse(listOf("ws", "list"))
        }
        output shouldContain "no workspaces"
    }
}
