# CLAUDE.md — `jdx`

> **New here? Read in this order — about 15 minutes, and it will save you a day:**
> 1. **This file** — the rules. Short.
> 2. **`AGENTS.md`** (repo root) — architecture rules, build/test/lint, spec pointers.
> 3. **The module `AGENTS.md`** for the area you will touch (`core/`, `index/`, …) —
>    key files, module rules, distilled gotchas. Read only the one(s) you need.
> 4. **`open-items.md`** — known limitations, caveats, and the phase-2 backlog: the
>    closest thing to a task list. New work starts here or from owner direction.
> 5. **`docs/TESTING.md`** — required before you write a test. **`CONTRIBUTING.md`** —
>    required before you write code.
>
> The full design lives in `docs/PROPOSAL.md`; read it lazily, when a task sends you there.
> (The v1 task board, session logs, and decision/lesson shards were removed post-v1;
> their durable content now lives in the `AGENTS.md` files, full history in git.)
>
> **This is an open-source project built by a rotating cast of humans and AI agents who share
> no memory and no habits.** Nobody has read the whole codebase. That is the constraint the
> conventions here exist to survive — write for a stranger, and leave the documentation true
> enough that the next contributor can start cold.

---

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
member was asked for.

---

## 2. Non-negotiable principles

1. **Minimal correct output.** Output is consumed by a model with a finite context window.
   Truncate intelligently and say how to get more — never silently, never by dumping more.
2. **Never guess a symbol.** If a reference is ambiguous, exit `2` and print every candidate
   as a copy-pasteable canonical ref. Guessing wrong is worse than asking.
3. **Every output line names its symbol canonically**, so the agent's next command is a
   mechanical copy-paste, not a reconstruction.
4. **Provenance always available.** Which jar, which sources jar, bytecode vs source vs
   decompiled, which decompiler. An agent must be able to judge confidence.
5. **Deterministic.** Same inputs → byte-identical output. Sorted. No ANSI unless TTY.
   No timestamps, no absolute paths, no hash values in default output.
6. **Meaningful exit codes** (see §5) so agents can branch without parsing prose.
7. **Fast.** Cold CLI under ~250 ms; warm daemon under ~20 ms. The agent may call this
   dozens of times per task.
8. **Degrade, don't fail.** No sources jar → decompile. Decompiler fails → `javap`.
   Kotlin metadata corrupt → JVM view. Always emit *something*, and say what it is.
9. **Heavily tested, by construction.** `core` is written **test-first**; everything else is
   backed by test families that *generate* their own cases — differential against `javap`,
   property-based, metamorphic, fault-injection, and a soak run over ~2,183 real jars. See
   `docs/TESTING.md`. Hand-written examples alone are not sufficient coverage here.

---

## 3. Locked technical decisions (summary — the rules live in `AGENTS.md`)

| Area | Decision |
|---|---|
| Language / build | **Kotlin + Gradle** (Kotlin JVM, JDK 21 toolchain target, runs on local JDK 26) |
| Bytecode reading | **ASM** |
| Decompiler | **Vineflower bundled** (default); `--engine javap` for raw opcodes |
| Java sources | **JavaParser** |
| Kotlin sources | **kotlin-compiler-embeddable PSI** — loaded lazily in an isolated classloader |
| Kotlin binaries | `@Metadata` via **kotlin-metadata-jvm** → true Kotlin signatures |
| Index | **Persistent on-disk index** (SQLite), content-hash keyed, shared across workspaces |
| Truth model | **Bytecode = skeleton, sources = flesh** (bodies, param names, doc) |
| Usages scope | **All workspace jars + the project's own source dirs** |
| Classpath input | explicit paths/globs/dirs + **named workspaces** + **project auto-discovery** + **Maven coords (auto-download)** + **JDK stdlib via jrt-fs** |
| Output | **Text by default**, `--json` for structured |
| Interfaces | one-shot **CLI**, **MCP** stdio server, **daemon** (5-min idle auto-shutdown), **HTTP/JSON** |
| v1 extras | javadoc/KDoc rendering, type hierarchy + implementors, call hierarchy in/out |
| Testing | **TDD mandatory in `core`** + 9 further families; Pitest mutation gate ≥ 80 % on `core` |
| Deferred | jar API diff, Minecraft mappings/remap, resources & services inspection |
| Repo | local git; public GitHub repo (push only on explicit user go-ahead) |

---

## 4. Repository layout

```
jdx/
├── CLAUDE.md                  ← you are here
├── README.md                  user-facing intro
├── CONTRIBUTING.md            conventions, code style, definition of done
├── AGENTS.md                  root contributor notes (rules, build/test, pointers)
├── <module>/AGENTS.md         per-module notes — read only the one(s) you touch
├── open-items.md              limitations, caveats, phase-2 backlog (the roadmap)
├── docs/
│   ├── PROPOSAL.md            full design document (the spec)
│   └── TESTING.md             testing strategy — read before writing tests
├── gradle/libs.versions.toml  version catalog (single source of dependency versions)
├── core/                      model, symbol refs, resolution, rendering. Pure Kotlin, no IO.
├── index/                     ASM readers, Kotlin metadata, SQLite store, indexer
├── sources/                   sources-jar handling, JavaParser, Kotlin PSI (isolated CL)
├── decompile/                 Vineflower + javap engines
├── cli/                       Clikt commands, text/JSON renderers
├── mcp/                       MCP stdio server
├── server/                    HTTP/JSON API + daemon
└── app/                       fat-jar assembly + `jdx` launcher script
```

**Dependency rule:** `core` depends on nothing project-local. Everything depends on `core`.
`cli`/`mcp`/`server` are thin adapters — **no logic in adapters**; if you're tempted to put
logic in a Clikt command, it belongs in `core` or a service class so all four interfaces
share it.

---

## 5. Exit codes (contract — do not change casually)

| Code | Meaning |
|---|---|
| `0` | Success, results found |
| `1` | Valid query, **no results** (class/member not found) |
| `2` | **Ambiguous** reference — candidates printed |
| `3` | Usage / argument error |
| `4` | No index or workspace resolved for this query |
| `5` | Artifact read error (corrupt jar, unreadable file) |
| `6` | Internal error |

---

## 6. Symbol reference syntax (canonical)

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
`#` and `(` are shell-hostile.

---

## 7. Working agreements (full details in `CONTRIBUTING.md`)

- **Keep `open-items.md` current** — it is the roadmap now that the v1 board is
  closed. Move finished items out, record newly found ones (limitations, deferred
  follow-ups, caveats). A change that alters behaviour or finds a caveat and leaves
  it stale is incomplete.
- **Keep the `AGENTS.md` notes true.** If a change invalidates a module note (new
  invariant, new gotcha, new key file), update that note in the same change. A
  contributor who trusts a stale note pays twice.
- **Ask the owner about genuine ambiguity** rather than picking. A silent wrong guess
  propagates through a codebase nobody fully reads.
- **Do not push to any remote** without explicit owner go-ahead in the current conversation.
- **Work in the cadence: one small change → commit → push → next.** Conventional Commits
  with a module scope: `feat(index): parallel artifact indexer`. If a change can't be
  finished and committed in one sitting, it is too big — **split it first.** An unpushed
  working tree is invisible to every other contributor.
- **Definition of done for a command:** behaviour in `core`/`index`/`sources`/`decompile`,
  thin adapter in `cli`, `--help` text, text renderer, JSON renderer, exit codes, truncation,
  golden tests for both renderers, at least one *generative* test family (`docs/TESTING.md`
  §2), README table row, Appendix B flag entry, `open-items.md` updated if behaviour or
  limitations changed.
- **Report honestly.** Failing tests get pasted, not summarised away. Documented half-finished
  work is useful; half-finished work reported as done is a trap.
- **Write for a stranger.** Explicit over clever, named over inlined, invariants stated in
  comments where types don't enforce them.
- **Batch independent tool calls in a single turn.** Plan the reads (or writes) you need,
  then issue them together in one block instead of one per turn. Every turn costs an API
  round-trip and re-sends context — batching saves both time and cost. Sequence calls only
  when one genuinely depends on another's output.

## 8. Environment notes (this machine)

- Arch Linux. **JDK 26** at `/usr/lib/jvm/default` (`java`, `javac`, `javap`, `jar`, `jdeps`).
  `JAVA_HOME` is **unset** — the launcher must resolve the JDK itself and not assume it.
- **No `mvn`/`gradle` on PATH.** Use the Gradle **wrapper**. Distributions 9.6.1 and 9.7.1
  are already cached in `~/.gradle/wrapper/dists`, so the wrapper works without a download.
- Maven Central and GitHub are reachable. `git` and `gh` are installed.
- Rich local test corpus: ~2183 jars in `~/.gradle/caches`, including `-sources.jar`
  artifacts (gson, msal4j, lwjgl, mojang-logging) and a large obfuscated/remapped
  `minecraft-client.jar` under `~/.gradle/caches/fabric-loom/` — use the latter as the
  scale/perf benchmark, and gson as the everyday correctness fixture.
- ASM and Vineflower already exist in the local Gradle cache.
