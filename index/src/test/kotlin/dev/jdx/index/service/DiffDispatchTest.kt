package dev.jdx.index.service

import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.render.DEFAULT_DIFF_LIMIT
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService.ArtifactSpec
import dev.jdx.index.service.JdxService.DiffOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `diff` over the daemon wire (issue #23, tier 2): the query is the old artifact
 * and the `new` param the new one, and the answer must be byte-identical to the
 * in-process service call with the equivalent options (D-056 §1 — the T-046
 * parity proof stays structural).
 *
 * Also pins the one place a diff could become unreachable: the three daemon
 * adapters resolve a classpath *before* dispatching, which would otherwise exit 4
 * for a command that names its own two artifacts.
 */
@Tag("tier2")
class DiffDispatchTest {

    private fun greeter(vararg methods: String): List<DiffType> = listOf(
        DiffType("d.Greeter", methods.map { DiffMember(it, "()V") }),
    )

    /** The old side has an extra method, so every answer below has something to say. */
    private fun pair(temp: Path): Pair<Path, Path> = Pair(
        diffJar(temp.resolve("v1.jar"), greeter("greet", "legacy")),
        diffJar(temp.resolve("v2.jar"), greeter("greet")),
    )

    private fun dispatchJson(old: String, vararg params: Pair<String, String>): String =
        JdxService.dispatch(RpcRequest(RpcCommand.DIFF, old, params.toMap()), RootsSpec())
            .toJson("diff")

    /** A coordinate no machine resolves, so the resolver's own message is the answer. */
    private val absentCoordinate: String = "jdx.diff.absent:demo:9c1f-jdx-diff"

    // -- parity with the in-process service -------------------------------------------

    @Test
    fun `diff over the wire is byte-identical to the service call with default options`(
        @TempDir temp: Path,
    ) {
        val (old, new) = pair(temp)

        val wire = dispatchJson(old.toString(), "new" to new.toString())
        val direct = JdxService.diff(ArtifactSpec(old.toString()), ArtifactSpec(new.toString()))

        wire shouldBe direct.toJson("diff")
        wire shouldContain "\"command\":\"diff\""
    }

    @Test
    fun `every option reaches the report through the wire`(@TempDir temp: Path) {
        val (old, new) = pair(temp)
        val cases = listOf(
            (mapOf("visibility" to "all") to DiffOptions(visibility = ApiSurface.ALL)),
            (mapOf("includeSynthetic" to "true") to DiffOptions(includeSynthetic = true)),
            (mapOf("severity" to "breaking") to DiffOptions(severityFilter = SeverityFilter.BREAKING)),
            (mapOf("failOn" to "breaking") to DiffOptions(failOn = FailOn.BREAKING)),
            (mapOf("limit" to "1") to DiffOptions(maxFindings = 1)),
        )
        for ((params, options) in cases) {
            val request = RpcRequest(RpcCommand.DIFF, old.toString(), params + ("new" to new.toString()))
            JdxService.dispatch(request, RootsSpec()).toJson("diff") shouldBe
                JdxService.diff(
                    ArtifactSpec(old.toString()),
                    ArtifactSpec(new.toString()),
                    options,
                ).toJson("diff")
        }
    }

    @Test
    fun `a gate carried over the wire still decides the exit code`(@TempDir temp: Path) {
        val (old, new) = pair(temp)

        val outcome = JdxService.dispatch(
            RpcRequest(
                RpcCommand.DIFF,
                old.toString(),
                mapOf("new" to new.toString(), "failOn" to "breaking"),
            ),
            RootsSpec(),
        )

        outcome.exitCode shouldBe 1
    }

    @Test
    fun `an absent limit is the default, not a guess`(@TempDir temp: Path) {
        val (old, new) = pair(temp)
        val request = RpcRequest(RpcCommand.DIFF, old.toString(), mapOf("new" to new.toString()))

        JdxService.dispatch(request, RootsSpec()).toJson("diff") shouldBe
            JdxService.diff(
                ArtifactSpec(old.toString()),
                ArtifactSpec(new.toString()),
                DiffOptions(maxFindings = DEFAULT_DIFF_LIMIT),
            ).toJson("diff")
    }

    // -- param errors: exit 3 naming the param -------------------------------------------

    @Test
    fun `the new artifact is required`(@TempDir temp: Path) {
        val (old, _) = pair(temp)

        val line = dispatchJson(old.toString())

        line shouldContain "\"code\":3"
        line shouldContain "--new is required for diff"
    }

    @Test
    fun `an unknown enum value is a usage error naming the param`(@TempDir temp: Path) {
        val (old, new) = pair(temp)
        // Wire name -> the CLI flag the message spells, because that is what the
        // caller typed and what it must retype.
        val flags = mapOf("visibility" to "--visibility", "severity" to "--severity", "failOn" to "--fail-on")

        for ((param, flag) in flags) {
            val line = dispatchJson(old.toString(), "new" to new.toString(), param to "bogus")
            line shouldContain "\"code\":3"
            line shouldContain flag
            line shouldContain "bogus"
        }
    }

    @Test
    fun `an unparseable or negative limit is a usage error`(@TempDir temp: Path) {
        val (old, new) = pair(temp)

        dispatchJson(old.toString(), "new" to new.toString(), "limit" to "many") shouldContain "--limit"
        // A negative limit bypasses no parsing rule: the service owns the range check.
        dispatchJson(old.toString(), "new" to new.toString(), "limit" to "-1") shouldContain
            "--limit must be >= 0"
    }

    // -- the coordinate side of the wire ---------------------------------------------------

    @Test
    fun `repos reach the coordinate resolver in order with central last`(@TempDir temp: Path) {
        val (_, new) = pair(temp)

        val line = dispatchJson(
            absentCoordinate,
            "new" to new.toString(),
            "repo" to " https://mirror.example/repo/ , https://other.example/m2 ",
        )

        line shouldContain "https://mirror.example/repo/"
        line shouldContain "https://other.example/m2"
        // Central stays the last resort (T-069), so a mirror wins without losing
        // the default.
        line shouldContain "https://repo.maven.apache.org/maven2/"
    }

    @Test
    fun `a coordinate side is an artifact read error without fetch`(@TempDir temp: Path) {
        val (_, new) = pair(temp)

        val line = dispatchJson(absentCoordinate, "new" to new.toString(), "fetch" to "false")

        line shouldContain "\"code\":5"
        line shouldContain "is not in the local caches"
    }

    // -- the classpath the adapters resolve before dispatching -------------------------

    @Test
    fun `daemonRoots short-circuits for diff so no workspace is needed`() {
        val store = InMemoryWorkspaceStore()

        // A diff names its own two artifacts, so classpath resolution is a no-op
        // for it — without this the adapters would exit 4 before dispatching.
        when (val resolved = JdxService.daemonRoots("no-such-workspace", "a.jar", store, RpcCommand.DIFF)) {
            is DaemonRoots.Ready -> resolved.roots shouldBe RootsSpec()
            is DaemonRoots.Failed -> throw AssertionError("expected Ready, got $resolved")
        }
    }

    @Test
    fun `daemonRoots short-circuits even when a workspace does exist`() {
        val store = InMemoryWorkspaceStore()
        store.save(WorkspaceDefinition(name = "fx", jars = listOf("anything.jar"), includeJdk = true))

        when (val resolved = JdxService.daemonRoots("fx", "a.jar", store, RpcCommand.DIFF)) {
            is DaemonRoots.Ready -> resolved.roots shouldBe RootsSpec()
            is DaemonRoots.Failed -> throw AssertionError("expected Ready, got $resolved")
        }
    }

    @Test
    fun `daemonRoots without a command is unchanged`() {
        val store = InMemoryWorkspaceStore()

        // The default is today's behaviour, exactly: a missing workspace still
        // exits 4, and a named one still resolves to its own jars.
        val missing = JdxService.daemonRoots("no-such-workspace", "q", store)
        (missing is DaemonRoots.Failed) shouldBe true
        (missing as DaemonRoots.Failed).outcome.toJson("show") shouldContain "\"code\":4"

        store.save(WorkspaceDefinition(name = "fx", jars = listOf("anything.jar"), includeJdk = false))
        when (val resolved = JdxService.daemonRoots("fx", "q", store)) {
            is DaemonRoots.Ready -> resolved.roots.jarSpecs shouldBe listOf("anything.jar")
            is DaemonRoots.Failed -> throw AssertionError("expected Ready, got $resolved")
        }
    }
}
