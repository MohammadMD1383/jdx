# AGENTS.md — `jdx`

> **Entry point.** This file is the rules. Then read only what your change needs:
> the module `AGENTS.md` (`core/`, `index/`, …), GitHub Issues (roadmap),
> `docs/TESTING.md` (before writing tests), `CONTRIBUTING.md` (before writing
> code). The full design lives in `docs/PROPOSAL.md` — read it lazily.
> (The v1 task board, session logs, and decision/lesson shards were removed
> post-v1; their durable content is distilled here and in the module notes,
> full history in git.)

## 1. What this project is

`jdx` is **an IDE for AI agents, as a command line tool.**

An agent working on a JVM codebase constantly needs to know things that today cost it many
shell round-trips: what methods a class has, what a method actually does, who calls it, what
implements an interface. Today it does this with `unzip`, `javap`, `grep`, and by reading
1000-line source files into its context window. That is slow, noisy, and burns context.

`jdx` answers those questions directly, from `.jar`, `-sources.jar`, class directories,
source directories, Maven coordinates, and the JDK itself:

```
jdx members com.google.gson.Gson --inherited     # what can I call on this? (IDE ".")
jdx body 'com.google.gson.Gson#toJson(Object)'   # show me ONLY that method body
jdx usages com.google.gson.TypeAdapter           # find usages
jdx hierarchy java.util.Map                      # who implements this?
jdx search '*Http*Client' --kind class           # symbol search
```

**Design north star:** every command answers one precise question with the *smallest correct
output*, and tells the agent exactly what to type next. Never dump a whole file when a
member was asked for. **v1 is complete** (all commands in `jdx help --agent` ship with
text + `--json`, exit codes, goldens).

## 2. Non-negotiable principles

1. **Minimal correct output.** Consumed by a model with a finite context window.
   Truncate intelligently and say how to get more — never silently, never by dumping more.
2. **Never guess a symbol.** Ambiguous input exits `2` with every candidate as a
   copy-pasteable canonical ref. Guessing wrong is worse than asking.
3. **Every output line names its symbol canonically**, so the next command is a
   mechanical copy-paste, not a reconstruction.
4. **Provenance always available.** Which jar, which sources jar, bytecode vs source vs
   decompiled, which decompiler. An agent must be able to judge confidence.
5. **Deterministic.** Same inputs → byte-identical output. Sorted. No ANSI unless TTY.
   No timestamps, no absolute paths, no hash values in default output.
6. **Meaningful exit codes** (see §6) so agents can branch without parsing prose.
7. **Fast.** Cold CLI under ~250 ms; warm daemon under ~20 ms. Called dozens of times
   per task.
8. **Degrade, don't fail.** No sources jar → decompile. Decompiler fails → `javap`.
   Kotlin metadata corrupt → JVM view. Always emit *something*, and say what it is.
9. **Heavily tested, by construction.** `core` test-first; everything else backed by
   *generative* families — differential against `javap`, property-based, metamorphic,
   fault-injection, corpus soak. Hand-written examples alone are not sufficient.

## 3. Locked rules (owner-settled — do not re-litigate; to change one, open an issue,
get an explicit reversal, record it here and in the commit message)

- **Truth model:** structure from bytecode, flesh (bodies, param names, docs) from
  sources; disagreement warns `SOURCES_VERSION_MISMATCH`, never silently wins.
- **Read-only:** parse with ASM, never load inspected classes (no static init ever
  runs). Write only inside the platform dirs (`docs/PROPOSAL.md` §17.1 table).
- **Per-OS dirs (D-044, issue #44):** cache/config/socket/install defaults per OS —
  Linux `~/.cache|~/.config|$XDG_RUNTIME_DIR` (honouring `XDG_*`); macOS
  `~/Library/Caches|~/Library/Application Support|$TMPDIR/jdx-$UID`; Windows
  `%LOCALAPPDATA%/jdx/cache|%APPDATA%/jdx|%LOCALAPPDATA%/jdx/run` (AF_UNIX,
  Win10 17063+ floor); install `~/.local/share/jdx` + `~/.local/bin` (same on macOS),
  `%LOCALAPPDATA%/Programs/jdx` + PATH shim on Windows. Supersedes D-013/D-027; a
  missing `XDG_RUNTIME_DIR` falls back per table, never `exit 3`. Precedence: CLI flag
  > `JDX_*` > `XDG_*` > OS default. Migration: move (never merge) the old location on
  first run, warn if both exist. Full table in `docs/PROPOSAL.md` §17.1.
- **Output:** text default, `--json` envelope complete (no text-only information).
- **Exit codes are a public contract** — changing them breaks agents.
- **Graph queries are live-roots-first:** `usages`/`hierarchy`/`callers`/`calls`/
  `samples` re-scan bytecode roots per query (no persistent-index acceleration yet).
- **Network is opt-in per invocation** (`--fetch`); coordinates resolve from
  `~/.gradle/caches` + `~/.m2` first.
- **Push rule:** no `git push` / `gh repo create` without explicit owner go-ahead in
  the current conversation.

| Area | Rule |
|---|---|
| Language / build | **Kotlin + Gradle** (JDK 21 toolchain target) |
| Bytecode reading | **ASM** |
| Decompiler | **Vineflower bundled** (default); `--engine javap` for raw opcodes |
| Java sources | **JavaParser** |
| Kotlin sources | **kotlin-compiler-embeddable PSI** — side-loaded into an isolated classloader, never a compile dep, never in the fat jar |
| Kotlin binaries | `@Metadata` via **kotlin-metadata-jvm** → true Kotlin signatures |
| Index | **Persistent on-disk index** (SQLite), content-hash keyed, behind an `IndexStore` interface |
| Usages scope | **All workspace jars + the project's own source dirs** |
| Classpath input | explicit paths/globs/dirs + **named workspaces** + **project auto-discovery** (never run Gradle/Maven) + **Maven coords (auto-download opt-in)** + **JDK stdlib via jrt-fs** |
| Interfaces | one-shot **CLI**, **MCP** stdio server, **daemon** (5-min idle auto-shutdown), **HTTP/JSON** — all thin adapters over one `JdxService`, no logic in adapters |
| Testing | **TDD mandatory in `core`** + generative families; Pitest mutation gate ≥ 80 % on `core` |
| Deferred | jar API diff, mappings/remap, resources & services inspection |
| Repo | local git; public GitHub repo ([github.com/MohammadMD1383/jdx](https://github.com/MohammadMD1383/jdx)), Apache-2.0 |

## 4. Repository layout

```
jdx/
├── AGENTS.md                  ← you are here
├── README.md                  user-facing intro (hook, demo, install, quickstart, links)
├── docs/COMMANDS.md           user-facing command catalogue (moved out of README)
├── CONTRIBUTING.md            conventions, code style, definition of done
├── <module>/AGENTS.md         per-module notes — read only the one(s) you touch
├── docs/
│   ├── PROPOSAL.md            full design document (the spec)
│   └── TESTING.md             testing strategy — read before writing tests
├── gradle/libs.versions.toml  version catalog (single source of dependency versions)
├── core/                      model, symbol refs, resolution, rendering. Pure Kotlin, no IO.
├── index/                     ASM readers, Kotlin metadata, SQLite store, indexer, JdxService
├── sources/                   sources-jar handling, JavaParser, Kotlin PSI (isolated CL)
├── decompile/                 Vineflower + javap engines
├── cli/                       Clikt commands, text/JSON renderers
├── mcp/                       MCP stdio server
├── server/                    HTTP/JSON API + daemon
├── app/                       fat-jar assembly + `jdx` launcher script
└── testfixtures/              nasty Java/Kotlin classes → binary jar + -sources.jar
```

**Dependency rule:** `core` depends on nothing project-local. Everything depends on `core`.
`cli`/`mcp`/`server` are ADAPTERS and must contain no behaviour; logic tempted into a
Clikt command belongs in `core` or a service class so all four interfaces share it.

## 5. Symbol reference syntax (canonical)

Javadoc style. Accept generously, print canonically.

```
com.google.gson.Gson                              type
com.google.gson.Gson$TypeAdapterRuntime           nested (also accepts .Inner)
com.google.gson.Gson#toJson                       all overloads
com.google.gson.Gson#toJson(Object)               simple param names OK
com.google.gson.Gson#toJson(java.lang.Object)     fully-qualified params
com.google.gson.Gson#toJson(Ljava/lang/Object;)Ljava/lang/String;   exact JVM descriptor
com.google.gson.Gson#<init>(...)                  constructor
Gson#toJson                                       short form → resolved, or exit 2 w/ candidates
```

`::` and `.` are accepted in place of `#`. **Always quote the ref in shell examples** —
`#` and `(` are shell-hostile. `$` always separates nesting; the final dot-segment is
always the class. `parse(print(ref)) == ref` is a pinned property — don't break
round-tripping.

## 6. Exit codes (contract — do not change casually)

| Code | Meaning |
|---|---|
| `0` | Success, results found |
| `1` | Valid query, **no results** (class/member not found) |
| `2` | **Ambiguous** reference — candidates printed |
| `3` | Usage / argument error |
| `4` | No index or workspace resolved for this query |
| `5` | Artifact read error (corrupt jar, unreadable file) |
| `6` | Internal error |

## 7. Build, test, lint

- Build only via the Gradle wrapper (`./gradlew`); JDK 21+ required. Tiers: `test`
  (fast loop, < 30 s) · `check` (pre-commit: tiers 1–2 + lint, < 3 min) ·
  tagged `soak`/`bench`/mutation on demand.
- `core` TDD-mandatory, mutation gate ≥ 80%; `index`/`sources`/`decompile` ≥ 85% line;
  adapters ungated (parity + goldens instead — gating thin code incentivises padding).
- Every behaviour needs a *generative* family (property / `javap`-differential /
  metamorphic / fault-injection / corpus), not just examples.
- Root `lint` (runs in every `check`): no trailing whitespace, no tabs, no bare
  `TODO`/`FIXME` without a reference, no `println`/`System.exit`/`printStackTrace` in
  library mains, `allWarningsAsErrors` on main + test compiles. A lone `:lint`
  validation red mid-churn is stale state until proven otherwise — retry clean first.
- `-Pgolden.update=true` is the most dangerous command here: **read every golden diff
  before committing it.** An unread golden update is a deleted test.
- Line counting is POSIX: one trailing `\n` is a terminator, not a line.

## 8. Working agreements

- **Keep GitHub Issues current** — they are the roadmap. Move finished items out, record
  newly found ones (limitations, follow-ups, caveats). A change that alters behaviour
  or finds a caveat and leaves them stale is incomplete.
- **Keep the `AGENTS.md` notes true.** If a change invalidates a module note (new
  invariant, new gotcha, new key file), update that note in the same change. A
  contributor who trusts a stale note pays twice.
- **Ask the owner about genuine ambiguity** rather than picking. A silent wrong guess
  propagates through a codebase nobody fully reads.
- **Work in the cadence: one small change → commit → push → next.** Conventional Commits
  with a module scope: `feat(index): parallel artifact indexer`. If a change can't be
  finished and committed in one sitting, it is too big — **split it first.** An unpushed
  working tree is invisible to every other contributor.
- **Definition of done for a command:** behaviour in `core`/`index`/`sources`/`decompile`,
  thin adapter in `cli`, `--help` text, text renderer, JSON renderer, exit codes,
  truncation, golden tests for both renderers, at least one *generative* test family,
  docs/COMMANDS.md table row, Appendix B flag entry, GitHub Issue filed/updated if behaviour or
  limitations changed.
- **Report honestly.** Failing tests get pasted, not summarised away. Documented
  half-finished work is useful; half-finished work reported as done is a trap.
- **Write for a stranger.** Explicit over clever, named over inlined, invariants stated
  in comments where types don't enforce them.
- **Batch independent tool calls in a single turn.** Sequence calls only when one
  genuinely depends on another's output.

## 9. Specs and roadmap

- `docs/PROPOSAL.md` — design spec (§6 ref grammar, §8 output, §14 serving,
  Appendix B flag reference, §19 licenses). Code cites `PROPOSAL.md §X`; keep true.
- `docs/TESTING.md` — testing strategy; read before writing tests.
- GitHub Issues — known limitations + phase-2 backlog (the roadmap).
- docs/COMMANDS.md is the user-facing catalogue; every shipped flag must appear in
  `jdx <cmd> --help`, the docs/COMMANDS.md table, and Appendix B.

## 10. Environment notes (this machine)

- Arch Linux. JDK at `/usr/lib/jvm/default` (`java`, `javac`, `javap`, `jar`, `jdeps`).
  `JAVA_HOME` is **unset** — the launcher must resolve the JDK itself and not assume it.
- **No `mvn`/`gradle` on PATH.** Use the Gradle **wrapper**.
- Maven Central and GitHub are reachable. `git` and `gh` are installed.
- Rich local test corpus: ~2183 jars in `~/.gradle/caches`, including `-sources.jar`
  artifacts (gson, msal4j, lwjgl, mojang-logging) and a large obfuscated/remapped
  `minecraft-client.jar` under `~/.gradle/caches/fabric-loom/` — the scale/perf
  benchmark; gson is the everyday correctness fixture.
- ASM and Vineflower already exist in the local Gradle cache.
