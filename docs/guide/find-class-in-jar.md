# Find which JAR contains a class, and list a JAR's classes, from the terminal

Find which jar contains a class, list classes in a jar, search for a class in jar files, or find class in Maven dependencies, from the terminal.

`jar tf` and `unzip -l` list every zip entry. Finding one class is a `grep` over that listing, repeated for each file under `libs/`, `mods/`, or a Maven cache, or a search in an IDE's external-libraries view. jdx reads class names from bytecode and prints the jar on the same line. Pass a `.jar`, a class directory, or a quoted glob such as `libs/*.jar` or `mods/*.jar`, a Maven coordinate, or a JDK class. jdx needs Java 21 or later. Text is the default, and every command accepts `--json`. Several matches exit 0 and every candidate is printed. Nothing matched exits 1. Install with the [one-line installer](installation.md). Every command output on this page was produced by running that command when the page was built.

Opening one class after you have its name is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). A Minecraft mod jar is the same workflow, pointed at the mapped jar your build already wrote. See [Minecraft mods](minecraft-mods.md).

## Find which jar contains a class

`jdx resolve` takes one exact name. A simple name (`JsonParser`), a binary or dotted name (`com.google.gson.Gson`), a member (`Gson#toJson`), and a package name are all accepted. The type match is case-sensitive: the binary name, the dotted name, or the simple name after the last `.` or `$`. Each row is the kind, the canonical name, and the artifact. The same simple name in two jars is two rows. A `Type#member` name lists every member of that name, each overload on its own row. An exact package name, when no type uses it, lists the artifacts that contain that package. Several candidates exit 0. A miss exits 1 and prints did-you-mean suggestions.

The artifact column is the jar's file name. It is not the Maven coordinate, and it is not an absolute path. On the JDK the same column is the module name, such as `java.base`. The text header is `search results for '…'`; `resolve` and `search` share that listing.

`--jars` takes a jar, a class directory, or a glob, and it is repeatable. Quote the glob so the shell does not expand it. An unquoted expansion exits 3. `--no-jdk` leaves the JDK out. The JDK is on the classpath until you pass that flag.

```bash
jdx resolve com.example.App --jars 'libs/*.jar' --no-jdk
jdx resolve App --jars path/to/app.jar --jars path/to/other.jar --no-jdk
```

Run here against Gson 2.14.0. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag.

```console exec max-lines=16
$ jdx resolve JsonParser --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx resolve com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

The same question against the running JDK. `--limit` caps the rows (default 50), and the footer names the flag that shows the rest:

```console exec max-lines=10
$ jdx resolve HashMap --limit 5
```

`jdx search` is the glob and the partial name. `*Http*Client` and `Json*` are globs. A bare word matches case-insensitively as a substring of the simple name, or as camel humps (`HMap` finds `HashMap`). `--regex` uses a Java regex. `--fuzzy` retries a miss within two edits. Default `--kind all` covers types, packages, and modules. It does not scan methods or fields, and a miss under that default names `--kind method`. `--kind class` keeps classes. `--in` keeps artifacts whose file name matches a glob. `--package` keeps one package. Several hits exit 0. Nothing matched exits 1.

```bash
jdx search '*Http*Client' --kind class --jars 'libs/*.jar' --no-jdk
jdx search App --kind class --in 'app*.jar' --jars 'libs/*.jar' --no-jdk
```

```console exec max-lines=14
$ jdx search 'Json*' --kind class --limit 8 --in 'gson*' --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Reference: [`resolve`](https://mohammadmd1383.github.io/jdx/docs/reference/resolve/), [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/). The match rules in more detail are in [Reading code](reading-code.md#search-the-classpath-search-resolve-ls-tree).

## List the classes in a jar

`jdx tree` prints the package tree of each artifact whose file name matches the glob. The argument is that file name, or a JDK module name. It is not a package. Omit it and the glob is `*`, every artifact on the classpath, JDK modules included. `--depth` caps nesting (default 8; `0` is the top segment only). `--counts` adds the subtree type count on each package. `--limit` caps package nodes (default 50). Nothing matched exits 1.

`jdx ls` browses packages. A glob such as `com.google.*` lists matching packages with a type count. Those rows do not name a jar. An exact package name prints `types in package '…'`, that package's type count, then one line per type with the jar file name. Omit the argument to list every package.

```bash
jdx tree 'app*' --depth 2 --counts --jars path/to/app.jar --no-jdk
jdx ls com.example --jars path/to/app.jar --no-jdk
jdx ls 'com.example.*' --jars 'libs/*.jar' --no-jdk
```

```console exec max-lines=20
$ jdx tree 'gson*' --depth 2 --counts --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx ls com.google.gson.stream --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

A flat list of class names, rather than packages, is `search` with a glob and `--kind class`. It stops at `--limit` (default 50).

```console exec max-lines=12
$ jdx search '*' --kind class --limit 8 --in 'gson*' --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Reference: [`tree`](https://mohammadmd1383.github.io/jdx/docs/reference/tree/), [`ls`](https://mohammadmd1383.github.io/jdx/docs/reference/ls/). The next step, one method of a class you have named, is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

## Maven dependencies and the JDK

A `group:artifact:version` coordinate resolves from `~/.gradle/caches` and `~/.m2` first. `--fetch` allows a download into the jdx cache, with checksum verification. Repeat `--coord` for several artifacts. `--repo` adds a repository tried before Maven Central.

Inside a Gradle or Maven project, with no classpath flags, jdx discovers project classes and the dependency jars named in the build files. It never runs Gradle or Maven to do that. When the dependency set cannot be pinned, it warns `PROJECT_DISCOVERY_FALLBACK` and still answers from what it found. See [Classpath & workspaces](classpath.md).

Listing the types in one package of a Maven artifact:

```console exec max-lines=14
$ jdx ls com.google.gson --limit 8 --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

The running JDK needs no coordinate. `jdx resolve HashMap` above is that query. `--no-jdk` is how a search of your own jars stays out of `java.*`.

## Which mod jar in a modpack contains a class

jdx answers in the names stored in the file you pass. v1 does not read mapping files and does not rename classes. Yarn, Mojmap, and obfuscated names come back exactly as they appear. Reading Tiny, SRG, or ProGuard mappings is phase-2 backlog. Point `--jars` at the jar Fabric Loom or NeoForge ModDevGradle already wrote. An obfuscated jar stays obfuscated.

```bash
jdx resolve net.minecraft.world.item.ItemStack --jars 'mods/*.jar' --no-jdk
jdx search 'ItemStack' --kind class --jars 'mods/*.jar' --no-jdk
jdx tree --depth 1 --counts --jars 'mods/*.jar' --no-jdk
```

Quote the glob. `--no-jdk` keeps `java.*` out of the tree. The docs build has no Minecraft jar, so these recipes are not executed. The same commands, run above against Gson, are the ones the site build checks. Swap `--coord` for `--jars "$MAPPED_JAR" --no-jdk` when the jar is yours. The rest of the mod workflow is [Minecraft mods](minecraft-mods.md).

## From an AI agent

The same four questions are MCP tools: `jdx_resolve`, `jdx_search`, `jdx_ls`, and `jdx_tree`. `jdx mcp` does not take a jar path or a Maven coordinate on the call, and it does not auto-discover a project. Create a workspace with the CLI, then start the server on it. A tool call may pass `workspace` to pick another stored name. The server never downloads. Prime a coordinate with `--fetch` first, or pass files you already have.

```bash
jdx ws create libs --jars 'libs/*.jar' --no-jdk
jdx mcp -w libs
jdx setup --agent claude-code --scope project
jdx setup --agent cursor --scope project
jdx setup --agent codex --scope project
```

`jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`. The config shapes are in [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

`jdx_resolve` takes `query` (`Gson`, `com.google.gson.Gson`, `Gson#toJson`) and `limit` (default 50). Several candidates are code 0. None is code 1, with did-you-mean. `jdx_search` takes `query`, `kind` (default `all`), `regex`, `fuzzy`, `in`, `package`, and `limit`. `jdx_ls` takes an optional `query` (a package glob, or an exact package) and `limit`. `jdx_tree` takes an optional `query` (an artifact-label glob; empty covers every artifact), `depth` (default 8), `counts`, and `limit`.

Reference: [`jdx_resolve`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_resolve), [`jdx_search`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_search), [`jdx_ls`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_ls), [`jdx_tree`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_tree).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs.

## Where next

- [Read and decompile a JAR](read-jar-from-terminal.md) — one method, source, or a decompilation, after you know the class.
- [META-INF/services SPI providers](https://mohammadmd1383.github.io/jdx/docs/reference/services/) — `jdx services`.
- [Read Javadoc from a jar](read-javadoc-from-jar.md) — the comment on that class or method.
- [Read a Kotlin library API from a compiled jar](kotlin-jar-api.md) — suspend, nullability, and properties from `@Metadata`.
- [Minecraft mods](minecraft-mods.md) — the mapped jar, mixin callers, and a version diff.
- [Find a Mixin target in Minecraft](minecraft-mixin-targets.md) — class, type chain, method descriptor, and an injection point.
- [See what changed between two Minecraft or mod versions](minecraft-version-diff.md) — `diff` two mapped jars, then one member.
- [An MCP server for Java and Kotlin bytecode](java-mcp-server.md) — `jdx mcp` for Claude Code, Cursor, and Codex.
- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, and `jdx ws`.
- [Command catalogue](../COMMANDS.md) — every flag.
