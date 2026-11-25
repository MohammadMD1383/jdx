# An MCP server for Java and Kotlin bytecode

jdx mcp is a Java MCP server and a Kotlin MCP server over stdio: an MCP server for a JVM jar that returns one member, not a file dump.

`jdx mcp` speaks the Model Context Protocol on stdin and stdout. An MCP client starts it as a child process and talks JSON-RPC. The process waits until that stream closes, then exits. You do not type queries at it. Each query is a typed `jdx_*` tool, so the agent does not quote `#` or `(` for a shell, and the workspace stays in memory for the session.

Install with the [one-line installer](installation.md) (JDK 21 or later). The same server is what [Use with AI agents](ai-agents.md) wires, and what [MCP, daemon, HTTP & batch](serving.md) places next to the daemon and the HTTP API.

Desktop clients that install an MCP Bundle can use the `jdx-mcp-<version>.mcpb` asset from the release instead of a `PATH` entry. The bundle launches `bin/jdx` with argument `mcp` (`bin/jdx.bat` on Windows) and still needs a Java 21+ runtime on `PATH` or in `JAVA_HOME`. See [MCP Bundle](installation.md#mcp-bundle).

## Config for Claude Code, Cursor, and Codex

`jdx setup` writes the entry. These are the commands on the integration pages. `--scope project` writes into the current checkout. `--scope system` writes the user-global file. Setup merges into an existing config and leaves other servers alone. Re-running is a no-op. Restart the agent after a write.

```bash
jdx setup --agent claude-code --scope project
jdx setup --agent claude-code --scope system
jdx setup --agent claude-code --scope project --check
jdx setup --agent claude-code --scope project --remove
```

Claude Code writes `mcpServers.jdx` into `.mcp.json` (project) or `~/.claude.json` (system). The object is the one the [integrations hub](https://mohammadmd1383.github.io/jdx/integrations/) shows for any client. A fresh `jdx setup` writes that object with two-space indentation:

```json
{"mcpServers":{"jdx":{"command":"jdx","args":["mcp"]}}}
```

```console exec
$ jdx setup --agent claude-code --scope project --check   # exit 1
```

`--check` exits 1 when jdx is not wired yet and 0 when it is. Per-agent page: [Claude Code](https://mohammadmd1383.github.io/jdx/integrations/claude-code/).

Cursor writes the same `mcpServers.jdx` object into `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (system):

```bash
jdx setup --agent cursor --scope project
jdx setup --agent cursor --scope system
jdx setup --agent cursor --scope project --check
jdx setup --agent cursor --scope project --remove
```

```json
{"mcpServers":{"jdx":{"command":"jdx","args":["mcp"]}}}
```

Per-agent page: [Cursor](https://mohammadmd1383.github.io/jdx/integrations/cursor/).

Codex CLI stores TOML. `jdx setup` writes the `[mcp_servers.jdx]` table (`command = "jdx"`, `args = ["mcp"]`), the `codex mcp add jdx -- jdx mcp` equivalent, into `.codex/config.toml` (project, trusted checkouts) or `~/.codex/config.toml` (system). `$CODEX_HOME` is honoured for the system file.

```bash
jdx setup --agent codex --scope project
jdx setup --agent codex --scope system
jdx setup --agent codex --scope project --check
jdx setup --agent codex --scope project --remove
```

```toml
[mcp_servers.jdx]
command = "jdx"
args = ["mcp"]
```

Per-agent page: [Codex](https://mohammadmd1383.github.io/jdx/integrations/codex/). Other agents (`opencode`, `copilot`, `antigravity`, `windsurf`, `aider`, `kilo`, `cline`) use the same `jdx setup --agent` command. The full list is [Use with AI agents](ai-agents.md).

Reference: [`setup`](https://mohammadmd1383.github.io/jdx/docs/reference/setup/), [`mcp`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp/).

## Point the server at a jar

`jdx mcp` with no `-w` serves the stored workspace named `default` when that workspace exists. A tool call may name another stored workspace. A call may also pass `jars` (jar paths, class directories, or globs, comma-separated) and `coord` (Maven coordinates, comma-separated). Those merge in front of the workspace, the same way `--jars` and `--coord` do on the CLI. When the call does not name a workspace and the server's workspace does not exist, `jars` or `coord` are enough on their own.

`discover` true, or `jdx mcp --discover`, walks up from the server's working directory for a Gradle or Maven project when no stored workspace is available. jdx never runs Gradle or Maven to do that. A stored workspace that exists wins over discovery. A missing workspace, with no jars, no coord, and no project, exits 4 and names `jdx ws`.

The server never downloads. `coord` resolves from `~/.gradle/caches` and `~/.m2` only. Prime a missing coordinate with `--fetch` on the CLI first, or pass `jars` for a file you already have.

```bash
jdx ws create default --jars path/to/app.jar
jdx mcp
```

`--no-jdk` on `ws create` leaves the JDK out, which you want when the question is about that jar alone. Pass the workspace to the server:

```bash
jdx ws create app --jars path/to/app.jar --no-jdk
jdx mcp -w app
```

The hand-written entry is the same object as above, with the workspace flag [Use with AI agents](ai-agents.md) documents (`"args": ["mcp", "-w", "myproject"]`). For this workspace the args are `mcp`, `-w`, `app`:

```json
{"mcpServers":{"jdx":{"command":"jdx","args":["mcp","-w","app"]}}}
```

Codex, same argv in the table `jdx setup` writes:

```toml
[mcp_servers.jdx]
command = "jdx"
args = ["mcp", "-w", "app"]
```

`jdx setup` itself always writes `args` as `mcp` only. Add `-w` by hand when the jar is not the workspace named `default`.

The server never downloads. Prime a coordinate once with the CLI and serve that workspace, or pass `coord` or `jars` on the call:

```bash
jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
jdx ws create gson --coord com.google.code.gson:gson:2.14.0
jdx mcp -w gson
```

A stored coordinate that is not in `~/.gradle/caches` or `~/.m2` exits 5 on the server. `--fetch` is a CLI flag. See [Classpath & workspaces](classpath.md#named-workspaces).

jdx reads the names stored in the file. v1 does not read mapping files and does not rename classes. For a Minecraft mod, pass the jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars`. `jdx_diff` compares two artifacts that already share one mapping namespace. A Yarn jar against a Mojmap jar, or an intermediary jar against a named jar, looks like nearly every member was removed and re-added. See [Minecraft mods](minecraft-mods.md) and [See what changed between two Minecraft or mod versions](minecraft-version-diff.md). Picking the class, the descriptor, and an injection point is [Find a Mixin target in Minecraft](minecraft-mixin-targets.md).

The same `members` question, run here against Gson, is what `jdx_members` returns:

```console exec max-lines=20
$ jdx members com.google.gson.Gson --declared --kind method --grep toJson --coord com.google.code.gson:gson:2.14.0 --fetch
```

## Tool categories

The server exposes 27 typed tools, one per query. Parameters, enums, and defaults are generated from `jdx mcp` on the [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/) page. Every tool is read-only. Bytecode is parsed with ASM, and inspected classes are never loaded.

| Category | Tools | CLI |
|---|---|---|
| Read a type or member | `jdx_show`, `jdx_outline`, `jdx_members`, `jdx_signature`, `jdx_doc`, `jdx_body`, `jdx_source` | `show`, `outline`, `members`, `signature`, `doc`, `body`, `source` |
| Find a name | `jdx_search`, `jdx_resolve`, `jdx_ls`, `jdx_tree` | `search`, `resolve`, `ls`, `tree` |
| Usages and calls | `jdx_usages`, `jdx_annotated`, `jdx_hierarchy`, `jdx_implementors`, `jdx_callers`, `jdx_calls`, `jdx_samples` | `usages`, `annotated`, `hierarchy`, `implementors`, `callers`, `calls`, `samples` |
| Inside the jar | `jdx_literals`, `jdx_services`, `jdx_modules`, `jdx_manifest`, `jdx_resources` | `literals`, `services`, `modules`, `manifest`, `resources` |
| Compare two artifacts | `jdx_diff` | `diff` |
| The server itself | `jdx_version`, `jdx_health` | `version`; liveness is `jdx_health` |

`jdx_doctor` is registered and always returns error code 6. It would describe the server's machine, not the client's. Run `jdx doctor` in a shell.

Kotlin binaries are read from `@Metadata`. `jdx_members` and `jdx_signature` default to the Kotlin declaration (`view` `kotlin`); `view` `jvm` is the raw JVM projection. Reading `.kt` sources needs a one-time `jdx kotlin install` on the CLI. See [Reading code](reading-code.md#kotlin).

`jdx_diff` is the one tool that does not need a workspace: the two arguments are the artifacts. Coordinates still resolve from the local caches only.

An agent that can run a shell can skip MCP and call the CLI. Paste `jdx help --agent` from the [agent cheat sheet](https://mohammadmd1383.github.io/jdx/docs/reference/cheat-sheet/). The short path for a dependency — `show`, then `outline`, then one `body` — is [Help AI coding agents understand Java dependencies](reduce-agent-tokens-java.md).

## Where next

- [Use with AI agents](ai-agents.md) — setup, the cheat sheet, and exit codes.
- [MCP, daemon, HTTP & batch](serving.md) — the other ways to keep jdx warm.
- [Warm background JVM (5-min idle shutdown)](https://mohammadmd1383.github.io/jdx/docs/reference/daemon/) — `jdx daemon`.
- [Many queries, one process (NDJSON in/out)](https://mohammadmd1383.github.io/jdx/docs/reference/batch/) — `jdx batch`.
- [Project and library configuration](https://mohammadmd1383.github.io/jdx/docs/reference/ws/) — `jdx ws`.
- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, and `jdx ws`.
- [Find which JAR contains a class](find-class-in-jar.md) — `jdx_resolve`, `jdx_search`, `jdx_ls`, and `jdx_tree`.
- [Find a Mixin target in Minecraft](minecraft-mixin-targets.md) — class, type chain, method descriptor, and an injection point.
- [See what changed between two Minecraft or mod versions](minecraft-version-diff.md) — `jdx_diff` on two mapped jars, then one member.
- [A javap alternative](javap-alternative.md) — class-file signatures, callers, and decompilation.
- [Command catalogue](../COMMANDS.md) — every flag.
