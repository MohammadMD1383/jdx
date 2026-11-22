# Help AI coding agents understand Java dependencies without dumping source

Help an AI agent read Java dependencies with outline, members, and one method body, to reduce token use without a source dump.

An agent that unzips a dependency and reads the source file spends its context on code it did not ask about. jdx answers one question at a time: the type card, the members declared on that type, the callable surface, then one signature and one body. Each line names its symbol canonically, so the next command is a copy-paste. Install with the [one-line installer](installation.md). The flag details live in [Reading code](reading-code.md). Every command output on this page was produced by running that command when the page was built.

## Start with the type card

`jdx show` is the class card: kind, modifiers, supertypes, member counts, provenance, and a `next:` line. A member reference is a usage error (exit 3). A short name that matches several types exits 2 and lists the candidates.

```console exec
$ jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
```

`--coord` resolves from `~/.gradle/caches` and `~/.m2` first. `--fetch` allows a download when the coordinate is missing. The running JDK needs no flag: `jdx show java.util.HashMap`.

Reference: [`show`](https://mohammadmd1383.github.io/jdx/docs/reference/show/).

## Outline, then members

`jdx outline` prints one dense line per member declared on that exact type. Inherited members are never included. This is the file-structure view, and it is the right first list for a dependency the agent does not own.

```console exec max-lines=16
$ jdx outline com.google.gson.Gson --kind method --limit 8 --coord com.google.code.gson:gson:2.14.0 --fetch
```

`jdx members` is the completion list. Inherited members are included by default and grouped by the declaring type, with `java.lang.Object` collapsed to one line unless `--from java.lang.Object` expands it. `--declared` limits the list to the type itself, which is `outline` with the same filters.

Narrow the list instead of reading more:

| Want | Flag |
|---|---|
| only this type's own members | `--declared`, or `jdx outline` |
| methods, fields, constructors, properties | `--kind method`, `--kind field`, `--kind ctor`, `--kind property` |
| a name regex | `--grep '^get'` |
| fewer rows | `--limit N` (default 50; the footer names the flag that shows the rest) |
| less text around the rows | `--brief` (no group headers, no provenance; text only) |
| a hard line cap | `--max-lines N` (text only) |

`--brief` and `--with-doc` together exit 3. Neither `--brief` nor `--max-lines` changes `--json` output. The envelope is the same information as the text. See [Output, JSON & exit codes](output.md#bounded-by-default).

```console exec max-lines=20
$ jdx outline com.google.gson.JsonArray --brief --limit 8 --coord com.google.code.gson:gson:2.14.0 --fetch
$ jdx members com.google.gson.Gson --declared --kind method --grep toJson --coord com.google.code.gson:gson:2.14.0 --fetch
```

Reference: [`outline`](https://mohammadmd1383.github.io/jdx/docs/reference/outline/), [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/).

## Open one signature, then one body

`jdx signature` prints every overload of one member and exits 0. `jdx body` will not pick one: the same name exits 2 and lists each candidate as a canonical ref. Paste one of those refs into `jdx body`. Always quote the ref: `#`, `(`, and `$` are shell syntax.

```console exec
$ jdx signature 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch
$ jdx body 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch   # exit 2
$ jdx body 'com.google.gson.Gson#toJson(Object)' --max-lines 30 --coord com.google.code.gson:gson:2.14.0 --fetch
```

`body` returns that member. `--max-lines` caps the slice (default 200). `--context N` adds lines around it. `jdx source` is the whole file, so slice it: `--lines 120:160` or `--around '<canonical ref>' --context 5`. Prefer `body` when one member is the question.

With a paired `-sources.jar`, the text is the real file. Without one, jdx decompiles with Vineflower and labels the result `⚠ reconstructed`. `--engine javap` prints raw bytecode of that member. Structure (`show`, `outline`, `members`, `signature`) always comes from bytecode. See [Reading code](reading-code.md#sources-decompilation-bytecode).

Reference: [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/), [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/).

## One measured task

The repository measured one task on gson-2.14.0: list Gson's API, then show only `toJson(Object)`. `unzip` + `javap` + `grep` + `sed` was 5 calls and about 17.5 KB. `jdx members` plus `jdx body` was 2 calls and about 4.6 KB. The body alone was 267 bytes, against 60,590 bytes for `javap -c` of that class and 58,251 bytes for `Gson.java`. The README states tokens as about bytes/4 for that run (about 4.4k, about 1.2k, and about 67 for the body). That is one task, reproduced with [`docs/bench-compare.sh`](../bench-compare.sh), not a rate for every question. See the [README benchmark](../../README.md) and [Read and decompile a JAR](read-jar-from-terminal.md).

## Name the classpath once

Typing `--coord` on every call is the expensive part for an agent. A workspace stores the jars:

```bash
jdx ws create gson --coord com.google.code.gson:gson:2.14.0
jdx -w gson outline com.google.gson.Gson --kind method --limit 20
```

`jdx mcp` answers from a stored workspace and does not auto-discover a project. The config for Claude Code, Cursor, and Codex is [An MCP server for Java and Kotlin bytecode](java-mcp-server.md). Wiring and the cheat sheet are in [Use with AI agents](ai-agents.md). Classpath rules are in [Classpath & workspaces](classpath.md).

## Mapped jars

jdx reads the names stored in the file. v1 does not read mapping files and does not rename classes. Pass the jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars`. `diff` compares two artifacts that already share one mapping namespace. A Yarn jar against a Mojmap jar looks like nearly every member was removed and re-added. See [Minecraft mods](minecraft-mods.md).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from a dependency runs.

## Where next

- [Reading code](reading-code.md) — `doc`, search, and Kotlin `@Metadata`.
- [A javap alternative](javap-alternative.md) — class-file signatures, callers, and implementors.
- [Read and decompile a JAR](read-jar-from-terminal.md) — list a jar, then one method.
- [Output, JSON & exit codes](output.md) — `--json`, truncation, and exit 2.
- [Command catalogue](../COMMANDS.md) — every flag.
