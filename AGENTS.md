# AGENTS.md — `jdx` (root)

`jdx` is an IDE for AI agents as a CLI: answers members/bodies/usages/hierarchy/calls
over jars, class dirs, source dirs, Maven coords, and the JDK. **v1 is complete**
(all commands in `jdx help --agent` ship with text + `--json`, exit codes, goldens).

## Layout and dependency rule

`core/` (pure domain, no IO) · `index/` (bytecode, store, service) · `sources/`
(Java/Kotlin source parsing) · `decompile/` (Vineflower + javap) · `cli/` `mcp/`
`server/` (thin adapters — **no behaviour here**) · `app/` (fat jar + launcher) ·
`testfixtures/` (nasty-class corpus). Rule: `core` depends on nothing project-local
and has no third-party runtime deps; everything depends on `core`.

## Locked architecture rules (owner-settled, do not re-litigate)

- **Truth model:** structure from bytecode, flesh (bodies, param names, docs) from
  sources; disagreement warns `SOURCES_VERSION_MISMATCH`, never silently wins.
- **Read-only:** parse with ASM, never load inspected classes (no static init ever
  runs). Write only to `~/.cache/jdx` and `~/.config/jdx`.
- **Output:** text default, `--json` envelope complete (no text-only information).
  Deterministic: sorted, no ANSI unless TTY, no timestamps/absolute paths in output.
- **Never guess:** ambiguous refs exit 2 with copy-pasteable candidates. Exit codes
  `0 ok · 1 not found · 2 ambiguous · 3 usage · 4 no workspace · 5 artifact · 6 internal`
  are a public contract — changing them breaks agents.
- **Graph queries are live-roots-first:** `usages`/`hierarchy`/`callers`/`calls`/
  `samples` re-scan bytecode roots per query (no persistent-index acceleration yet).
- **Network is opt-in per invocation** (`--fetch`); coordinates resolve from
  `~/.gradle/caches` + `~/.m2` first.
- **Push rule:** no `git push` / `gh repo create` without explicit owner go-ahead in
  the current conversation.

## Build, test, lint

- Build only via the Gradle wrapper (`./gradlew`); JDK 21+ required, `JAVA_HOME` unset
  on the dev machine — never assume it. Tiers: `test` (fast loop) · `check`
  (pre-commit, tiers 1–2 + lint) · tagged `soak`/`bench`/mutation on demand.
- `core` is TDD-mandatory with a Pitest mutation gate ≥ 80%; `index`/`sources`/
  `decompile` ≥ 85% line; adapters ungated (covered by parity + goldens).
- Every behaviour needs a *generative* family (property / `javap`-differential /
  metamorphic / fault-injection / corpus), not just examples.
- Root `lint` (runs in every `check`): no trailing whitespace, no tabs, no bare
  `TODO`/`FIXME` without a reference, no `println`/`System.exit`/`printStackTrace` in
  library mains, `allWarningsAsErrors` on main + test compiles. A lone `:lint`
  validation red mid-churn is stale state until proven otherwise — retry clean first.
- `-Pgolden.update=true` is the most dangerous command here: **read every golden diff
  before committing it.** An unread golden update is a deleted test.
- Line counting is POSIX: one trailing `\n` is a terminator, not a line.

## Specs and roadmap

- `docs/PROPOSAL.md` — design spec (§6 ref grammar, §8 output, §14 serving,
  Appendix B flag reference, §19 licenses). Code cites `PROPOSAL.md §X`; keep true.
- `docs/TESTING.md` — testing strategy; read before writing tests.
- `open-items.md` — known limitations + phase-2 backlog (the roadmap).
- README is the user-facing catalogue; every shipped flag must appear in
  `jdx <cmd> --help`, the README table, and Appendix B.
