# See what changed between two Minecraft or mod versions with jdx diff

See what a Minecraft or mod upgrade changed in the public API: two Loom-mapped jars in one mapping namespace, then the member, its signature, and its body.

The workflow below is what you run when Minecraft or a library moves. `jdx diff` names every bytecode change between two artifacts. You then open the type with `members`, the member with `signature`, and the method with `body`. jdx needs JDK 21 or later. Install with the [one-line installer](installation.md). The rule list and the CI gate, for any two jars, are in [API diff for upgrades](api-diff.md). Metadata, deprecated members, and mixin callers are in [Minecraft mods](minecraft-mods.md). Picking a method before writing a mixin is [Find a Mixin target in Minecraft](minecraft-mixin-targets.md).

The docs build has no Minecraft jar, so the Minecraft recipes on this page are bash blocks and are not executed. Each console block was produced by running that command when the page was built, against Gson 2.11.0 and Gson 2.14.0. Swap a coordinate for the jar file when the jar is yours. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. A coordinate is not downloaded without that flag.

## Two jars, one mapping namespace

jdx reads the names stored in the file. v1 does not read mapping files and does not rename classes. You pass the Loom-mapped jar yourself: the Yarn or Mojmap jar Fabric Loom or NeoForge ModDevGradle already wrote. An obfuscated jar stays obfuscated. An intermediary jar stays intermediary.

`diff` only works within one mapping namespace. Both sides must already use the same names. Compare two Yarn jars, or two Mojmap jars, or two intermediary jars, or two builds of your mod that were compiled in one namespace. A Yarn jar against a Mojmap jar, and an intermediary jar against a named jar, share almost no names, so the report looks like nearly every member was removed and re-added. That comparison is meaningless. A named jar uses Yarn or Mojmap names. An intermediary jar uses intermediary names.

The two arguments of `diff` are the artifacts. Each one is a jar file, a class directory, a glob, or a Maven coordinate. There is no classpath, so `diff` has no `--jars`, no `-w`, and no `--no-jdk`. It needs no workspace, and it runs in an empty directory. `members`, `signature`, and `body` are the commands that take `--jars`.

Quote a glob so the shell does not expand it. A glob that resolves to anything but one artifact exits 3: a diff compares two artifacts, and it has nowhere to put a set. A path that is missing, or a coordinate that is not in the local caches and was not given `--fetch`, exits 5.

```bash
jdx diff "$OLD_MAPPED_JAR" "$NEW_MAPPED_JAR"
jdx diff old-mod.jar new-mod.jar
```

`$OLD_MAPPED_JAR` and `$NEW_MAPPED_JAR` are two Loom-mapped Minecraft jars in the same namespace, one version apart. A mod or library jar you already have is the same command with those two files. A library that is a Maven coordinate is the Gson block below.

## Compare the public API

The default surface is `public`: public and protected types and members, the API a caller compiles against. Findings are ordered most severe first. Each line is `severity  RULE  ref`, and a detail after the ref when the rule has one. The ref is canonical, so the next command is a copy-paste.

```bash
jdx diff "$OLD_MAPPED_JAR" "$NEW_MAPPED_JAR" --severity breaking --limit 40
jdx diff old-mod.jar new-mod.jar --severity breaking --limit 40
```

`--severity breaking` prints breaking findings only. `--severity suspicious` prints breaking and suspicious. The default `all` prints every severity. The tally under the list always describes the whole comparison, including findings the filter hid. A hidden line says how many were below the filter. `--limit` caps what is printed (default 200). The tail it cuts is the least severe. The footer names the limit that would show the rest.

The same comparison, run here against two Gson versions:

```console exec max-lines=16
$ jdx diff com.google.code.gson:gson:2.11.0 com.google.code.gson:gson:2.14.0 --fetch --limit 8
```

The header names both sides and the surface (`public surface` or `all surface`). A file side is labeled with its file name, never the absolute path. A coordinate side is labeled with `group:artifact:version`. Two `source:` lines say `bytecode`: a diff reads class files and nothing else. No sources jar is paired, and nothing is decompiled.

The report closes with `next: jdx members <type>`. That type is the class of the first breaking finding, or of the first finding when nothing is breaking. The hint names the type. It does not pass a jar, because `diff` does not leave a classpath behind. Warnings are printed on stderr. A multi-release jar warns `MULTI_RELEASE_VARIANT` when a versioned class is the one read. Those lines are not findings.

A member's identity is its name plus its erased parameter list, so a covariant return is one `RETURN_TYPE_CHANGED`, not a removal plus an addition. A removal paired with one matching addition is one retyped signature (`PARAMETER_TYPE_CHANGED`, or `FIELD_TYPE_CHANGED` for a field). An ambiguous pairing is never guessed: two removals of one shape stay two removals. The full rule list is in [API diff for upgrades](api-diff.md).

`diff` only sees types inside the two artifacts you named. A closed list of JDK roots is skipped when a removed member might have moved upward: `java.lang.Object`, `java.lang.Enum`, `java.lang.Record`, `java.lang.Throwable`, `java.lang.annotation.Annotation`, `java.lang.constant.Constable`, and `java.lang.constant.ConstantDesc`. Any other supertype that is not in the new jar stays a breaking `MEMBER_REMOVED`, and the detail names that supertype and says inheritance was not checked. A method that moved onto a Minecraft superclass is that case when you diff two mod jars and do not pass the Minecraft jar. Diff the two mapped Minecraft jars when the question is about Minecraft, and the two mod jars when the question is about the mod.

## Private members and the gate

`--visibility all` compares every declared type and member, including package-private and private. That is the surface a mixin targets. The default `public` hides it. `--include-synthetic` also compares bridge and synthetic members, which are hidden by default.

```bash
jdx diff "$OLD_MAPPED_JAR" "$NEW_MAPPED_JAR" --visibility all --limit 40
jdx diff old-mod.jar new-mod.jar --visibility all --severity breaking
```

A comparison that ran exits 0, whatever it found. The findings are the answer. `--fail-on breaking` exits 1 when a breaking change is present and 0 otherwise. `--fail-on any` exits 1 when any difference is present. That is the one documented exception to the [exit-code table](output.md#exit-codes). The verdict is also `result.gate` in `--json` (`mode`, `tripped`, `exitCode`). A bad flag, an empty side, or a glob that is not exactly one artifact exits 3. An unreadable artifact exits 5.

Gson 2.11.0 to 2.14.0 is a comparison the gate lets through. The tally still counts every informational finding the severity filter hid:

```console exec max-lines=10
$ jdx diff com.google.code.gson:gson:2.11.0 com.google.code.gson:gson:2.14.0 --fetch --severity breaking --fail-on breaking
```

Reference: [`diff`](https://mohammadmd1383.github.io/jdx/docs/reference/diff/).

## Open the member

Take the type from the `next:` line, or the declaring type of a finding, and list it on the jar that contains it. `members` takes that jar with `--jars`. `--no-jdk` leaves the JDK out. With `--no-jdk`, a JDK supertype is not in the workspace: jdx warns `UNRESOLVED_SUPERTYPE` and omits members inherited from it. `--declared` keeps the type's own members. The default visibility is public and protected, the same surface as `diff`'s default. After `--visibility all`, pass `--access all`, or a private member the report named is filtered out of the list. Without `--jars`, a class that lives only in your jar is not on the classpath, so `members` exits 1.

```bash
jdx members net.minecraft.world.item.ItemStack --declared --kind method --jars "$NEW_MAPPED_JAR" --no-jdk
jdx members net.minecraft.world.item.ItemStack --declared --kind method --access all --jars "$NEW_MAPPED_JAR" --no-jdk
```

`signature` prints one source-style line per overload. Paste the finding's ref. A `MEMBER_REMOVED` ref is the old member: point `--jars` at the old jar. A `MEMBER_ADDED` ref is the new member: point `--jars` at the new jar. A `PARAMETER_TYPE_CHANGED` line names the old ref, and the detail after `now:` is the new ref. A `RETURN_TYPE_CHANGED` line keeps the same ref and says `old -> new` in the detail. A missing member exits 1. An under-specified name lists every overload and exits 0. A type reference exits 3.

```bash
jdx signature '<ref from the finding>' --jars "$OLD_MAPPED_JAR" --no-jdk
jdx signature '<ref after now:>' --jars "$NEW_MAPPED_JAR" --no-jdk
jdx body '<canonical ref from signature>' --jars "$NEW_MAPPED_JAR" --no-jdk
```

`body` prints one member. With a paired `-sources.jar` (a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version) the text is the real file. A mapped Minecraft jar usually has no pair, so `body` decompiles with Vineflower and the source line says `decompiled by vineflower` and `⚠ reconstructed`. `--engine javap` prints the disassembly, including the `descriptor:` line. `--max-lines 80` caps a long method. Several overloads on a bare name exit 2 and print every candidate as a canonical ref. Paste one of them. `members` and `signature` always come from bytecode, so those lines stay exact with or without sources.

With `--no-jdk` and no `--jars` and no selected workspace, `members`, `signature`, and `body` exit 4. Copying the `next:` line and adding only `--no-jdk` is that case. Pass the jar.

The same three commands, run here against Gson 2.14.0. Swap the type and the ref for the ones in your report, and swap `--coord com.google.code.gson:gson:2.14.0 --fetch` for `--jars "$NEW_MAPPED_JAR"` when the jar is a file:

```console exec max-lines=14
$ jdx members com.google.gson.Gson --declared --kind method --limit 6 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx signature 'com.google.gson.Gson#toJson(java.lang.Object)' --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx body 'com.google.gson.Gson#toJson(java.lang.Object)' --max-lines 8 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Reference: [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/), [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/). The rest of reading one class is [Reading code](reading-code.md). The mixin walk from that class is [Find a Mixin target in Minecraft](minecraft-mixin-targets.md).

## From an AI agent

`jdx setup` writes the MCP config for the agent you name. `jdx mcp` is the stdio server. `jdx_diff` is the one tool that does not need a workspace: its two arguments are the artifacts, the same specs as the CLI. The server never downloads, so a coordinate has to already be in `~/.gradle/caches` or `~/.m2`. Prime it once with the CLI and `--fetch`, or pass the jar files.

`jdx_members`, `jdx_signature`, and `jdx_body` read the workspace. Create it with the one jar you are opening. Two versions of the same classes in one workspace warn `DUPLICATE_FQN` and the first jar in classpath order is the one that is shown. `jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`.

```bash
jdx setup --agent cursor --scope project
jdx setup --agent claude-code --scope project
jdx ws create mc-new --jars "$NEW_MAPPED_JAR" --no-jdk
jdx mcp -w mc-new
```

Other agents and the tool list are in [Use with AI agents](ai-agents.md) and [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

Reference: [`setup`](https://mohammadmd1383.github.io/jdx/docs/reference/setup/), [`mcp`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp/), [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/).

## What never runs

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser and no other code from a Minecraft jar or a mod jar runs. jdx writes only inside its own cache, config, and runtime directories. `--fetch` is the network opt-in for a coordinate; a path you pass is read from disk and is not downloaded.

## Where next

- [API diff for upgrades](api-diff.md) — every rule, the severities, and `--fail-on` in CI.
- [Minecraft mods](minecraft-mods.md) — the mapped jar, mixin callers, metadata, and deprecated members.
- [Find a Mixin target in Minecraft](minecraft-mixin-targets.md) — class, type chain, method descriptor, and an injection point.
- [Reading code](reading-code.md) — `members`, `signature`, `body`, and decompilation.
- [Symbol references](symbol-references.md) — how to paste a canonical ref.
- [Classpath & workspaces](classpath.md) — `--jars` on the commands that take a classpath.
- [Command catalogue](../COMMANDS.md) — every flag.
