# Read and decompile a JAR from the terminal with jdx

Decompile a jar from the command line: list its classes, read one method, and view source. jdx is a javap alternative that returns only what you asked for.

Pass a `.jar` on disk, a Maven coordinate, or a JDK class. jdx needs Java 21 or later, runs on Linux, macOS, and Windows, and is Apache-2.0. Text is the default, and every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate; jdx does not guess. Install it with the [one-line installer](installation.md), then try the [quickstart](quickstart.md). Every command output on this page was produced by running that command when the page was built.

A Minecraft mod jar is the same workflow, pointed at the mapped jar your build already wrote. See [Minecraft mods](minecraft-mods.md).

## List what is in a jar

`jdx tree` prints the package tree of one artifact. `jdx ls` lists the types in one package. `jdx members` lists what you can call on one class.

A file you already have is `--jars` (a jar, a class directory, or a glob). Quote globs so the shell does not expand them; an unquoted expansion exits 3. `--no-jdk` leaves the JDK out when the question is about that jar alone.

```bash
jdx tree 'app*' --depth 2 --counts --jars path/to/app.jar --no-jdk
jdx ls com.example --jars path/to/app.jar --no-jdk
jdx members com.example.App --declared --kind method --limit 20 --jars path/to/app.jar --no-jdk
```

Run here against Gson 2.14.0. `members` is narrowed with `--grep toJson`; the next section opens one of those overloads. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag.

```console exec max-lines=16
$ jdx tree 'gson*' --depth 2 --counts --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx ls com.google.gson.stream --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx members com.google.gson.Gson --declared --kind method --grep toJson --coord com.google.code.gson:gson:2.14.0 --fetch
```

`--declared` is only the type's own members. Inherited members are the default. A longer list takes `--limit`, and the footer names the flag that shows the rest.

Reference: [`tree`](https://mohammadmd1383.github.io/jdx/docs/reference/tree/), [`ls`](https://mohammadmd1383.github.io/jdx/docs/reference/ls/), [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/). Filters are in [Reading code](reading-code.md#what-can-i-call-members-and-outline).

## Show one method body

`jdx body` prints one method, not the class. On gson-2.14.0 the body of `com.google.gson.Gson#toJson(Object)` alone is 267 bytes. `javap -c` of that whole class is 60,590 bytes (226×), and the full `Gson.java` is 58,251 bytes (218×). That is the [README benchmark](../../README.md); reproduce it with [`docs/bench-compare.sh`](../bench-compare.sh).

```console exec
$ jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

`toJson` has several overloads. jdx will not pick one. It exits 2 and prints every candidate as a reference you can paste:

```console exec
$ jdx body 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch   # exit 2
```

Always quote references in a shell: `#`, `(`, and `$` are shell syntax. Paste one of those refs into the next command. Do not retype parameter types by hand.

Reference: [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/). The ref grammar is [Symbol references](symbol-references.md).

## Read source, or decompile

`jdx source` and `jdx body` prefer a paired `-sources.jar`. For `foo.jar` that is a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version. A sources jar with a different file name is not paired. `--fetch` downloads the `-sources.jar` with the binary.

With sources paired, the answer is the real file, with its path and line range:

```console exec max-lines=40
$ jdx source com.google.gson.JsonNull --lines 17:45 --coord com.google.code.gson:gson:2.14.0 --fetch
```

Without a paired sources jar, jdx decompiles with Vineflower and labels the result `⚠ reconstructed`, naming the engine and the jar. Slice a long file with `--lines A:B` or `--around '<canonical ref>' --context N` instead of reading all of it.

```console exec
$ jdx body 'java.util.HashMap#get(java.lang.Object)'
```

`--engine javap` prints raw bytecode instead. `--engine vineflower` forces a reconstruction even when sources are paired. When Vineflower returns only a `$VF: Could not decompile…` stub, the default ladder falls through to `javap` instead of serving the stub.

```console exec max-lines=12
$ jdx body 'java.util.HashMap#get(java.lang.Object)' --engine javap
```

`members` and `signature` always come from bytecode, so those lists stay exact with or without sources. When sources and bytecode disagree, jdx warns `SOURCES_VERSION_MISMATCH` and bytecode wins for structure. Kotlin binaries are read from `@Metadata`. Reading `.kt` sources needs a one-time `jdx kotlin install`, which is one of the few commands that uses the network. See [Reading code](reading-code.md#sources-decompilation-bytecode).

Reference: [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/).

## Look inside non-class contents

These read the jar as a zip of entries. They do not decompile classes.

`jdx manifest` lists `META-INF/MANIFEST.MF` main attributes (`Main-Class`, `Implementation-Version`, `Multi-Release`, and the rest). `jdx resources` lists non-class paths; `--cat` prints the text of the unique match. `jdx services` lists `META-INF/services` providers. `jdx literals` searches string constants: bytecode `LDC` loads, `String` field constants, and `.properties` keys and values.

```console exec max-lines=16
$ jdx manifest Multi-Release --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx resources META-INF/MANIFEST.MF --cat --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx literals 'com.google.gson' --coord com.google.code.gson:gson:2.14.0 --fetch --limit 5 --no-jdk
$ jdx services java.sql.Driver --coord org.postgresql:postgresql:42.7.4 --fetch --no-jdk
```

Two or more `--cat` matches exit 2 and list `path --in artifact` candidates. Tighten the pattern, or add `--in`, then `--cat` that one path. Binary payloads are not dumped. Nothing matched exits 1. The PostgreSQL coordinate is a separate artifact from Gson, so that command passes `--fetch` as well.

Reference: [`manifest`](https://mohammadmd1383.github.io/jdx/docs/reference/manifest/), [`resources`](https://mohammadmd1383.github.io/jdx/docs/reference/resources/), [`services`](https://mohammadmd1383.github.io/jdx/docs/reference/services/), [`literals`](https://mohammadmd1383.github.io/jdx/docs/reference/literals/).

## Maven coordinates and JDK classes

The running JDK is on the classpath with no setup. A `group:artifact:version` coordinate resolves from `~/.gradle/caches` and `~/.m2` first. The CLI contacts the network only when you pass `--fetch`, or when you run `jdx upgrade` or `jdx kotlin install`.

```console exec max-lines=14
$ jdx members java.util.HashMap --limit 5
$ jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
```

Inside a Gradle or Maven project, with no classpath flags, jdx discovers project classes and dependency jars from the build files and the local caches. It never runs Gradle or Maven to do that. See [Classpath & workspaces](classpath.md).

## Nothing in the jar runs

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser and no other code from the jar runs. jdx writes only inside its own cache, config, and runtime directories. `--jars` reads the file you named and does not download it.

## Using it from an AI agent

`jdx mcp` is the stdio server: 27 typed tools, one per query, so the agent does not quote `#` and `(`. `jdx setup` writes that server into the agent you name. The server itself never downloads; prime a missing coordinate with the CLI `--fetch` first.

```bash
jdx setup --agent claude-code --scope project
jdx setup --agent cursor --scope project
jdx setup --agent codex --scope project
jdx mcp
```

`--scope project` writes into the current checkout. `--scope system` writes the user-global file. The same command covers other agents. Per-agent pages: [integrations](https://mohammadmd1383.github.io/jdx/integrations/), [Claude Code](https://mohammadmd1383.github.io/jdx/integrations/claude-code/), [Cursor](https://mohammadmd1383.github.io/jdx/integrations/cursor/), [Codex](https://mohammadmd1383.github.io/jdx/integrations/codex/).

```console exec
$ jdx setup --agent claude-code --scope project --check   # exit 1
```

`--check` exits 1 when jdx is not wired yet and 0 when it is. The tool list is on the [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/) page. Wiring details and the CLI cheat sheet are in [Use with AI agents](ai-agents.md).

Reference: [`setup`](https://mohammadmd1383.github.io/jdx/docs/reference/setup/), [`mcp`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp/).

## jdx vs unzip + javap

| Task | unzip + javap | jdx |
|---|---|---|
| List what is in a jar | `unzip -l`, then pick a `.class` path | `jdx tree`, `jdx ls`, `jdx members` |
| One method | `javap -c` prints the whole class (60,590 bytes for Gson) | `jdx body` prints that method (267 bytes, 226× smaller) |
| The source file | `unzip -p` dumps `Gson.java` (58,251 bytes) | `jdx body` or `jdx source` returns the member or a line range |
| No `-sources.jar` | run a decompiler yourself | Vineflower, labelled `⚠ reconstructed`; `jdx body --engine javap` for bytecode |
| Manifest, resources, services, strings | `unzip -p` and `grep` | `jdx manifest`, `jdx resources --cat`, `jdx services`, `jdx literals` |
| Several overloads | you choose | exit 2, and every candidate is printed |

Every command accepts `--json`. Exit codes are in [Output, JSON & exit codes](output.md#exit-codes).

## Where next

- [Quickstart](quickstart.md) — the JDK and a Maven coordinate in five minutes.
- [Installation](installation.md) — Linux, macOS, Windows, and offline installs.
- [Reading code](reading-code.md) — `show`, `signature`, `doc`, and search.
- [Minecraft mods](minecraft-mods.md) — a mapped jar, mixin callers, and a version diff.
- [Command catalogue](../COMMANDS.md) — every flag.
