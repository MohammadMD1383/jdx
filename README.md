# `jdx` — an IDE for AI agents

---

An AI agent working on a JVM codebase spends most of its time — and most of its context
window — answering questions a human answers in an IDE with one keystroke:

*What methods does this class have? What does this method actually do? Who calls it?
What implements this interface?*

Today an agent answers those with `unzip`, `javap`, `grep`, and by reading 1,400-line source
files into its context. It is slow, noisy, and expensive, and `javap` gives it erased
signatures and raw opcodes.

`jdx` answers them directly — from `.jar`, `-sources.jar`, class directories, source
directories, Maven coordinates, and the JDK itself:

```console
$ jdx members java.util.HashMap --limit 5
members of java.util.HashMap
constructors declared on java.util.HashMap:
  constructor public HashMap()
  constructor public HashMap(int initialCapacity)
  ...
5 of 54 members shown (--limit 54 to see more)
source: java.base (jrt)

$ jdx body 'java.util.HashMap#get(java.lang.Object)'
java.util.HashMap#get(java.lang.Object)
  source: decompiled by vineflower from java.base · java/util/HashMap.java:178-182  ⚠ reconstructed
   @Override
   public V get(Object key) {
      Node<K, V> e;
      return (e = this.getNode(key)) == null ? null : e.value;
   }
```

No sources jar? It decompiles with Vineflower — and **says so**, so the agent knows it is
reading a reconstruction (pass `--engine javap` for raw opcodes instead).

## What makes it an *IDE*, not a better `javap`

- **Inherited members resolved transitively**, with generic substitution — so
  `class StringList extends ArrayList<String>` reports `add(String)`, not `add(E)`. The agent
  never walks a class hierarchy by hand again.
- **A real index**, so *find usages*, *implementors*, and *call hierarchy* work across an
  entire classpath, not just one jar.
- **Sources when they exist, decompilation when they don't** — uniformly, behind one command.
- **True Kotlin signatures** from `@Metadata`: `suspend`, properties, default arguments,
  `@JvmName` mappings — not the misleading JVM projection (`--view jvm` forces the
  raw JVM projection when the agent genuinely needs it: Java interop, stack traces).
- **Javadoc/KDoc**, including documentation inherited from a supertype.
- **`jdx samples`** — real call sites from the classpath as usage examples. Evidence instead
  of recollection.

## What makes it *for agents*, not for humans

- **Minimal correct output.** Bounded, with an explicit hint for getting the rest. Never a
  file dump when a method was asked for (`members`/`outline` take `--brief` and
  `--max-lines` for the biggest token burners).
- **Never guesses.** An ambiguous reference exits `2` and lists every candidate as a
  copy-pasteable reference.
- **Machine-legible outcomes.** Distinct exit codes for *not found*, *ambiguous*,
  *no index*, *bad usage* — branch without parsing prose. `--json` for everything.
- **Provenance on every answer** — which jar, sources or bytecode, which decompiler — so an
  agent knows how much to trust what it just read.
- **`jdx batch`** — many queries, one process, one index load.
- **Four front-ends over one engine:** one-shot CLI, MCP server, a daemon (5-minute idle
  shutdown), and a local HTTP/JSON API.
- **`jdx help --agent`** — a compact paste-ready block for `CLAUDE.md` / system prompts,
  generated from the same table as `jdx help` so the two cannot drift.

## Command catalogue

`jdx <command> --help` documents each command; `jdx help --agent` prints the compact
agent block. Every query command accepts `--json` (same information, structured envelope)
and `--no-daemon` (force in-process even when the daemon is up).

### Read: declaration, members, structure

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

### Search and browse

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx search <pattern>` | Search everywhere (`Shift Shift`) | `--kind all\|type\|class\|interface\|enum\|record\|annotation\|object\|companion\|method\|field\|package\|module`, `--regex`, `--fuzzy` (Levenshtein retry on miss), `--in <artifact-glob>`, `--package <glob>`, `--limit` (50). Bare words match case-insensitively or as camel humps (`HMap` finds `HashMap`). |
| `jdx resolve <name>` | "What is this symbol?" | `--limit` (50). Several candidates are success (exit 0); none is exit 1 with did-you-mean. |
| `jdx ls [package-glob]` | External library browser (packages/types) | `--limit` (50). A glob lists packages with type counts; an exact package lists its types. |
| `jdx tree [artifact-glob]` | External library browser (artifacts) | `--depth` (8; 0 = top segments), `--counts`, `--limit` (50) |

### Graph: usages, hierarchy, calls, samples

| Command | IDE equivalent | Key flags |
|---|---|---|
| `jdx usages <symbol>` | Find usages (`Alt+F7`) | `--kind all\|call\|read\|write\|ref\|new\|throw\|annotation` (`impl`/`override` redirect to `hierarchy`/`implementors`; `--context` redirects to `samples`), `--in` / `--exclude <artifact-glob>`, `--limit` (50), `--src <dir>` (repeatable source-dir roots). `new` = constructor call sites; `throw` = methods declaring the type in `throws`; `annotation` = annotated classes/members. Source-dir hits are textual mentions (`ref` only). |
| `jdx hierarchy <type>` | Type hierarchy (`Ctrl+H`) | `--up` / `--down` (default both), `--direct`, `--depth N`, `--in` / `--exclude`, `--limit` (50). Member refs exit 3. |
| `jdx implementors <type>` | Who implements this? | Alias for `hierarchy --down`: `--direct`, `--depth N`, `--in` / `--exclude`, `--limit` (50) |
| `jdx callers <member>` | Call hierarchy in (`Ctrl+Alt+H`) | `--depth N` (default 1), `--in` / `--exclude`, `--limit` (50). Methods + constructors only; type/field refs exit 3. Cycle-safe (`…(cycle)`). Exact name+descriptor matching — not override-aware (see limitations). |
| `jdx calls <member>` | Call hierarchy out | Same as `callers`, plus `--external-only` (hide callees in the method's own artifact — dependency view) |
| `jdx samples <symbol>` | *(no IDE equivalent)* | `--limit` (default 3), `--in` / `--exclude`, `--prefer-sources` (rank snippet-capable callers first). Ranked by exemplariness: non-test before test, non-generated before generated, fuller overloads first. Snippets capped at 15 lines. Type refs match calls to any member; field refs exit 3. |

### Environment and maintenance

| Command | Purpose | Key flags / notes |
|---|---|---|
| `jdx version` | Print the version | `--json` for the envelope form |
| `jdx doctor` | Self-diagnosis | One `ok`/`warn`/`fail` row per check: JDK, `jrt:/`, `javap`, JDK sources, cache, config, index DB, Kotlin sidecar, daemon (probed live: running vs stale), workspace. Exits 6 if any check fails. |
| `jdx ws create\|list\|info\|remove\|use\|add` | Project & library configuration | `create <name> --jars … --src … --coord … --repo … [--jdk\|--no-jdk]`; `add <name> <root>`; `use <name> [--clear]` sets the default; `list` / `info <name>` / `remove <name>` |
| `jdx cache info\|gc\|clear` | Index and cache maintenance | `info` (DB location, size, schema, counts); `gc [--dry-run] [--cache-dir …]` (evicts unreferenced + stale artifacts + orphan daemon logs); `clear` (wipes regenerable cache) |
| `jdx kotlin install` | Fetch the Kotlin PSI sidecar | `--repo …` (repeatable), `--force` (re-download). 7 jars, SHA-1 verified, into `~/.cache/jdx/kotlin/`; present jars are skipped, re-runs resume. |
| `jdx help [--agent] [--json]` | Cheat sheet | `--agent` prints the paste-ready agent block |
| `jdx bench` | Benchmark the read path | `--iterations N` (default 3, median reported), classpath flags. Fixed `load`/`show`/`members`/`search`/`hierarchy` workload with §15 advisory targets; always exits 0 on success. Falls back to the local `minecraft-client.jar` when no roots are given. |

### Serving: daemon, MCP, HTTP, batch

| Command | Purpose | Notes |
|---|---|---|
| `jdx daemon start\|stop\|status\|restart` | Warm background JVM | Per-workspace unix socket at `$XDG_RUNTIME_DIR/jdx/<hash>-v1.sock`; `--idle 5m` default (0 disables); `start` is idempotent. `status`: uptime, memory, query count. `run` (foreground) is the internal spawn target. |
| `jdx mcp [-w name]` | MCP stdio server | One typed `jdx_*` tool per query (20 tools) with generated schemas; per-call workspace override; answers byte-identical to `--json`. |
| `jdx serve [-w name] [--port 7070] [--bind 127.0.0.1]` | Local HTTP/JSON API | `GET /v1/<command>?query=…&<param>=…`, `POST /v1/batch` (NDJSON), `GET /v1/health`. Localhost by default; blocks until interrupted. Bodies byte-identical to `--json`. |
| `jdx batch` | Many queries, one process | NDJSON `RpcRequest` lines on stdin (`{"command":"members","query":"…","params":{…}}`), one envelope per line on stdout; exit code is the max query exit code. Roots resolved once. `--json` accepted and ignored (output is always envelopes). |

### Classpath flags (every query command)

`--jars <jar|dir|glob>` (repeatable, merged in front of the workspace) ·
`--coord group:artifact:version` (repeatable; local `~/.gradle/caches` + `~/.m2` first) ·
`--repo <url>` (repeatable Maven mirror, tried before Central) ·
`--fetch` (allow downloads incl. `-sources.jar`, checksum-verified into `~/.cache/jdx/m2/`) ·
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

## Requirements

- **JDK 21+** (developed against JDK 26/27). The launcher resolves the runtime as
  `JAVA_HOME` → `java` on `PATH` → `/usr/lib/jvm/default`, and fails with one
  human-readable line (exit 6) when none is usable. `JAVA_HOME` is never assumed.
- **No Maven or Gradle installation needed** — the Gradle wrapper is the only build
  entry point. Maven Central must be reachable on first build (dependencies) and for
  opt-in `--fetch` of Maven coordinates.
- Linux is the developed-on platform; the launcher is POSIX `sh`.

## Build and install

```bash
git clone <repo> && cd jdx
./gradlew :app:installDist   # builds app/build/jdx + the fat jar (+ AppCDS jdx.jsa)
./install.sh                 # symlinks app/build/jdx into ~/.local/bin (per-user, never root)
```

- `./install.sh [--force]` refuses to run as root and refuses to overwrite an unrelated
  `jdx` already on `PATH` unless `--force` is given. Re-running it refreshes the link.
- The build also ships an AppCDS archive (`app/build/libs/jdx.jsa`); the launcher uses it
  when present and silently degrades to a plain run when it is missing or stale
  (~2× faster cold start: `--version` ~187 ms → ~90 ms).
- Verify the install: `jdx doctor` reports JDK, `javap`, cache, index, Kotlin sidecar,
  daemon, and workspace status, each `ok`/`warn`/`fail`; `jdx version` prints the version.

## Quickstart

```bash
# No setup: the running JDK is always on the classpath via jrt:/
jdx members java.util.HashMap --inherited --limit 10
jdx body 'com.google.gson.Gson#toJson(Object)' --jars gson.jar

# Name a classpath once, reuse it everywhere (incl. daemon/MCP/HTTP/batch)
jdx ws create fx --jars 'libs/*.jar' --src ./src
jdx -w fx usages 'com.example.Service#run()'

# Maven coordinates resolve from ~/.gradle/caches + ~/.m2 first;
# --fetch downloads from Maven Central (with -sources.jar) when allowed
jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch

# Token budgets for the biggest listings (text only; --json bytes are unaffected)
jdx members java.util.HashMap --inherited --brief --max-lines 20

# Warm path: start once, query at ~20 ms instead of ~250 ms cold
jdx daemon start -w fx
jdx -w fx members 'com.example.Service'   # served warm automatically
jdx daemon status

# One process, many queries
echo '{"command":"show","query":"java.util.Map"}' | jdx batch --jars gson.jar
```

## Performance

Targets are advisory — rows print `ok`/`OVER` but the command always exits 0 on success
(machine variance never gates):

- Cold one-shot CLI: ~250 ms; warm daemon queries: ~20 ms.
- `jdx bench [--iterations N]` runs the fixed `load`/`show`/`members`/`search`/`hierarchy`
  workload (default 3 iterations, median reported) with §15 targets (`load` ≤ 8000 ms,
  cold queries ≤ 250 ms each). `usages` is deliberately excluded — without an indexed
  path it would only document the known gap below.

## Known limitations (by design)

Nothing below is a bug; each is a candidate for future tasks. Full list in
[GitHub Issues](https://github.com/MohammadMD1383/jdx/issues).

- **No persistent index acceleration.** `usages`/`hierarchy`/`callers`/`calls`/`samples`
  re-scan live bytecode roots on every query; slow on giant roots.
- **Call hierarchy is not override-aware** — exact name+descriptor matching only.
- **`--kind throw` reads declarations, not sites** (methods declaring the type in `throws`).
- **No line numbers on bytecode reference edges** (source-dir mentions have file:line).
- **The daemon never fetches** — stored `--repo` mirrors are inert; unresolvable stored
  coordinates fail warm queries with exit 5. `doctor` is refused over the daemon wire;
  `ws`/`cache` are absent from the wire; MCP is workspace-bound (stored workspace plus a
  per-call override, no auto-discovery or explicit `--jars`/`--coord`).
- **Kotlin:** file facades stay JVM-projected; nullability is not rendered; no fallback
  lexer without the sidecar.
- **Warm text is plain** (no ANSI — the daemon has no TTY); piped output is byte-identical
  to cold.

## Out of scope for v1 (phase-2 backlog)

`jdx diff a.jar b.jar` (public-API diff) · mappings/remapping (Tiny/SRG/ProGuard,
obfuscated jars) · resources & metadata inspection (`META-INF/services`, `module-info`,
manifests) · annotation-driven views · `--since` / API-level reporting · `jdx flow`
(dataflow-lite) · multi-release jar variant selection · Scala/Groovy views · publishing
(Homebrew/AUR, GitHub Releases, native image) — tracked as
[GitHub Issues](https://github.com/MohammadMD1383/jdx/issues).

## Fixture corpus

Tests read real bytecode, not mocks: `testfixtures/` compiles deliberately nasty Java and
Kotlin classes (generics, bridges, nesting, records, sealed types, enums, annotations,
`synchronized`/`native`, a `-g:none` class, a static-initialiser probe, and every Kotlin
shape whose JVM projection misleads) into a binary jar plus a `-sources.jar`.

```bash
./gradlew :testfixtures:jar :testfixtures:sourcesJar
```

Every fixture type carries an `@ExpectedMembers` annotation with its `javap -p` truth, so
adding a fixture adds coverage automatically. **To add one:** write the source, copy the
member lines from `javap -p` into the annotation (never from memory), rebuild twice and
check the jars are byte-identical. Full rules: [`docs/TESTING.md`](docs/TESTING.md) §11.1.

Testing bar: `core` is test-first with a Pitest mutation gate (≥ 80 %, currently 91 %);
every behaviour is covered by at least one *generative* family (property-based,
`javap`-differential, metamorphic, fault-injection, corpus soak over ~2,183 real jars).
Tiers 1–2 run in `check`; tier 3 soaks the corpus; tier 4 mutates `core`.

## Repository layout

```
jdx/
├── README.md                  user-facing intro (this file)
├── AGENTS.md                  project rules and entry point for contributors and agents
├── CONTRIBUTING.md            conventions, code style, definition of done
├── install.sh                 per-user symlink installer (~/.local/bin)
├── gradle/libs.versions.toml  single source of dependency versions
├── core/                      model, symbol refs, resolution, rendering. Pure Kotlin, no IO.
├── index/                     ASM readers, Kotlin metadata, SQLite store, indexer
├── sources/                   sources-jar handling, JavaParser, Kotlin PSI (isolated CL)
├── decompile/                 Vineflower + javap engines
├── cli/                       Clikt commands, text/JSON renderers
├── mcp/                       MCP stdio server
├── server/                    HTTP/JSON API + daemon
├── app/                       fat-jar assembly + `jdx` launcher script
└── testfixtures/              nasty Java/Kotlin classes → binary jar + -sources.jar
```

Dependency rule: `core` depends on nothing project-local. Everything depends on `core`.
`cli`/`mcp`/`server` are thin adapters — no logic in adapters.

Key dependencies: ASM (bytecode), JavaParser (Java sources), Vineflower (decompilation),
`kotlin-metadata-jvm` (Kotlin signatures), `kotlin-compiler-embeddable` (side-loaded PSI,
never bundled), SQLite (index store), Clikt (CLI), MCP Kotlin SDK + `com.sun.net.httpserver`
(serving). Kotlin + Gradle, JDK 21 toolchain target.

## Documentation

| | |
|---|---|
| [`AGENTS.md`](AGENTS.md) | Project rules and entry point for contributors and agents |
| [`AGENTS.md`](AGENTS.md) (+ per-module `AGENTS.md`) | Contributor notes: architecture rules, gotchas, build/test |
| [`docs/PROPOSAL.md`](docs/PROPOSAL.md) | Full design specification (Appendix B is the flag reference) |
| [`docs/TESTING.md`](docs/TESTING.md) | Testing strategy — required reading before writing tests |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Conventions, code style, definition of done |
| [GitHub Issues](https://github.com/MohammadMD1383/jdx/issues) | Limitations, caveats, and the phase-2 backlog |

## Contributing

Contributors here — human and AI — do not share memory. Read
[`CONTRIBUTING.md`](CONTRIBUTING.md) before your first change; it is short and it explains the
documentation discipline that keeps the project resumable by anyone.

## License

Apache-2.0 (see `LICENSE`). Bundled third-party components and their licenses are listed in
`docs/PROPOSAL.md` §19 and will be reproduced in `NOTICE`.
