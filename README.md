# `jdx` — an IDE for AI agents

> **Status: M0–M3 implemented, M4 in progress.**
> Core read path, index, workspaces, bodies, sources, docs, decompilers,
> usages and hierarchy work. This README describes the target; see
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
$ jdx members com.google.gson.Gson --inherited --grep json
methods on com.google.gson.Gson
  public <T> T fromJson(String, Class<T>) throws JsonSyntaxException
  public String toJson(Object)
  public String toJson(Object, Type)
  public JsonElement toJsonTree(Object)
+ 9 inherited from java.lang.Object (--from java.lang.Object)
4 of 39 methods shown (filtered by --grep 'json')

$ jdx body 'com.google.gson.Gson#toJson(Object)'
com.google.gson.Gson#toJson(java.lang.Object)
  source: gson-2.14.0-sources.jar · com/google/gson/Gson.java:722-728
  public String toJson(Object src) {
    if (src == null) {
      return toJson(JsonNull.INSTANCE);
    }
    return toJson(src, src.getClass());
  }
```

No sources jar? It decompiles with Vineflower — and **says so**, so the agent knows it is
reading a reconstruction:

```console
$ jdx body 'net.minecraft.world.item.ItemStack#getMaxStackSize()'
net.minecraft.world.item.ItemStack#getMaxStackSize()
  source: decompiled by vineflower from minecraft-client.jar   ⚠ reconstructed
  public int getMaxStackSize() {
    return this.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
  }
```

## What makes it an *IDE*, not a better `javap`

- **Inherited members resolved transitively**, with generic substitution — so
  `class StringList extends ArrayList<String>` reports `add(String)`, not `add(E)`. The agent
  never walks a class hierarchy by hand again.
- **A real index**, so *find usages*, *implementors*, and *call hierarchy* work across an
  entire classpath, not just one jar.
- **Sources when they exist, decompilation when they don't** — uniformly, behind one command.
- **True Kotlin signatures** from `@Metadata`: `suspend`, nullability, properties, extension
  receivers, default arguments — not the misleading JVM projection.
- **Javadoc/KDoc**, including documentation inherited from a supertype.

## What makes it *for agents*, not for humans

- **Minimal correct output.** Bounded, with an explicit hint for getting the rest. Never a
  file dump when a method was asked for.
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

## Command surface (target)

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
| `jdx hierarchy <type>` | Type hierarchy (`Ctrl+H`) |
| `jdx callers` / `jdx calls` | Call hierarchy (`Ctrl+Alt+H`) |
| `jdx samples <symbol>` | *(no IDE equivalent)* |
| `jdx ws …` / `jdx index` / `jdx cache …` | Project & library configuration |
| `jdx mcp` / `jdx daemon` / `jdx serve` / `jdx batch` | *(no IDE equivalent)* |

Full specification with every flag: [`docs/PROPOSAL.md`](docs/PROPOSAL.md).

## Requirements

- **JDK 21+** (developed against JDK 26)
- No Maven or Gradle installation needed — the Gradle wrapper is the only build entry point

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
