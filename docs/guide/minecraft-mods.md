# Inspect Minecraft mod jars from the terminal with jdx

Inspect a Minecraft mod jar from the terminal with jdx: members, method bodies, mixin-target callers, and API diffs, without remapping or a full decompile.

jdx answers in the names stored in the file you pass. v1 does not read mapping files and does not rename classes; Yarn, Mojmap, and obfuscated names come back exactly as they appear. Reading Tiny, SRG, or ProGuard mappings is phase-2 backlog. Point `--jars` at the jar Fabric Loom or NeoForge ModDevGradle already wrote, and use the same commands as the [quickstart](quickstart.md).

## Why

Mod work is a lot of small questions about jars you did not write:

- Which method do I mixin, and who already calls it?
- What does another mod's public API actually look like?
- Which members were deprecated or removed between two Minecraft or library versions?
- What is in `fabric.mod.json`, `mods.toml`, or a mixin config, without unzipping the jar?

Each of those is one command. `members`, `signature`, `body`, and `source` return one type or one member, so you do not decompile the whole jar into a tree of files. The guides for those commands are [Reading code](reading-code.md), [Usages, hierarchy & calls](navigating-code.md), and [API diff for upgrades](api-diff.md).

## Setup

jdx needs a JDK 21 or later and nothing else. Install it with the one-line installer, then check that the launcher found Java:

```bash
jdx version
jdx doctor
```

Details, including Windows and offline installs, are in [Installation](installation.md).

## Pass the jar that already has the names you want

`--jars` takes a jar, a class directory, or a glob. Repeat it for the mapped Minecraft jar and the mod jars you care about. `--no-jdk` leaves the JDK out, which you want here: otherwise usages and callers are full of `java.*`.

```bash
jdx members net.minecraft.world.item.ItemStack --declared --kind method --limit 20 --jars "$MAPPED_JAR" --no-jdk
```

`$MAPPED_JAR` is the Yarn or Mojmap jar Loom or ModDevGradle already produced. An obfuscated jar stays obfuscated. Quote globs so the shell does not expand them (`--jars 'build/libs/*.jar'`); an unquoted expansion exits 3.

Inside a Gradle project, with no classpath flags, jdx discovers `build/classes/...` and dependency jars named as `group:artifact:version` in the build scripts. It never runs Gradle to do that. The remapped Minecraft jar is usually not one of those coordinates, so pass it with `--jars`. When the dependency set cannot be pinned, jdx warns `PROJECT_DISCOVERY_FALLBACK` and still answers from the project classes it found. See [Classpath & workspaces](classpath.md#project-auto-discovery).

## Read a class

Start from a name you can see, or search for it:

```bash
jdx search 'ItemStack' --kind class --jars "$MAPPED_JAR" --no-jdk
jdx members net.minecraft.world.item.ItemStack --declared --kind method --grep '^get' --jars "$MAPPED_JAR" --no-jdk
jdx signature 'net.minecraft.world.item.ItemStack#use' --jars "$MAPPED_JAR" --no-jdk
```

`signature` prints every overload as a canonical ref. If the name is missing, jdx exits 1; if several members match, it exits 2 and lists each candidate. Paste one of those refs into the next command. Do not retype parameter types by hand — Yarn and Mojmap spell them differently, and the jar you passed is the authority.

```bash
jdx body '<canonical ref from signature>' --jars "$MAPPED_JAR" --no-jdk
jdx source net.minecraft.world.item.ItemStack --lines 1:80 --jars "$MAPPED_JAR" --no-jdk
```

`jdx source --around '<canonical ref>' --context 5` slices the file around one member. `jdx body --max-lines 80` caps a long method.

Kotlin mods are read from `@Metadata`, so `members` and `signature` show Kotlin declarations. A mixin targets the JVM method: `jdx signature --view jvm` (and the same flag on `jdx members`) prints that name. See [Reading code](reading-code.md#kotlin).

Reference: [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/), [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/), [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/), [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/).

The docs build has no Minecraft jar, so the recipes on this page are not executed. The same `members` command, run here against Gson:

```console exec max-lines=14
$ jdx members com.google.gson.JsonArray --kind method --declared --limit 6 --coord com.google.code.gson:gson:2.14.0
```

Swap `--coord` for `--jars "$MAPPED_JAR" --no-jdk` when the jar is yours.

## Find callers before you write a mixin

`callers` is override-aware by default. A query for an interface or base method also finds call sites that go through overrides, and a query for an override also finds polymorphic calls through its declaring supertypes. Constructors, static methods, and private methods stay exact. `--exact` restricts every query to invoke-owner matching. `--depth N` walks further (cycles are marked).

```bash
jdx callers '<canonical ref from signature>' --jars "$MAPPED_JAR" --jars "$MOD_JAR" --no-jdk
jdx callers '<canonical ref from signature>' --exact --limit 30 --jars "$MAPPED_JAR" --no-jdk
```

Pass every jar that might contain a call site. `callers` only accepts methods and constructors; a type or field ref exits 3. `calls` goes the other direction and stays bytecode-exact (the invoke names one owner).

Next: [find a mixin target](minecraft-mixin-targets.md) — the class, its type chain, the signature, the body, and the injection point.

Reference: [`callers`](https://mohammadmd1383.github.io/jdx/docs/reference/callers/), [`calls`](https://mohammadmd1383.github.io/jdx/docs/reference/calls/). More on the graph is in [Usages, hierarchy & calls](navigating-code.md#call-hierarchy-callers-and-calls).

## Usages

`usages` is every reference, not only calls: reads, writes, constructors, `instanceof`, annotations.

```bash
jdx usages net.minecraft.world.item.ItemStack --limit 30 --jars "$MAPPED_JAR" --jars "$MOD_JAR" --no-jdk
jdx usages '<canonical ref from signature>' --kind call --jars "$MOD_JAR" --no-jdk
```

`--src <dir>` adds your own source tree. Those hits are textual mentions with line numbers. `--in` and `--exclude` filter by jar file name.

Reference: [`usages`](https://mohammadmd1383.github.io/jdx/docs/reference/usages/).

## Deprecated members when porting

`annotated` lists classes and members that carry an annotation. `deprecated` means `java.lang.Deprecated`. `forRemoval` means `@Deprecated(forRemoval=true)`.

```bash
jdx annotated deprecated --jars "$MAPPED_JAR" --no-jdk
jdx annotated forRemoval --limit 50 --jars "$MAPPED_JAR" --no-jdk
```

That is the removal list inside one jar. For what actually changed between two jars, use `diff` below. Omit a leading `@` on the CLI (`Deprecated(forRemoval=true)`); Clikt reserves `@file` for argfiles.

Reference: [`annotated`](https://mohammadmd1383.github.io/jdx/docs/reference/annotated/).

## Strings and translation keys

`literals` searches string constants: bytecode `LDC` loads, `String` field `ConstantValue`s, and `.properties` keys and values. Translation keys embedded in code show up here. JSON lang files do not — those are resources, below.

```bash
jdx literals 'item.minecraft.diamond' --jars "$MAPPED_JAR" --no-jdk
jdx literals 'block.examplemod.' --limit 20 --jars "$MOD_JAR" --no-jdk
```

A properties hit looks like `prop path#key` plus the value. Nothing matched exits 1.

Reference: [`literals`](https://mohammadmd1383.github.io/jdx/docs/reference/literals/).

## Mod metadata

`resources` lists non-class paths. The pattern is a substring of the entry path. `--cat` prints the unique match as text.

```bash
jdx resources fabric.mod.json --cat --jars "$MOD_JAR" --no-jdk
jdx resources mods.toml --cat --jars "$MOD_JAR" --no-jdk
jdx resources mixins.json --jars "$MOD_JAR" --no-jdk
```

Two or more matches exit 2 and list `path --in artifact` candidates. Tighten the pattern, or add `--in 'example*.jar'`, then `--cat` that one path. `--max-lines` caps the text (default 200). Binary payloads are not dumped. A lang file is the same command: `jdx resources en_us.json --cat --jars "$MOD_JAR" --no-jdk` when that path is unique.

Reference: [`resources`](https://mohammadmd1383.github.io/jdx/docs/reference/resources/).

## Diff two versions of a mod or library jar

`diff` compares the bytecode API of two artifacts. The two arguments *are* the jars — there is no `--jars` and no classpath. `--visibility all` includes package-private and private members, which is the surface mixins target (the default `public` is public and protected only). `--fail-on breaking` exits 1 when a breaking change is present and 0 otherwise. That is the one documented exception to the [exit-code table](output.md#exit-codes): a diff that ran is success, and the gate is also `result.gate` in `--json`.

```bash
jdx diff old-mod.jar new-mod.jar --visibility all --fail-on breaking
jdx diff old-library.jar new-library.jar --visibility all --severity breaking --limit 40
```

Both sides must already use the same names. jdx does not remap, so a Yarn jar diffed against a Mojmap jar looks like nearly every member was removed and re-added. Compare two Yarn jars, or two Mojmap jars, or two builds of your mod in one namespace. The same command works for two mapped Minecraft jars. The full rule list and the CI sketch are in [API diff for upgrades](api-diff.md).

Reference: [`diff`](https://mohammadmd1383.github.io/jdx/docs/reference/diff/).

## Decompilation

`members` and `signature` always come from bytecode, so they stay exact with or without sources. `body` and `source` prefer a paired `-sources.jar`: for `foo.jar`, a sibling `foo-sources.jar`, or the same file name in the Gradle/Maven cache layout under that version. A sources jar with a different file name is not paired.

With no pair, jdx decompiles with Vineflower and labels the result `⚠ reconstructed`, including which engine and which jar. `--engine javap` prints raw bytecode instead. `--engine vineflower` forces a reconstruction even when sources are paired. When Vineflower returns only a `$VF: Could not decompile…` stub, the default ladder falls through to `javap` instead of serving the stub.

```bash
jdx body '<canonical ref from signature>' --engine javap --jars "$MAPPED_JAR" --no-jdk
```

The same ladder, with runnable Gson and JDK examples, is in [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). See also [Reading code](reading-code.md#sources-decompilation-bytecode).

## Nothing in the jar runs

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser and no other code from a mod jar runs. jdx writes only inside its own cache, config, and runtime directories. Network access happens only with `--fetch` (or `jdx upgrade` / `jdx kotlin install`); `--jars` reads the file you named and does not download.

## From an AI agent

`jdx setup` writes the MCP config for the agent you name. `jdx mcp` is the stdio server: one typed tool per query, so the agent does not have to quote `#` and `(`, and the index stays warm for the session.

```bash
jdx setup --agent cursor --scope project
jdx setup --agent claude-code --scope project
jdx ws create mc --jars "$MAPPED_JAR" --jars "$MOD_JAR" --no-jdk
jdx mcp -w mc
```

`--scope project` writes into the current checkout; `--scope system` writes the user-global file. Other agents, the cheat sheet from `jdx help --agent`, and the tool list are in [Use with AI agents](ai-agents.md).

Reference: [`setup`](https://mohammadmd1383.github.io/jdx/docs/reference/setup/), [`mcp`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp/), [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/).

## Where next

- [Find a Mixin target in Minecraft](minecraft-mixin-targets.md) — class, type chain, method descriptor, and an injection point.
- [Find which JAR contains a class](find-class-in-jar.md) — which mod jar in a directory holds a class.
- [Read and decompile a JAR](read-jar-from-terminal.md) — list a jar, open one method, decompile when there are no sources.
- [An MCP server for Java and Kotlin bytecode](java-mcp-server.md) — `jdx mcp` pointed at the mapped jar.
- [Quickstart](quickstart.md) — the same commands against the JDK and Gson.
- [API diff for upgrades](api-diff.md) — rules, severities, and `--fail-on`.
- [Command catalogue](../COMMANDS.md) — every flag.
- [Symbol references](symbol-references.md) — how to paste a canonical ref.
