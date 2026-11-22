# A javap alternative for reading class files and jars

jdx is a javap alternative for reading class file signatures, one method, callers, and implementors from a jar, a coordinate, or the JDK.

javap disassembles a class file. jdx answers the question you would otherwise build out of `javap`, `unzip`, and `grep`, and it stops at the member you named. Text is the default. Every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate. jdx does not guess. Install with the [one-line installer](installation.md), then try the [quickstart](quickstart.md). Every command output on this page was produced by running that command when the page was built.

## What javap prints

`javap` takes a classpath and a class name. `javap -public -classpath app.jar com.example.App` prints that class's public members. `javap -c -p` prints the bytecode of every method in the class, private methods included. `javap -v` adds the constant pool and the attributes. The unit of output is the whole class.

That listing is the right tool when you want the constant pool or a verbose attribute dump of one class file. javap does not search other classes, list callers, list implementors, decompile to Java source, or diff two versions of a jar.

## What jdx adds on the same file

Pass the jar, a class directory, a Maven coordinate, or a JDK class. The commands below are the ones a `javap` session usually turns into.

| Question | javap | jdx |
|---|---|---|
| Members of one class | `javap -public -classpath app.jar com.example.App` prints the class | `jdx members com.example.App --declared --jars app.jar` |
| One method's signature | the class listing, or `javap -s` for JVM descriptors | `jdx signature 'com.example.App#run'` prints each overload with parameter names |
| Bytecode of one method | `javap -c` prints every method | `jdx body 'com.example.App#run()' --engine javap --jars app.jar` |
| Java source | javap disassembles; it does not decompile | paired `-sources.jar`, or Vineflower labelled `⚠ reconstructed` |
| A name somewhere in the jar | you choose the class first | `jdx search` |
| Who calls it, who implements it | not a javap question | `jdx callers`, `jdx implementors` |
| What changed between two jars | not a javap question | `jdx diff` |

`--jars` takes a jar, a class directory, or a glob. Quote globs so the shell does not expand them; an unquoted expansion exits 3. `--no-jdk` leaves the JDK out when the question is about that jar alone.

## Read class file signatures

`jdx members` lists what you can call. `jdx signature` prints one member: each overload, with parameter names, generics, `throws`, and defaults. Both come from bytecode parsed with ASM, so the signatures stay exact with or without a sources jar. A name without a parameter list lists every overload and exits 0. `jdx body` on that same name exits 2 and prints every candidate as a reference you can paste. A type reference passed to `signature` exits 3; that command wants a member.

```console exec max-lines=16
$ jdx members java.util.HashMap --declared --kind method --limit 6
$ jdx signature 'java.util.HashMap#get'
```

The same question against a library jar. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`.

```console exec
$ jdx signature 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch
```

Always quote references in a shell: `#`, `(`, and `$` are shell syntax. Paste one of the printed refs into the next command. Do not retype parameter types by hand.

Reference: [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/), [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/). The ref grammar is [Symbol references](symbol-references.md). Filters are in [Reading code](reading-code.md#what-can-i-call-members-and-outline).

## Search, callers, and implementors

These have no `javap` equivalent. `search` finds a name across the classpath. Default `--kind all` covers types, packages, and modules. Pass `--kind method` or `--kind field` to scan members. `implementors` lists who implements an interface or extends a class. `callers` is override-aware by default; `--exact` keeps invoke-owner matching. Constructors, static methods, and private methods stay exact either way.

```console exec max-lines=16
$ jdx search '*Http*Client' --kind class --limit 8
$ jdx implementors com.google.gson.TypeAdapter --limit 6 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx callers 'com.google.gson.JsonArray#add(com.google.gson.JsonElement)' --limit 5 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

`callers` only accepts methods and constructors. A type or field ref exits 3. Nothing matched exits 1. `Gson#toJson(Object)` has no call sites inside gson-2.14.0, so the example above uses `JsonArray#add(JsonElement)`, which three methods in that jar call.

Reference: [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/), [`implementors`](https://mohammadmd1383.github.io/jdx/docs/reference/implementors/), [`callers`](https://mohammadmd1383.github.io/jdx/docs/reference/callers/). More of the graph is in [Usages, hierarchy & calls](navigating-code.md#call-hierarchy-callers-and-calls).

## Decompile with Vineflower, or stay on bytecode

`jdx body` and `jdx source` prefer a paired `-sources.jar`. For `foo.jar` that is a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version. `--fetch` downloads the `-sources.jar` with the binary.

With no pair, jdx decompiles with Vineflower and labels the result `⚠ reconstructed`, naming the engine and the jar. `--engine javap` prints the raw bytecode of that one member instead. `--engine vineflower` forces a reconstruction even when sources are paired. When Vineflower returns only a `$VF: Could not decompile…` stub, the default ladder falls through to `javap` instead of serving the stub.

```console exec max-lines=12
$ jdx body 'java.util.HashMap#get(java.lang.Object)' --engine javap
```

On gson-2.14.0 the body of `com.google.gson.Gson#toJson(Object)` alone is 267 bytes. `javap -c` of that whole class is 60,590 bytes, and the full `Gson.java` is 58,251 bytes. That measurement is the [README benchmark](../../README.md). Reproduce it with [`docs/bench-compare.sh`](../bench-compare.sh). The walkthrough is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

Reference: [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/), [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/).

## Diff two jars

`jdx diff` compares the bytecode API of two artifacts. The two arguments are the jars, class directories, globs, or Maven coordinates. There is no `--jars` and no classpath. `--fail-on breaking` exits 1 when a breaking change is present and 0 otherwise. That is the one documented exception to the [exit-code table](output.md#exit-codes). The verdict is also `result.gate` in `--json`.

```bash
jdx diff old.jar new.jar --fail-on breaking
jdx diff com.google.code.gson:gson:2.11.0 com.google.code.gson:gson:2.14.0 --severity breaking --fetch
```

The executed Gson comparison is on [API diff for upgrades](api-diff.md).

jdx reads the names stored in the file. v1 does not read mapping files and does not rename classes. Pass the jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars` on the commands that take a classpath. `diff` only works within one mapping namespace: both sides must already use the same names. A Yarn jar diffed against a Mojmap jar looks like nearly every member was removed and re-added. Compare two Yarn jars, or two Mojmap jars, or two builds of your mod. See [Minecraft mods](minecraft-mods.md).

Reference: [`diff`](https://mohammadmd1383.github.io/jdx/docs/reference/diff/).

## Jars, coordinates, and the JDK

The running JDK is on the classpath with no setup. A `group:artifact:version` coordinate resolves from `~/.gradle/caches` and `~/.m2` first. The CLI contacts the network only when you pass `--fetch`, or when you run `jdx upgrade` or `jdx kotlin install`. Inside a Gradle or Maven project, with no classpath flags, jdx discovers project classes and dependency jars from the build files and the local caches. It never runs Gradle or Maven to do that. The remapped Minecraft jar is usually not one of those coordinates, so pass it with `--jars`. See [Classpath & workspaces](classpath.md).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs.

From an agent, the same questions are tools on `jdx mcp`. See [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

## Where next

- [Read and decompile a JAR](read-jar-from-terminal.md) — list a jar, open one method, decompile when there are no sources.
- [Reading code](reading-code.md) — `show`, `outline`, `doc`, and search.
- [Help AI coding agents understand Java dependencies](reduce-agent-tokens-java.md) — `outline` and `members` before a source dump.
- [Usages, hierarchy & calls](navigating-code.md) — the rest of the graph.
- [Command catalogue](../COMMANDS.md) — every flag.
