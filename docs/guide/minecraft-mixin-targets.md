# Find a Mixin target in Minecraft from the terminal

Find a mixin target method, the Minecraft method descriptor, and a mixin injection point by inspecting a Minecraft class from the terminal.

The workflow below is what you run before writing a mixin: find the class, see its type chain, read the member and its descriptor, read the body, then see who calls it and what it calls. jdx needs JDK 21 or later. Install with the [one-line installer](installation.md). Pass the mapped jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars`. The same commands, against any jar, are the [quickstart](quickstart.md). Broader mod questions — metadata, deprecated members, a version diff — are in [Minecraft mods](minecraft-mods.md).

The docs build has no Minecraft jar, so the Minecraft recipes on this page are bash blocks and are not executed. Each console block was produced by running that command when the page was built, against Gson 2.14.0 or the JDK. Swap `--coord com.google.code.gson:gson:2.14.0 --fetch` for `--jars "$MAPPED_JAR" --no-jdk` when the jar is yours. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. A coordinate is not downloaded without that flag.

## Pass the mapped jar

`$MAPPED_JAR` is the Yarn or Mojmap jar Loom or ModDevGradle already produced. jdx reads the names stored in that file. An obfuscated jar stays obfuscated. `--no-jdk` leaves the JDK out, which you want here: otherwise callers and hierarchy are full of `java.*`.

`--jars` takes a jar, a class directory, or a glob, and it is repeatable. Quote globs so the shell does not expand them (`--jars 'mods/*.jar'`). An unquoted expansion exits 3. Always quote a reference in the shell: `#`, `(`, and `$` are shell syntax.

Inside a Gradle project, with no classpath flags, jdx discovers `build/classes/...` and dependency jars named as `group:artifact:version` in the build scripts. It never runs Gradle to do that. The remapped Minecraft jar is usually not one of those coordinates, so pass it with `--jars`. See [Classpath & workspaces](classpath.md#project-auto-discovery).

## Find the class

`jdx search` is the partial name. `--kind class` keeps classes. A bare word matches case-insensitively as a substring of the simple name, or as camel humps. `jdx resolve` takes one exact name: a simple name, a binary name, or a dotted name. Several `resolve` candidates exit 0, and each row names the kind, the canonical name, and the jar file name. A miss exits 1. `jdx ls` with an exact package lists the types in that package, each with the jar file name.

```bash
jdx search 'ItemStack' --kind class --jars "$MAPPED_JAR" --no-jdk
jdx resolve net.minecraft.world.item.ItemStack --jars "$MAPPED_JAR" --no-jdk
jdx ls net.minecraft.world.item --jars "$MAPPED_JAR" --no-jdk
```

The same three commands, run here against Gson:

```console exec max-lines=12
$ jdx search 'Json*' --kind class --limit 6 --in 'gson*' --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx resolve com.google.gson.JsonArray --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx ls com.google.gson --limit 6 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

A method name whose class you do not know is `jdx search '<name>' --kind method`. That scan reads members. Default `--kind all` covers types, packages, and modules, and a miss under that default names `--kind method`.

Reference: [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/), [`resolve`](https://mohammadmd1383.github.io/jdx/docs/reference/resolve/), [`ls`](https://mohammadmd1383.github.io/jdx/docs/reference/ls/). Finding which file in a directory holds a class is [Find which JAR contains a class](find-class-in-jar.md).

## See the type chain

`jdx hierarchy` prints supertypes upward and subtypes downward. Supertype lines say `extends` or `implements`. Subtype lines name the class and the jar, so a second `--jars 'mods/*.jar'` shows which mods extend the class. `--up` or `--down` picks a direction. `--direct` stops at one level. `--depth N` caps it. A member reference exits 3; this command wants a type.

```bash
jdx hierarchy net.minecraft.world.item.Item --jars "$MAPPED_JAR" --no-jdk
jdx hierarchy net.minecraft.world.item.Item --down --jars "$MAPPED_JAR" --jars 'mods/*.jar' --no-jdk
```

```console exec max-lines=16
$ jdx hierarchy com.google.gson.JsonElement --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Reference: [`hierarchy`](https://mohammadmd1383.github.io/jdx/docs/reference/hierarchy/). More of the graph is in [Usages, hierarchy & calls](navigating-code.md#type-hierarchy-hierarchy-and-implementors).

## List members and read the signature

`jdx members` lists what you can call. Inherited members are the default; `--declared` keeps the type's own members. The default visibility is public and protected. A mixin often targets a private or package-private method, so pass `--access all` (or `--access private`). `--kind method` keeps methods. `--grep '^use'` keeps names matching that regex. `jdx outline` is the same list for declared members only: one line each, never inherited, with the same filters.

```bash
jdx members net.minecraft.world.item.ItemStack --declared --kind method --access all --grep '^use' --jars "$MAPPED_JAR" --no-jdk
jdx outline net.minecraft.world.item.ItemStack --kind method --access all --jars "$MAPPED_JAR" --no-jdk
```

```console exec max-lines=14
$ jdx members com.google.gson.JsonArray --declared --kind method --access all --limit 6 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx outline com.google.gson.JsonArray --kind method --access all --limit 6 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

With `--no-jdk`, a JDK supertype is not in the workspace. jdx warns `UNRESOLVED_SUPERTYPE` and omits members inherited from it. `--declared` is the list a mixin uses: the methods on the class itself, private ones included when you passed `--access all`.

`jdx signature` prints one source-style line per overload: visibility, fully qualified parameter and return types, and parameter names from the class file (`arg0`, `arg1`, … when the jar has none). An under-specified name lists every overload and exits 0. A missing member exits 1. A type reference exits 3. Bridge and synthetic members are hidden unless you pass `--include-synthetic`.

That line identifies the overload. It is a source-style signature. A Mixin method selector that pins one overload is the JVM method name immediately followed by the JVM descriptor, such as `get(Ljava/lang/Object;)Ljava/lang/Object;`. jdx does not print that selector, and it does not print an `@At` target. The canonical ref uses dotted names (`java.util.HashMap#get(java.lang.Object)`), not the internal owner form.

The JVM descriptor is the `descriptor:` line from `jdx body '<ref>' --engine javap`, which disassembles that one member. Take the method name from the signature line and the descriptor from that line. `--json` on `signature` repeats the source-style line and adds the canonical `ref`. It has no descriptor field. The `descriptor:` line is part of the disassembly text.

You can paste a descriptor into jdx. `Type#name(Lpkg/Arg;)Lpkg/Return;` selects that one member. The printed signature stays source-style. The ref grammar is [Symbol references](symbol-references.md#overloads-and-descriptors).

Kotlin mods are read from `@Metadata`, so the default signature is the Kotlin declaration. A mixin targets the JVM method: `jdx signature --view jvm` (and the same flag on `jdx members`) prints the JVM name, including a mangled name or a hidden `Continuation` parameter. The descriptor still comes from `jdx body --engine javap`.

```bash
jdx signature 'net.minecraft.world.item.ItemStack#use' --jars "$MAPPED_JAR" --no-jdk
jdx signature 'net.minecraft.world.item.ItemStack#use' --view jvm --jars "$MAPPED_JAR" --no-jdk
jdx body '<canonical ref>' --engine javap --max-lines 20 --jars "$MAPPED_JAR" --no-jdk
```

Run here against `HashMap#get`. The first command is the source-style line. The second passes the JVM descriptor and prints the same kind of line. The third is the disassembly: its source line says `disassembled by javap` and `⚠ reconstructed`, and the `descriptor:` line is the JVM descriptor.

```console exec max-lines=10
$ jdx signature 'java.util.HashMap#get'
$ jdx signature 'java.util.HashMap#get(Ljava/lang/Object;)Ljava/lang/Object;'
$ jdx body 'java.util.HashMap#get(java.lang.Object)' --engine javap
```

A name with several overloads is not a guess. `jdx body` on that name exits 2 and prints every candidate as a canonical ref. Paste one of them:

```console exec
$ jdx body 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch   # exit 2
```

Reference: [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/), [`outline`](https://mohammadmd1383.github.io/jdx/docs/reference/outline/), [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/).

## Read the method body

`jdx body` prints one method. With a paired `-sources.jar` (a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version) the text is the real file. A sources jar with a different file name is not paired. With no pair, jdx decompiles with Vineflower and the source line says `decompiled by vineflower` and `⚠ reconstructed`. `--engine javap` is the disassembly in the previous section. `--engine vineflower` forces a reconstruction even when sources are paired. When Vineflower returns only a `$VF: Could not decompile…` stub, the default ladder falls through to `javap` instead of serving the stub. `jdx body --max-lines 80` caps a long method.

```bash
jdx body '<canonical ref from signature>' --jars "$MAPPED_JAR" --no-jdk
jdx body '<canonical ref from signature>' --max-lines 80 --jars "$MAPPED_JAR" --no-jdk
```

`members` and `signature` always come from bytecode, so those lines stay exact with or without sources. The same ladder, with runnable Gson and JDK examples, is in [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

## Find a mixin injection point

`jdx callers` lists who calls the method. It is override-aware by default: a query for an interface or base method also finds call sites that go through overrides, and a query for an override also finds polymorphic calls through its declaring supertypes. Constructors, static methods, and private methods stay exact. `--exact` restricts every query to invoke-owner matching. `--depth N` walks further (cycles are marked `…(cycle)`). Pass every jar that might contain a call site. `callers` only accepts methods and constructors; a type or field ref exits 3.

`jdx calls` lists what that method calls. That is the invoke you aim an injection at. Callees stay bytecode-exact: the invoke names one owner, so `calls` does not expand overrides.

Each row is a canonical ref. Paste it into `jdx body` to read that caller or callee.

```bash
jdx callers '<canonical ref from signature>' --jars "$MAPPED_JAR" --jars 'mods/*.jar' --no-jdk
jdx callers '<canonical ref from signature>' --exact --limit 30 --jars "$MAPPED_JAR" --no-jdk
jdx calls '<canonical ref from signature>' --jars "$MAPPED_JAR" --no-jdk
```

```console exec max-lines=12
$ jdx callers 'com.google.gson.JsonArray#add(com.google.gson.JsonElement)' --limit 5 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx calls 'com.google.gson.Gson#toJson(java.lang.Object)' --limit 8 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Reference: [`callers`](https://mohammadmd1383.github.io/jdx/docs/reference/callers/), [`calls`](https://mohammadmd1383.github.io/jdx/docs/reference/calls/). More on the graph is in [Usages, hierarchy & calls](navigating-code.md#call-hierarchy-callers-and-calls).

## A constant or a field

`jdx usages` is every reference, not only calls: reads, writes, constructors, `instanceof`, annotations. `--kind read` and `--kind write` are the field edges. `--kind call` is the call edge. `jdx literals` searches string constants: bytecode `LDC` loads, `String` field `ConstantValue`s, and `.properties` keys and values. JSON lang files are not in `literals`; those are `jdx resources`. Nothing matched exits 1.

```bash
jdx usages '<field ref from members>' --kind read --jars "$MAPPED_JAR" --jars 'mods/*.jar' --no-jdk
jdx usages '<field ref from members>' --kind write --jars "$MAPPED_JAR" --no-jdk
jdx literals 'item.minecraft.diamond' --jars "$MAPPED_JAR" --no-jdk
```

```console exec max-lines=12
$ jdx usages com.google.gson.JsonNull --limit 5 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx literals 'com.google.gson' --coord com.google.code.gson:gson:2.14.0 --fetch --limit 5 --no-jdk
```

Reference: [`usages`](https://mohammadmd1383.github.io/jdx/docs/reference/usages/), [`literals`](https://mohammadmd1383.github.io/jdx/docs/reference/literals/), [`resources`](https://mohammadmd1383.github.io/jdx/docs/reference/resources/).

## From an AI agent

`jdx setup` writes the MCP config for the agent you name. `jdx mcp` is the stdio server: one typed tool per query (`jdx_search`, `jdx_resolve`, `jdx_hierarchy`, `jdx_members`, `jdx_outline`, `jdx_signature`, `jdx_body`, `jdx_callers`, `jdx_calls`, `jdx_usages`, `jdx_literals`), so the agent does not quote `#` and `(`. The server does not take a jar path. Create a workspace, then start the server on it. `jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`.

```bash
jdx setup --agent cursor --scope project
jdx setup --agent claude-code --scope project
jdx ws create mc --jars "$MAPPED_JAR" --jars 'mods/*.jar' --no-jdk
jdx mcp -w mc
```

```console exec
$ jdx setup --agent claude-code --scope project --check   # exit 1
```

`--check` exits 1 when jdx is not wired yet and 0 when it is. The server never downloads. Other agents and the tool list are in [Use with AI agents](ai-agents.md) and [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

Reference: [`setup`](https://mohammadmd1383.github.io/jdx/docs/reference/setup/), [`mcp`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp/), [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/).

## Names, mappings, and what never runs

jdx does not remap. It reads whatever names the jar uses, so query the names of the mapping namespace in that jar: the Loom-mapped jar, a Mojmap jar, a Yarn jar, or an obfuscated jar. Reading Tiny, SRG, or ProGuard mappings is phase-2 backlog.

`jdx diff` compares two artifacts that already use the same names. A Yarn jar diffed against a Mojmap jar, or an intermediary jar diffed against a named jar, looks like nearly every member was removed and re-added. Compare two Yarn jars, or two Mojmap jars, or two builds in one namespace. See [See what changed between two Minecraft or mod versions](minecraft-version-diff.md), [Minecraft mods](minecraft-mods.md#diff-two-versions-of-a-mod-or-library-jar), and [API diff for upgrades](api-diff.md).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser and no other code from the jar runs. jdx writes only inside its own cache, config, and runtime directories. `--jars` reads the file you named and does not download it.

## Where next

- [Minecraft mods](minecraft-mods.md) — mod metadata, deprecated members, and a version diff.
- [See what changed between two Minecraft or mod versions](minecraft-version-diff.md) — `diff` two mapped jars, then one member.
- [Find which JAR contains a class](find-class-in-jar.md) — which file in `mods/*.jar` holds a class.
- [Read and decompile a JAR](read-jar-from-terminal.md) — one method, source, or a decompilation.
- [Usages, hierarchy & calls](navigating-code.md) — the rest of the graph.
- [Symbol references](symbol-references.md) — canonical refs, and JVM descriptors as input.
- [Command catalogue](../COMMANDS.md) — every flag.
