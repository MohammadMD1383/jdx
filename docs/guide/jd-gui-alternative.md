# JD-GUI alternative: decompile a jar without a GUI

JD-GUI alternative: a command line Java decompiler. Decompile a jar without a GUI, search classes across jars, and read one method from the shell with jdx.

JD-GUI, Recaf, and IntelliJ IDEA's decompiler view are for clicking through a jar on a desktop. jdx is the command-line tool for one class, a search across jars, or one method, in a script, in CI, over SSH, or from an AI coding agent. CFR vs JD-GUI is the same kind of choice, a command-line decompiler beside a window, and that comparison is below. jdx needs Java 21 or later, runs on Linux, macOS, and Windows, and is Apache-2.0. Text is the default, and every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate. jdx does not guess. Install with the [one-line installer](installation.md), then try the [quickstart](quickstart.md). Every command output on this page was produced by running that command when the page was built.

The sources-or-decompile walkthrough is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). Class-file signatures next to javap are [A javap alternative](javap-alternative.md). Which file holds a class is [Find which JAR contains a class](find-class-in-jar.md).

## Quick answer

Open a class, search a jar, and read one method. `--jars` takes a jar, a class directory, or a glob. Quote the glob so the shell does not expand it; an unquoted expansion exits 3. `--no-jdk` leaves the JDK out when the question is about that jar alone. Always quote a reference in a shell: `#`, `(`, and `$` are shell syntax.

```bash
jdx show com.example.App --jars path/to/app.jar --no-jdk
jdx source com.example.App --jars path/to/app.jar --no-jdk
jdx search 'App*' --kind class --jars path/to/app.jar --no-jdk
jdx body 'com.example.App#run()' --jars path/to/app.jar --no-jdk
```

`jdx show` prints the class card: kind, modifiers, supertypes, and the next command. `jdx source` opens the file, or decompiles it. `jdx search` finds a name. `jdx body` prints one method. A member name with several overloads makes `body` exit 2 and print every candidate as a reference you can paste.

`source` and `body` prefer a paired `-sources.jar`. For `foo.jar` that is a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version. With no pair, jdx decompiles with Vineflower, the default engine, and labels the result `⚠ reconstructed`. `--engine vineflower` forces that reconstruction even when sources are paired. `--engine javap` prints the bytecode of that member instead.

```bash
jdx source com.example.App --engine vineflower --jars path/to/app.jar --no-jdk
jdx body 'com.example.App#run()' --engine javap --jars path/to/app.jar --no-jdk
```

Run here against Gson 2.14.0. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag. Gson publishes a `-sources.jar`, so `body` below is the real method. Gson 2.14.0 is a multi-release jar, so jdx warns `MULTI_RELEASE_VARIANT` and still answers.

```console exec max-lines=18
$ jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx search 'Json*' --kind class --limit 8 --in 'gson*' --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
$ jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

Reference: [`show`](https://mohammadmd1383.github.io/jdx/docs/reference/show/), [`source`](https://mohammadmd1383.github.io/jdx/docs/reference/source/), [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/). The ref grammar is [Symbol references](symbol-references.md).

## When a GUI decompiler fits

Use a GUI when you are at a desktop and want to click through one jar. JD-GUI opens a jar and shows a reconstructed class in a window. Recaf is a GUI for browsing and editing bytecode. IntelliJ IDEA's decompiler view shows a class when a dependency has no sources attached, next to the editor. Expand a package, open a class, scroll to a method: that is what those tools are for.

Use jdx when the next step is a command. A script, a CI job, and an SSH session on a machine with no display can run the commands above. `jdx search` takes a quoted glob, so one query can cover `libs/*.jar`. An AI coding agent calls the same questions through `jdx mcp` instead of copying text out of a window. Wiring for Claude Code, Cursor, and Codex is [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

## CFR vs JD-GUI

CFR is a command-line Java decompiler. It prints reconstructed Java for a class file or a jar. JD-GUI is the desktop window people open for that reading. jdx is on the command-line side with CFR, and the answer is one named question rather than a dump of the file. `jdx show` prints the class card. `jdx search` finds a name across jars. `jdx source` returns one class. `jdx body` returns one method.

When a `-sources.jar` is paired, `source` and `body` return that source. When it is not, they decompile with Vineflower and label the text `⚠ reconstructed`, naming the engine and the jar. `--engine javap` stays on bytecode for that one member. The ladder, with executed Gson and JDK examples, is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). Signatures, callers, and implementors next to javap are [A javap alternative](javap-alternative.md).

CFR is the tool for its reconstruction of the classes you point it at. JD-GUI, Recaf, and the IDE view are the tools for clicking through a reconstruction. jdx is the tool for one class, one method, or a search, in a shell or from an agent.

## Task by task

| Task | GUI approach | jdx |
|---|---|---|
| Open a class | Open the jar in JD-GUI, Recaf, or IntelliJ and select the class | `jdx show com.example.App --jars app.jar --no-jdk` |
| Read the class | Scroll the reconstructed class in that window | `jdx source com.example.App --jars app.jar --no-jdk` |
| Search a jar | Search in the open jar, or use the IDE's project search | `jdx search 'App*' --kind class --jars app.jar --no-jdk` |
| Read one method | Scroll to the method in the decompiled class | `jdx body 'com.example.App#run()' --jars app.jar --no-jdk` |
| Search many jars | Open each jar, or search the IDE project | `jdx search 'App*' --kind class --jars 'libs/*.jar' --no-jdk` |
| No sources attached | The window shows its reconstruction | `jdx source com.example.App --jars app.jar --no-jdk` |
| CI, SSH, or no display | A desktop window needs a display | The same commands in a shell |
| Hand it to an agent | Copy text out of the window | `jdx mcp`, or the CLI |

Quote `'libs/*.jar'`. An unquoted glob that expands to several paths exits 3.

## Decompile a jar without a GUI

`jdx source` prints one class. `jdx body` prints one method. The text is the paired source when a `-sources.jar` is present, and a Vineflower reconstruction when it is not. Vineflower is the default decompiler. `--engine vineflower` forces a reconstruction even when sources are paired. `--engine javap` prints raw bytecode. When Vineflower returns only a `$VF: Could not decompile…` stub, the default ladder falls through to `javap` instead of serving the stub.

```console exec max-lines=14
$ jdx source com.google.gson.JsonNull --lines 1:12 --engine vineflower --coord com.google.code.gson:gson:2.14.0 --fetch --no-jdk
```

Gson 2.14.0 ships sources, so the command above forces a reconstruction of `JsonNull`. The real source of that class is on [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs.

On a Minecraft jar, jdx reads the names stored in the file. It does not remap. Pass the jar Fabric Loom or NeoForge ModDevGradle already wrote with `--jars`. `jdx diff` only makes sense inside one mapping namespace: both sides must already use the same names. See [Minecraft mods](minecraft-mods.md).

## From an AI coding agent

`jdx mcp` speaks MCP on stdin and stdout. Each query is a typed tool, including `jdx_show`, `jdx_source`, `jdx_body`, and `jdx_search`. The server never downloads. Pass `jars` or `coord` on the call, or prime a missing coordinate with `--fetch` on the CLI first. `discover` true reads a Gradle or Maven project from the server's working directory when no workspace is stored.

```bash
jdx setup --agent claude-code --scope project
jdx setup --agent cursor --scope project
jdx setup --agent codex --scope project
jdx mcp
```

`jdx setup` writes `args` as `mcp` only. The config shapes and the tool list are in [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

## FAQ

### What is a command-line alternative to JD-GUI?

jdx. `jdx show com.example.App --jars app.jar --no-jdk` opens a class, `jdx source com.example.App --jars app.jar --no-jdk` reads or decompiles it, and `jdx body 'com.example.App#run()' --jars app.jar --no-jdk` prints one method. JD-GUI remains a good fit when you want to click through one jar in a window.

### How do I decompile a jar without a GUI?

Run `jdx source com.example.App --jars app.jar --no-jdk`. When a sibling `-sources.jar` is paired, the text is the real source. When it is not, jdx decompiles with Vineflower and labels the result reconstructed. `jdx body 'com.example.App#run()' --jars app.jar --no-jdk` prints one method the same way. jdx returns that class or that method.

### How does jdx compare with CFR and JD-GUI?

JD-GUI opens a jar in a desktop window. CFR is a command-line decompiler that prints reconstructed Java for a class file or a jar. jdx is a command-line tool too. It prefers paired sources, decompiles with Vineflower when those sources are absent, and can show one class card or search many jars in one command. Use CFR for its reconstruction of the classes you point it at. Use JD-GUI to read that kind of reconstruction in a window. Use jdx for one named answer in a shell, in CI, or from an agent.

### How do I search a class across jars from the terminal?

`jdx search 'App*' --kind class --jars 'libs/*.jar' --no-jdk` matches a glob across every jar in that glob. Quote the glob. `jdx resolve com.example.App --jars 'libs/*.jar' --no-jdk` takes an exact name and prints the jar file name on the same line. Several matches exit 0. Nothing matched exits 1. Finding a class this way is [Find which JAR contains a class](find-class-in-jar.md).

### Can an AI coding agent decompile a class without a GUI?

Yes. `jdx mcp` serves typed tools, including `jdx_source` and `jdx_body`, over stdio. `jdx setup --agent cursor --scope project` writes that server into Cursor, and the same command covers Claude Code and Codex. The server does not download. Prime a coordinate with `--fetch` on the CLI first. The config is [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

## Where next

- [Read and decompile a JAR from the terminal](read-jar-from-terminal.md) — list a jar, open one method, decompile when there are no sources.
- [A javap alternative](javap-alternative.md) — class-file signatures, callers, implementors, and where javap still fits.
- [Find which JAR contains a class](find-class-in-jar.md) — which file holds a class, across a glob or a coordinate.
- [An MCP server for Java and Kotlin bytecode](java-mcp-server.md) — `jdx mcp` for Claude Code, Cursor, and Codex.
- [Minecraft mods](minecraft-mods.md) — the mapped jar. jdx does not remap.
- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, and `--fetch`.
- [Command catalogue](../COMMANDS.md) — every flag.
