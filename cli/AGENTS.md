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
  (workspace store, socket health) so tests never touch the real home; `javap`
  probing covers `$JAVA_HOME/bin` and Windows `.exe`/`.cmd`/`.bat` (`osName`
  seam). `service/UpgradeService` — pure-Java `tar.gz` extraction first
  (external `tar` is the fallback), `.exe`-aware launcher check.
- `parity/AdapterParityTest` — the §9 contract: CLI `--json` == MCP == HTTP bytes
  over all 17 read commands + failure branches + a hostile-property case.

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
