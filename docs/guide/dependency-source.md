# View source code of a Maven dependency

View source code of a Maven dependency from the terminal with jdx. Read Gradle dependency source the same way, and see library source without an IDE.

jdx prints one signature, one class, or one method. A paired `-sources.jar` is the real file. With no pair, the method is decompiled and labelled. Supplying every classpath flag is [Classpath & workspaces](classpath.md). Opening a jar you already have, and the decompile ladder, is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). The comment on a symbol is [Read Javadoc from a jar](read-javadoc-from-jar.md). A desktop window for the same reading is [A JD-GUI alternative](jd-gui-alternative.md). Install with the [one-line installer](installation.md). Java 21 or later. Text is the default, and every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate. jdx does not guess. Every command output on this page was produced by running that command when the page was built.

## Quick answer

From the project directory, with no classpath flags, jdx uses the dependency jars it can name from that directory's build files. It does not run Maven or Gradle, and it does not build the resolved classpath those tools would. Outside a project, name the coordinate or the jar file.

```bash
cd my-app
jdx signature 'com.example.lib.Client#connect()'
jdx source com.example.lib.Client
jdx body 'com.example.lib.Client#connect()'
```

```bash
jdx signature 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
jdx source com.google.gson.JsonNull --lines 17:28 --coord com.google.code.gson:gson:2.14.0 --fetch
jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

`--coord` resolves from the jdx fetch cache (`<cache-dir>/m2`, `~/.cache/jdx/m2` on Linux), then `~/.gradle/caches`, then `~/.m2`. `--fetch` allows a download when the coordinate is missing, and downloads the `-sources.jar` when the repository publishes one. Without `--fetch`, a missing coordinate exits 5 and the message names `--fetch`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag.

Run here against Gson 2.14.0. `signature` is the declaration. `source --lines 17:28` is that window of the class. Gson 2.14.0 is a multi-release jar, so jdx warns `MULTI_RELEASE_VARIANT` and still answers.

```console exec max-lines=16
$ jdx signature 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

```console exec max-lines=20
$ jdx source com.google.gson.JsonNull --lines 17:28 --coord com.google.code.gson:gson:2.14.0 --fetch
```

`jdx signature` comes from bytecode, so the line is the same with or without a sources jar. `jdx source` prints the class, or a `--lines A:B` window. `jdx body` prints one method. For `foo.jar`, the pair is a sibling `foo-sources.jar`, or the same file name in a sibling directory under that version in the Gradle or Maven cache. A binary jar that itself contains `.java` or `.kt` entries is its own pair. With no pair, `source` and `body` decompile with Vineflower and label the result `⚠ reconstructed`. `--engine javap` prints that member's bytecode. `--engine vineflower` forces a reconstruction even when sources are paired. When sources and bytecode disagree, jdx warns `SOURCES_VERSION_MISMATCH` and bytecode wins for which members exist. The rest of that ladder is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

Always quote a reference in a shell: `#`, `(`, and `$` are shell syntax. A method name with several overloads makes `body` exit 2. jdx prints every candidate as a reference you can paste. Paste one of them. Do not retype parameter types by hand.

Reference: [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/). The ref grammar is [Symbol references](symbol-references.md).

## A Maven project

Run the commands from the project, or from any subdirectory. jdx walks upward to the nearest directory with a build file. In one directory, `settings.gradle(.kts)` and `build.gradle(.kts)` win over `pom.xml`. The marker list is [Classpath & workspaces](classpath.md#project-auto-discovery).

Coordinates come from the `pom.xml` in that directory. A parent POM is not merged, and other modules are not read. Every `<dependency>` whose `<version>` is literal text is kept. A version written as `${…}` is skipped. Versions that live only in a parent or a BOM are not filled in. Transitive dependencies are not walked.

Those coordinates are looked up as jars in `~/.m2/repository` and in `~/.gradle/caches/modules-2/files-2.1`. A `-sources.jar` or `-javadoc.jar` is not a classpath root. The sources jar is paired with the binary when you ask for source: in the Maven layout that is the sibling `name-version-sources.jar`. The jdx fetch cache is not on this path. A jar you previously downloaded only with `--fetch` is visible to `--coord`, and it is not visible to discovery until it is also in `~/.m2` or the Gradle cache.

`target/classes` is included when that directory exists. Those are compiled classes. jdx does not read `src/main/java`.

```bash
cd my-app
jdx signature 'com.example.lib.Client#connect()'
jdx source com.example.lib.Client
jdx body 'com.example.lib.Client#connect()'
```

When that pom names no usable coordinates, jdx warns `PROJECT_DISCOVERY_FALLBACK` and answers from `target/classes` only. The message says to pass `--jars` for dependencies. A class that exists only in a dependency then exits 1. A coordinate that is written down, whose jar is in neither cache, is omitted. There is no download on the discovery path. Name the artifact yourself:

```bash
jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

The workspace row of `jdx doctor` names the project root and the nearest build file. It does not list the jars.

## A Gradle project

The same walk. The nearest directory with `settings.gradle(.kts)` or `build.gradle(.kts)` is the project. Coordinates are `group:artifact:version` strings in `gradle.lockfile`, `build.gradle(.kts)`, and `settings.gradle(.kts)` in that directory. A `pom.xml` beside those files is read too. `libs.versions.toml` is not read. An alias such as `libs.gson`, or a dependency written as separate group, name, and version fields, is not a coordinate. A lockfile that lives in a parent directory is not read when a submodule's own build file is the nearest marker.

Jars are looked up under `~/.gradle/caches/modules-2/files-2.1/<group>/<name>/<version>/` and in `~/.m2/repository`. Sources and javadoc jars are left off the classpath. The sources file is paired by name: `<binary>-sources.jar` beside the binary, or the same file name in a sibling directory under that version. The Gradle cache keeps each file in its own hash directory, and the pair walk covers those siblings.

`build/classes/java/main` and `build/classes/kotlin/main` are included when those directories exist. A plain `build/classes` is used when the language-specific directories are absent. jdx does not read `src/main/java` or `src/main/kotlin`.

When no coordinate string is found, jdx warns `PROJECT_DISCOVERY_FALLBACK` and answers from those class directories only. Pass `--coord` or `--jars` for the dependency you mean.

```bash
cd my-app
jdx signature 'com.example.lib.Client#connect()'
jdx source com.example.lib.Client
```

```bash
jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

`jdx signature` reads a Kotlin declaration from `@Metadata`. Reading a `.kt` file needs a one-time `jdx kotlin install`, which is one of the few commands that uses the network. Suspend, nullability, and file facades are [Read a Kotlin library API from a compiled jar](kotlin-jar-api.md).

On a Minecraft jar, jdx reads the names stored in the file. It does not remap. Pass the jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars`. `jdx diff` compares two artifacts that already share one mapping namespace. A Yarn jar against a Mojmap jar looks like nearly every member was removed and re-added. See [Minecraft mods](minecraft-mods.md).

## One jar from the cache

Pass the binary jar you already have. `--no-jdk` leaves the JDK out when the question is about that jar alone. `--jars` reads the file you named and does not download it.

```bash
jdx source com.google.gson.Gson --jars "$HOME/.m2/repository/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar" --no-jdk
jdx signature 'com.google.gson.Gson#toJson(Object)' --jars "$HOME/.m2/repository/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar" --no-jdk
jdx body 'com.google.gson.Gson#toJson(Object)' --jars "$HOME/.m2/repository/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar" --no-jdk
```

The sibling `gson-2.14.0-sources.jar` in that directory is paired. Pass the binary. The sources jar has no class files, so it is the wrong file to pass as the only `--jars`.

The Gradle cache directory name includes a content hash, so it is not a stable path to type:

`~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.14.0/<hash>/gson-2.14.0.jar`

`--coord` finds that binary, and pairs `gson-2.14.0-sources.jar` from a sibling hash directory, with no download when the files are already cached:

```bash
jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --no-jdk
jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --no-jdk
```

A glob is one `--jars` value. Quote it so the shell does not expand it. An unquoted expansion exits 3.

```bash
jdx source com.example.App --jars 'libs/*.jar' --no-jdk
```

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs. jdx writes only inside its own cache, config, and runtime directories.

## From an AI coding agent

`jdx mcp` speaks MCP on stdin and stdout. The tools for this page are `jdx_signature`, `jdx_source`, and `jdx_body`. The server does not auto-discover a Gradle or Maven project. A call does not take a jar path or a Maven coordinate. The server never downloads. Create the workspace with the CLI, and prime a missing coordinate with `--fetch` first.

```bash
jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
jdx ws create gson --coord com.google.code.gson:gson:2.14.0
jdx mcp -w gson
jdx setup --agent cursor --scope project
jdx setup --agent claude-code --scope project
jdx setup --agent codex --scope project
```

`jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`. A tool call may pass `workspace` to pick another stored name. `--scope project` writes into the current checkout. `--scope system` writes the user-global file. The config shapes are in [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

`jdx_source` takes `query` (a type, such as `com.google.gson.Gson`), `lines` (a 1-based window such as `17:28`), `around`, `context`, `maxLines` (default 200), and `engine`. `lines` and `around` together are code 3. `jdx_body` takes a member `query`. `jdx_signature` prints one line per overload. An ambiguous member is code 2 and lists every candidate. No stored workspace is code 4.

Reference: [`jdx_source`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_source), [`jdx_body`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_body), [`jdx_signature`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_signature).

## FAQ

### How do I view the source code of a Maven dependency from the terminal?

From the project directory, `jdx source com.example.lib.Client` reads that class when the nearest `pom.xml` names the coordinate with a literal version and the jar is already in `~/.m2` or the Gradle cache. `jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch` names the artifact directly. `--fetch` is what allows a download, including the `-sources.jar`. `jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch` prints one method. Install jdx with the [one-line installer](installation.md). Java 21 or later is required.

### How do I read a Gradle dependency's source from the terminal?

Run `jdx source com.example.lib.Client` from the project. jdx uses `group:artifact:version` strings in that directory's `gradle.lockfile` or Gradle build scripts, and opens the jar from `~/.gradle/caches`. It does not run Gradle. A version-catalog alias, with no literal coordinate and no lockfile in that directory, warns `PROJECT_DISCOVERY_FALLBACK` and leaves the dependency out. `jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch` names the artifact you mean.

### How do I see a library's source without an IDE?

`jdx signature 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch` prints the declaration. `jdx source com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch` prints the class. `jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch` prints one method. With a paired `-sources.jar` the text is the real source. With no pair, jdx decompiles with Vineflower and labels the result reconstructed. No IDE window is required.

### What if the dependency has no sources jar?

`jdx source` and `jdx body` decompile with Vineflower and label the text `⚠ reconstructed`. `jdx signature` and `jdx members` still print the declaration from bytecode. `jdx doc` reads the comment from sources and exits 1 when no sources are paired. The comment case is [Read Javadoc from a jar](read-javadoc-from-jar.md). The decompile ladder is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

### Can an AI coding agent read a dependency's source through MCP?

Yes. `jdx_source`, `jdx_body`, and `jdx_signature` answer the same questions as the CLI. `jdx mcp` does not auto-discover a project and does not download. Prime the coordinate with `--fetch`, run `jdx ws create gson --coord com.google.code.gson:gson:2.14.0`, then `jdx setup --agent cursor --scope project`. The same setup command covers Claude Code and Codex. The config is [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

## Where next

- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, `--fetch`, and what auto-discovery reads.
- [Read and decompile a JAR from the terminal](read-jar-from-terminal.md) — one method, source, or a decompilation.
- [Read Javadoc from a jar](read-javadoc-from-jar.md) — the comment, from a paired `-sources.jar`.
- [A JD-GUI alternative](jd-gui-alternative.md) — a GUI decompiler versus these commands.
- [Reading code](reading-code.md) — `show`, `members`, `signature`, `doc`, and `body`.
- [Symbol references](symbol-references.md) — how to paste a canonical ref.
- [Read a Kotlin library API from a compiled jar](kotlin-jar-api.md) — suspend, nullability, and properties from `@Metadata`.
- [An MCP server for Java and Kotlin bytecode](java-mcp-server.md) — `jdx mcp` for Claude Code, Cursor, and Codex.
- [Minecraft mods](minecraft-mods.md) — the mapped jar. jdx does not remap.
- [Installation](installation.md) — Linux, macOS, Windows, and offline installs.
- [Command catalogue](../COMMANDS.md) — every flag.
