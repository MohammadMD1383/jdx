# AGENTS.md — `cli/`

Clikt commands + text/JSON renderers + `DaemonClient`. **Thin adapter: no behaviour
here** — flags parse, `JdxService` answers, renderers print, exit codes map. (No
`explicitApi()`: 99 Clikt-wiring violations with no Kotlin consumers; the contract
is the CLI surface, pinned by help/parity goldens.)

## Key files

- `JdxCli` — root; `--json`/`-w`/`--no-daemon` accepted before *or* after the
  subcommand (dual position). `commands/ReadCommandSupport` — shared roots
  resolution + budget validation. `render/HelpSheet` — the single `HELP_ROWS` table
  feeding `jdx help` and `jdx help --agent` (they cannot drift).
- `DaemonClient` — forwards `--json` queries to the running daemon, degrades to
  in-process when down; explicit roots, unnamed workspaces, and usage errors stay
  cold; `--no-daemon` forces in-process. Warm text comes server-rendered (always
  plain — the daemon has no TTY); `--json` bytes are untouched by budgets.
- `bench/BenchRunner` + `render/BenchSheet` — fixed workload, median of iterations,
  advisory targets (never gate). `service/DoctorService` — injectable probes
  (workspace store, socket health, AppCDS archive + fat jar paths) so tests never
  touch the real home; `javap` probing covers `$JAVA_HOME/bin` and Windows
  `.exe`/`.cmd`/`.bat` (`osName` seam); the `appcds` row is stat-only
  (size + mtime vs the fat jar, WARN-only, never FAIL). `service/UpgradeService` — pure-Java `tar.gz` extraction first
  (external `tar` is the fallback), `.exe`-aware launcher check.
  `service/SetupService` — agent wiring (`jdx setup --agent opencode|claude-code|cursor|kilo|codex
  --scope project|system`): JSONC-tolerant `mcp.jdx` (v1) + `mcp.servers.jdx`
  (v2, `enabled` → `disabled`) merge into `opencode.json[c]` (project walk-up,
  else `~/.config/opencode`); `mcpServers.jdx` (`{"command": "jdx", "args":
  ["mcp"]}`, the `claude mcp add jdx -- jdx mcp` equivalent) merges into
  `.mcp.json` (project walk-up) or `~/.claude.json` (system); Cursor (same
  `mcpServers.jdx` shape, verified against `cursor-agent 2026.09.26-dd393fe`:
  `agent mcp list` reads `.cursor/mcp.json`/`~/.cursor/mcp.json`) merges into
  `.cursor/mcp.json` (project walk-up) or `~/.cursor/mcp.json` (system); Kilo Code
  (OpenCode fork, same `mcp` map shape) single `mcp.jdx` merge into
  `kilo.json[c]` (project walk-up preferring `.kilo/`, else `~/.config/kilo`;
  fresh files carry no `$schema`); Codex CLI `[mcp_servers.jdx]`
  (`command = "jdx"`, `args = ["mcp"]`, the `codex mcp add jdx -- jdx mcp`
  equivalent, verified against `codex-cli 0.157.1`) merges line-based into
  `.codex/config.toml` (project walk-up) or `$CODEX_HOME/config.toml`
  (else `~/.codex/config.toml`); idempotent no-op re-runs, `--check`/`--remove`;
  `parseOpencodeVersion`/`probeOpencodeVersion` classify the installed OpenCode
  line (`opencode` then legacy `opencode2`; v1 = `1.x`, v2 =
  `0.0.0-next/beta/dev-*` or `2.x`, never a guess);
  `probeClaudeVersion`/`describeClaudeVersion` report `claude --version`
  presence; `probeCursorVersion`/`describeCursorVersion` report
  `cursor-agent --version` presence (`agent` shim fallback);
  `probeCodexVersion`/`describeCodexVersion` report `codex --version`
  presence (`isJdxCommand` accepts Windows shims `jdx.exe`/`.cmd`/`.bat` and
  backslash paths on every host, so `--check`/`doctor` stay true on Windows);
  `doctor` reuses all probes for the setup row.
  Tests pin userHome/projectDir to temp dirs, never the real home
  (Codex tests additionally pin `$CODEX_HOME` via the `codexHome` ctor arg).
- `parity/AdapterParityTest` — the §9 contract: CLI `--json` == MCP == HTTP bytes
  over all 18 read commands + failure branches + a hostile-property case.
- `commands/DiffCommand` is the one query with no `RootsSpec`, no `--jars`/`-w`/`--no-jdk`
  and **no `--no-daemon`**: a diff names its own two artifacts, and a warm daemon holds
  nothing a two-artifact comparison can reuse (nor a way to carry a `--fail-on` verdict
  back over the v1 wire, which only carries an exit code as an error). A flag that does
  nothing is a lie, so the flag is *absent*, not inert: `jdx --no-daemon diff a b`
  parses, `jdx diff --no-daemon a b` does not. The daemon still *serves* `diff` (MCP
  `jdx_diff`, `GET /v1/diff`) — the CLI just does not forward to it.

## Rules

- `--brief`/`--max-lines` are text-only (`members`/`outline`); `--json` bytes are
  identical with or without them. `--brief --with-doc` and `--max-lines < 0` exit 3.
- Batch: NDJSON in, one envelope per line, per-query failures never abort the
  stream, exit = max query exit. Empty batch exits 3, malformed lines exit 6.
- Command tests must inject `InMemoryWorkspaceStore()` — never the real-home store
  (ambient `active-workspace` files silently re-root assertions). Thread throwing
  terminators through group builders in tests, or failure paths kill the worker JVM.

## Gotchas (distilled)

- Fakes must use honest outcome constructors (`notFound`/`ambiguous`, not `generic`
  for 1/2) — total adapters render fake-construction throws as exit-6 lines.
- Assert envelope-field absence via byte-identity with the field-off serialisation,
  never substring (result payloads may name the same fields).
- kotest block matchers can silently drop message lambdas — read the overload list
  before "fixing" an unused-expression warning (`requireNotNull` keeps lazy
  messages + smart-casts).
- `git diff` every edit before building; anchors appear verbatim in both strings.
