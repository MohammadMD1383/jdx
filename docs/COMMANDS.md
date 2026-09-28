# `jdx` command reference

The user-facing catalogue. Every shipped flag must appear in `jdx <command> --help`,
here, and in `docs/PROPOSAL.md` Appendix B. (Moved out of `README.md` to keep it a
hook, demo, install, quickstart, and links.)

`jdx <command> --help` documents each command; `jdx help --agent` prints the compact
agent block. Every query command accepts `--json` (same information, structured envelope)
and `--no-daemon` (force in-process even when the daemon is up).

## Read: declaration, members, structure

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx show <type>` | Go to declaration | classpath flags only |
| `jdx members <type>` | Code completion after `.` | `--declared` / `--inherited` (default), `--kind all\|method\|field\|ctor\|property`, `--access public\|protected\|package\|private\|all`, `--static` / `--instance`, `--from <supertype>`, `--grep <regex>`, `--include-synthetic`, `--limit` (50), `--with-doc`, `--brief`, `--max-lines`, `--sort kind\|name\|declaring`, `--view kotlin\|jvm` |
| `jdx outline <type>` | File structure (`Ctrl+F12`) | Same filters as `members --declared` (never inherited): `--kind`, `--access`, `--static` / `--instance`, `--grep`, `--include-synthetic`, `--limit`, `--with-doc`, `--brief`, `--max-lines`, `--sort`, `--view` |
| `jdx signature <member>` | Parameter info (`Ctrl+P`) | `--include-synthetic`, `--view kotlin\|jvm`, `--limit` (50) |
| `jdx doc <symbol>` | Quick documentation (`Ctrl+Q`) | `--inherited` (default) / `--no-inherited`, `--raw`, `--max-lines` (200). Undocumented members fall back to the nearest documenting supertype. |
| `jdx body <member>` | Open the method | `--context N`, `--line-numbers`, `--max-lines` (200), `--engine vineflower\|javap`, `--with-doc`, `--with-signature` |
| `jdx source <type>` | Open the file / decompile | `--lines A:B`, `--around '<type>#<member>'` + `--context N`, `--line-numbers`, `--max-lines` (200), `--engine vineflower\|javap` |

`body`/`source` ladder: paired sources → Vineflower reconstruction → `javap` disassembly →
signatures → not found. `--engine vineflower` forces reconstruction even when sources are
paired; `--engine javap` shows raw bytecode. Decompiled output is always labelled
`⚠ reconstructed` with the engine named.

## Search and browse

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx search <pattern>` | Search everywhere (`Shift Shift`) | `--kind all\|type\|class\|interface\|enum\|record\|annotation\|object\|companion\|method\|field\|package\|module`, `--regex`, `--fuzzy` (Levenshtein retry on miss), `--in <artifact-glob>`, `--package <glob>`, `--limit` (50). Bare words match case-insensitively or as camel humps (`HMap` finds `HashMap`). |
| `jdx resolve <name>` | "What is this symbol?" | `--limit` (50). Several candidates are success (exit 0); none is exit 1 with did-you-mean. |
| `jdx ls [package-glob]` | External library browser (packages/types) | `--limit` (50). A glob lists packages with type counts; an exact package lists its types. |
| `jdx tree [artifact-glob]` | External library browser (artifacts) | `--depth` (8; 0 = top segments), `--counts`, `--limit` (50) |

## Graph: usages, hierarchy, calls, samples

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx usages <symbol>` | Find usages (`Alt+F7`) | `--kind all\|call\|read\|write\|ref\|new\|throw\|annotation` (`impl`/`override` redirect to `hierarchy`/`implementors`; `--context` redirects to `samples`), `--in` / `--exclude <artifact-glob>`, `--limit` (50), `--src <dir>` (repeatable source-dir roots). `new` = constructor call sites; `throw` = methods declaring the type in `throws`; `annotation` = annotated classes/members. Source-dir hits are textual mentions (`ref` only). |
| `jdx hierarchy <type>` | Type hierarchy (`Ctrl+H`) | `--up` / `--down` (default both), `--direct`, `--depth N`, `--in` / `--exclude`, `--limit` (50). Member refs exit 3. |
| `jdx implementors <type>` | Who implements this? | Alias for `hierarchy --down`: `--direct`, `--depth N`, `--in` / `--exclude`, `--limit` (50) |
| `jdx callers <member>` | Call hierarchy in (`Ctrl+Alt+H`) | `--depth N` (default 1), `--in` / `--exclude`, `--limit` (50). Methods + constructors only; type/field refs exit 3. Cycle-safe (`…(cycle)`). Exact name+descriptor matching — not override-aware (see limitations). |
| `jdx calls <member>` | Call hierarchy out | Same as `callers`, plus `--external-only` (hide callees in the method's own artifact — dependency view) |
| `jdx samples <symbol>` | *(no IDE equivalent)* | `--limit` (default 3), `--in` / `--exclude`, `--prefer-sources` (rank snippet-capable callers first). Ranked by exemplariness: non-test before test, non-generated before generated, fuller overloads first. Snippets capped at 15 lines. Type refs match calls to any member; field refs exit 3. |

## Upgrades: API diff

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx diff <old> <new>` | "What did this upgrade break?" | `--visibility public\|all` (default `public`: public + protected declarations; `all` = every declared member, for refactors and mod/mixin work), `--include-synthetic`, `--severity all\|suspicious\|breaking` (cumulative), `--fail-on none\|breaking\|any`, `--limit` (200), `--fetch`, `--repo`, `--json`. Each of the two specs is a jar, class directory, glob, or `group:artifact:version`. Reads bytecode only — no sources, no decompilation, no classpath. Every finding names one rule (`MEMBER_REMOVED`, `RETURN_TYPE_CHANGED`, `KOTLIN_SUSPEND_CHANGED`, …) at `breaking` / `suspicious` / `info`, and every line is a canonical ref. The full rule set is `docs/PROPOSAL.md` §7.7. |

```console
$ jdx diff gson-2.11.0.jar gson-2.14.0.jar --fail-on breaking
api diff gson-2.11.0.jar -> gson-2.14.0.jar (public surface)
info  FIELD_CONSTANT_VALUE_CHANGED  com.google.gson.internal.GsonBuildConfig#VERSION: "2.11.0" -> "2.14.0"
0 breaking · 0 suspicious · 17 informational · 2 types added · 0 types removed
fail-on breaking: no breaking changes (exit 0)
source: gson-2.11.0.jar (bytecode)
source: gson-2.14.0.jar (bytecode)
next: jdx members com.google.gson.internal.GsonBuildConfig
```

`diff` exits **0 whenever the comparison ran**, whatever it found — the answer is in
the report, not in the process status. `--fail-on breaking` (or `any`) is the CI gate
and is the only way a successful diff exits non-zero (see the exit-code table). It needs
no workspace, no `--jars` and no `--no-daemon`, and is answered by the MCP tool
`jdx_diff` and `GET /v1/diff?query=<old>&new=<new>` with identical bytes.

## Environment and maintenance

| Command | Purpose | Key flags / notes |
|---|---|---|
| `jdx version` | Print the version | `--json` for the envelope form |
| `jdx upgrade` | Self-update from GitHub Releases (release installs only) | `--version <tag>` (default latest), `--check` (report only), `--repo OWNER/NAME`, `--json`. Exits 1 on unknown tag, 3 on non-release installs, 5 on download failure |
| `jdx doctor` | Self-diagnosis | One `ok`/`warn`/`fail` row per check: JDK, `jrt:/`, `javap`, JDK sources, cache, config, index DB, Kotlin sidecar, AppCDS archive (present/absent/stale via size + mtime vs the fat jar; missing/stale degrades via `-Xshare:auto`, never fails), daemon (probed live: running vs stale), workspace, agent setup (OpenCode project/system wiring + detected `v1`/`v2` line via `opencode --version`; Claude Code `.mcp.json`/`~/.claude.json` wiring + `claude --version` presence; Kilo Code project/system wiring; Codex CLI `.codex/config.toml`/`~/.codex/config.toml` wiring + `codex --version` presence; GitHub Copilot CLI `.mcp.json`/`~/.copilot/mcp-config.json` wiring + `copilot --version` presence). Exits 6 if any check fails. |
| `jdx setup --agent opencode\|claude-code\|kilo\|codex\|copilot --scope project\|system` | Wire jdx into an agent | OpenCode: writes the `jdx mcp` MCP entries into `opencode.json`/`opencode.jsonc` (project checkout or `~/.config/opencode`): both the v1 entry (`mcp.jdx`) and the v2 entry (`mcp.servers.jdx`, `enabled` → `disabled`) so either OpenCode line picks it up (v1 support is deprecated, slated for removal). Claude Code: writes the `mcpServers.jdx` entry (`{"command": "jdx", "args": ["mcp"]}`, the `claude mcp add jdx -- jdx mcp` equivalent) into `.mcp.json` (project checkout) or `~/.claude.json` (system). Kilo Code (an OpenCode fork sharing the `mcp` map shape): writes the single `mcp.jdx` entry into `kilo.json`/`kilo.jsonc` (project root or `.kilo/` — the `.kilo/` variant wins when both exist — or `~/.config/kilo`). Codex CLI: writes the `[mcp_servers.jdx]` table (`command = "jdx"`, `args = ["mcp"]`, the `codex mcp add jdx -- jdx mcp` equivalent) into `.codex/config.toml` (project checkout, trusted projects) or `~/.codex/config.toml` (system, `$CODEX_HOME` honoured). GitHub Copilot CLI: writes the `mcpServers.jdx` entry (`{"type": "local", "command": "jdx", "args": ["mcp"]}`, the `copilot mcp add jdx -- jdx mcp` equivalent, verified against `GitHub Copilot CLI 1.0.88`) into `.mcp.json` (project checkout — the nearest one walking up, `.github/mcp.json` as fallback — trusted directories) or `~/.copilot/mcp-config.json` (system, `$COPILOT_HOME` honoured). Reports the detected line. Merges, never clobbers; `--check` reports without writing (exit 1 when absent); `--remove` uninstalls cleanly; re-runs are no-ops. Restart the agent after changing config (it loads once at startup). |
| `jdx ws create\|list\|info\|remove\|use\|add` | Project & library configuration | `create <name> --jars … --src … --coord … --repo … [--jdk\|--no-jdk]`; `add <name> <root>`; `use <name> [--clear]` sets the default; `list` / `info <name>` / `remove <name>` |
| `jdx cache info\|gc\|clear` | Index and cache maintenance | `info` (DB location, size, schema, counts); `gc [--dry-run] [--cache-dir …]` (evicts unreferenced + stale artifacts + orphan daemon logs); `clear` (wipes regenerable cache) |
| `jdx kotlin install` | Fetch the Kotlin PSI sidecar | `--repo …` (repeatable), `--force` (re-download). 7 jars, SHA-1 verified, into `<cache-dir>/kotlin/` (`~/.cache/jdx/kotlin/` on Linux; per-OS defaults in `docs/PROPOSAL.md` §17.1); present jars are skipped, re-runs resume. |
| `jdx help [--agent] [--json]` | Cheat sheet | `--agent` prints the paste-ready agent block |
| `jdx bench` | Benchmark the read path | `--iterations N` (default 3, median reported), classpath flags. Fixed `load`/`show`/`members`/`search`/`hierarchy` workload with §15 advisory targets; always exits 0 on success. Falls back to the local `minecraft-client.jar` when no roots are given. |

## Serving: daemon, MCP, HTTP, batch

| Command | Purpose | Notes |
|---|---|---|
| `jdx daemon start\|stop\|status\|restart` | Warm background JVM | Per-workspace unix socket under the OS runtime dir (`$XDG_RUNTIME_DIR/jdx/<hash>-v1.sock` on Linux; macOS `$TMPDIR/jdx-$UID`, Windows `%LOCALAPPDATA%/jdx/run` — full table in `docs/PROPOSAL.md` §17.1; a missing `XDG_RUNTIME_DIR` falls back, never `exit 3`); over-long socket paths exit 3 with the `JDX_RUNTIME_DIR` hint (104 B macOS / 108 B elsewhere); concurrent `start` fails fast on the socket lock; `stop` never kills a reused pid; `--idle 5m` default (0 disables); `start` is idempotent. `status`: uptime, memory, query count. `run` (foreground) is the internal spawn target. |
| `jdx mcp [-w name]` | MCP stdio server | One typed `jdx_*` tool per query (21 tools) with generated schemas; per-call workspace override; answers byte-identical to `--json`. |
| `jdx serve [-w name] [--port 7070] [--bind 127.0.0.1]` | Local HTTP/JSON API | `GET /v1/<command>?query=…&<param>=…`, `POST /v1/batch` (NDJSON), `GET /v1/health`. Localhost by default; blocks until interrupted. Bodies byte-identical to `--json`. |
| `jdx batch` | Many queries, one process | NDJSON `RpcRequest` lines on stdin (`{"command":"members","query":"…","params":{…}}`), one envelope per line on stdout; exit code is the max query exit code. Roots resolved once. `--json` accepted and ignored (output is always envelopes). |

## Classpath flags (every query command)

`--jars <jar|dir|glob>` (repeatable, merged in front of the workspace) ·
`--coord group:artifact:version` (repeatable; local `~/.gradle/caches` + `~/.m2` first) ·
`--repo <url>` (repeatable Maven mirror, tried before Central) ·
`--fetch` (allow downloads incl. `-sources.jar`, checksum-verified into `<cache-dir>/m2/` (`~/.cache/jdx/m2/` on Linux)) ·
`--no-jdk` (exclude the running JDK stdlib, included by default via `jrt:/` + `src.zip`) ·
`-w/--workspace <name>` (also `JDX_WORKSPACE` env or `jdx ws use` default; Gradle/Maven
project roots auto-discover from the working directory) ·
`--src <dir>` (only `usages`, `batch`, `bench`, and `ws create`: source dirs scanned textually).

Global output flags: `--json` (accepted before or after the subcommand, e.g.
`jdx --json show …`), `--no-color` (piped output is always plain),
`--no-daemon` (force in-process). Warm (daemon) text is always plain; `--json` bytes are
identical cold and warm.

## Exit codes and symbol references

| Code | Meaning |
|---|---|
| `0` | Success, results found |
| `1` | Valid query, **no results** |
| `2` | **Ambiguous** reference — candidates printed, retry with one |
| `3` | Usage / argument error |
| `4` | No index or workspace resolved for this query |
| `5` | Artifact read error (corrupt jar, unreadable file) |
| `6` | Internal error |

The one exception is `jdx diff`'s `--fail-on breaking\|any` gate, which exits `1` when it
trips: a successful comparison otherwise always exits `0`, and the verdict is in the
envelope as `result.gate` too.

References are Javadoc style — `com.example.Outer`, `com.example.Outer#method(Type)`,
short `Outer#method` (resolved, or exit 2 with candidates). Always quote the ref:
`#` and `(` are shell-hostile. `::` and `.` are accepted in place of `#`.
Constructors are `<init>`, static initialisers `<clinit>`.

Every `--json` answer uses one envelope:
`{"jdx":1, "ok":…, "command":…, "query":…, "result":…, "truncated":…, "warnings":…, "provenance":…}`.
Warnings carry stable codes (`DUPLICATE_FQN`, `CORRUPT_CLASS`, `SOURCES_VERSION_MISMATCH`, …);
provenance names the artifact, the source (sources / bytecode / decompiled + engine), and
the file:lines.

## Kotlin note

Kotlin signatures come from `@Metadata` (suspend, properties, default args, `@JvmName`);
Kotlin member bodies and KDoc come from `.kt` sources through a side-loaded
`kotlin-compiler-embeddable` (never on the compile classpath, never in the fat jar).
Without the sidecar, `body`/`source`/`doc` over `.kt`-only roots degrade to
decompile/`javap` — they emit *something* and say what it is, never an error.
Run `jdx kotlin install` to fetch the sidecar set (SHA-1 checked) from Maven Central;
`jdx doctor` reports its status. File facades stay JVM-projected and nullability is not
rendered (see limitations).
