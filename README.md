# `jdx` — an IDE for AI agents

> **Status: M0–M6 implemented; M7 polish landing.**
> Read path, index, workspaces, bodies, sources, docs, decompilers, usages,
> hierarchy, call hierarchy, samples, Kotlin signatures, daemon, MCP server,
> HTTP API, `batch`, token budgets (`--brief`/`--max-lines`), AppCDS
> cold-start archive, `bench`, and `help --agent` all work. See
> [`docs/PROGRESS.md`](docs/PROGRESS.md) for the real state and
> [`docs/TASKS.md`](docs/TASKS.md) for what's next.

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
- **`jdx samples`** — real call sites from the classpath as usage examples. Evidence instead
  of recollection.
- **`jdx batch`** — many queries, one process, one index load.
- **Four front-ends over one engine:** one-shot CLI, MCP server, a daemon (5-minute idle
  shutdown), and a local HTTP/JSON API.
- **`jdx help --agent`** — a compact paste-ready block for `CLAUDE.md` / system prompts,
  generated from the same table as `jdx help` so the two cannot drift.

## Command surface

| Command | IDE equivalent |
|---|---|
| `jdx show <type>` | Go to declaration |
| `jdx members <type> --inherited` | Code completion after `.` |
| `jdx signature <member>` | Parameter info (`Ctrl+P`) |
| `jdx doc <symbol>` | Quick documentation (`Ctrl+Q`) |
| `jdx body <member>` | Open the method |
| `jdx source <type>` | Open the file / decompile |
| `jdx outline <type>` | File structure (`Ctrl+F12`) |
| `jdx search <pattern>` | Search everywhere (`Shift Shift`) |
| `jdx resolve <name>` | "What is this symbol?" |
| `jdx ls [package]` / `jdx tree [artifact]` | External library browser |
| `jdx usages <symbol>` | Find usages (`Alt+F7`) |
| `jdx hierarchy <type>` / `jdx implementors <type>` | Type hierarchy (`Ctrl+H`) |
| `jdx callers <member>` / `jdx calls <member>` | Call hierarchy (`Ctrl+Alt+H`) |
| `jdx samples <symbol>` | *(no IDE equivalent)* |
| `jdx ws …` / `jdx cache …` | Project & library configuration |
| `jdx mcp` / `jdx daemon` / `jdx serve` / `jdx batch` | *(no IDE equivalent)* |
| `jdx bench [--iterations N]` | *(no IDE equivalent — timings + §15 targets)* |
| `jdx help [--agent]` | Cheat sheet (paste-ready for agents with `--agent`) |

Full specification with every flag: [`docs/PROPOSAL.md`](docs/PROPOSAL.md)
(Appendix B is the flag reference). `jdx <command> --help` documents each command;
`jdx help --agent` prints the compact agent block.

## Requirements

- **JDK 21+** (developed against JDK 26). The launcher resolves the runtime as
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
  when present and silently degrades to a plain run when it is missing or stale.
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
```

## Front-ends

One engine, four ways to reach it — payloads are byte-identical (`--json` envelope)
across all of them:

- **One-shot CLI** (cold, ~250 ms): every command above. `--no-daemon` forces the
  in-process path.
- **Daemon** (warm, ~20 ms): `jdx daemon start|status|stop|restart`. The CLI forwards
  `--json` queries to the running daemon automatically and degrades to in-process when
  it is down. Idle shutdown after 5 minutes.
- **MCP stdio server**: `jdx mcp [-w name]` exposes each query as a typed `jdx_*` tool
  for agents.
- **HTTP/JSON API**: `jdx serve [-w name] [--port 7070] [--bind 127.0.0.1]`
  (`GET /v1/<command>`, `POST /v1/batch`, `GET /v1/health`, localhost by default).
- **`jdx batch`**: newline-delimited `RpcRequest` lines on stdin, one envelope per line on
  stdout; exit code is the max query exit code.

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

## Kotlin note

Kotlin signatures come from `@Metadata` (suspend, properties, default args, `@JvmName`);
Kotlin member bodies and KDoc come from `.kt` sources through a side-loaded
`kotlin-compiler-embeddable` (never on the compile classpath, never in the fat jar).
When the sidecar at `~/.cache/jdx/kotlin/` is absent, `jdx doctor` warns and
`body`/`source`/`doc` over `.kt`-only roots degrade to decompile/`javap` — they emit
*something* and say what it is, never an error. Run `jdx kotlin install` to fetch
and verify the sidecar set (compiler plus its runtime jars, SHA-1 checked) from
Maven Central; re-run to resume after a failure.

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

## Documentation

| | |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Project rules and entry point for contributors and agents |
| [`docs/PROPOSAL.md`](docs/PROPOSAL.md) | Full design specification |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Why things are the way they are |
| [`docs/TASKS.md`](docs/TASKS.md) | Backlog — pick your next task here |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | Running work log and current state |
| [`docs/TESTING.md`](docs/TESTING.md) | Testing strategy — required reading before writing tests |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Conventions, code style, definition of done |

## Contributing

Contributors here — human and AI — do not share memory. Read
[`CONTRIBUTING.md`](CONTRIBUTING.md) before your first change; it is short and it explains the
documentation discipline that keeps the project resumable by anyone.

## License

Apache-2.0 (see `LICENSE`). Bundled third-party components and their licenses are listed in
`docs/PROPOSAL.md` §19 and will be reproduced in `NOTICE`.
