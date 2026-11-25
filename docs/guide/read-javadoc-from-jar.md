# Read Javadoc from a jar in the terminal

Read the Javadoc of a class or method in a jar, a Maven dependency, or the JDK from the terminal. jdx doc prints that comment for you and for AI agents.

The comment usually lives in an HTML tree, or behind a hover in an IDE. `jdx doc` prints it as plain text, plus the file and the line range it came from. HTML from a known set of tags is stripped. Block tags such as `@param` and `@return` stay. An inline tag is unwrapped when its closing `}` is on the same line. jdx needs Java 21 or later, runs on Linux, macOS, and Windows, and is Apache-2.0. Text is the default, and every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate. jdx does not guess. Install with the [one-line installer](installation.md). Every command output on this page was produced by running that command when the page was built.

Finding which file holds the class is [Find which JAR contains a class](find-class-in-jar.md). Opening the method body is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

## Read the Javadoc of a class

`jdx doc` takes a type or a member reference. A type prints that class's own comment. The first line is the canonical reference. The `source:` line names the sources file, the path inside it, and the line range. The full answer closes with `next: jdx show` and the declaring type.

Run here against Gson 2.14.0. Gson's comments wrap some `{@link}` tags across lines, so those tags stay partly written in the text. A tag that fits on one line is unwrapped. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag. `--fetch` also downloads the `-sources.jar` when the repository publishes one. A repository with no `-sources.jar` still returns the binary.

```console exec max-lines=16
$ jdx doc com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
```

The class comment is long. `--max-lines` caps the text (default 200) and the footer names the limit that shows the rest. `--json` carries the same text, the line range, and the provenance. Gson 2.14.0 is a multi-release jar, so jdx warns `MULTI_RELEASE_VARIANT` and still prints the comment.

Reference: [`doc`](https://mohammadmd1383.github.io/jdx/docs/reference/doc/).

## Read the Javadoc of one method

`toJson` has several overloads. jdx will not pick one. It exits 2 and prints every candidate as a reference you can paste:

```console exec
$ jdx doc 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0 --fetch   # exit 2
```

Always quote references in a shell: `#`, `(`, and `$` are shell syntax. Paste one of those refs into the next command.

```console exec max-lines=20
$ jdx doc 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

A method with no comment of its own falls back to the nearest documenting supertype. The `source:` line then names that supertype as `(inherited from …)`, and the file and line range are the supertype's comment. `{@inheritDoc}` inside a comment that does exist is filled from that supertype's first paragraph. Fields and constructors do not inherit a comment. A type does not inherit its superclass's class comment.

`java.util.Collections$UnmodifiableMap` is a nested JDK class, so the `$` is part of the reference. Its `get` method has no comment of its own. The line below names `java.util.Map`:

```console exec max-lines=8
$ jdx doc 'java.util.Collections$UnmodifiableMap#get(Object)'
```

`--no-inherited` keeps only the comment written on that exact symbol. `--inherited` is the default and is accepted for explicitness. Passing `--inherited` and `--no-inherited` together exits 3. `--raw` prints the comment verbatim, delimiters already removed and the leading stars kept, instead of the rendered plain text.

The reference grammar is [Symbol references](symbol-references.md). A package or module reference exits 3. A missing symbol exits 1 and prints did-you-mean suggestions.

## A JDK class

The running JDK is on the classpath with no coordinate and no `--fetch`. When that JDK ships `lib/src.zip`, the comment is read from it. The sources file on the `source:` line is `src.zip`.

```console exec max-lines=16
$ jdx doc 'java.util.Map#get(Object)'
```

A JDK distribution that omits `lib/src.zip` exits 1. The message names the class and the module (`java.base` for `java.util.Map`) and says decompilation is not yet implemented. A library jar with no paired sources prints the same kind of message. That case is next.

`--no-jdk` leaves the JDK out. With `--no-jdk`, no `--jars`, no `--coord`, and no selected workspace, `jdx doc` exits 4.

## show, members, and signature

`jdx show` prints the class card: kind, modifiers, supertypes, and member counts. `jdx members` lists what you can call. `jdx members --with-doc` adds the first sentence of each member's javadoc when a sources comment exists. `jdx signature` prints one declaration line per overload. Structure comes from bytecode, so that list is the same with or without a sources jar. `jdx body` prints the method. `jdx body --with-doc` places the javadoc beside that body when sources have a comment.

```console exec max-lines=16
$ jdx members com.google.gson.Gson --declared --kind method --grep '^toJson$' --with-doc --coord com.google.code.gson:gson:2.14.0 --fetch
$ jdx signature 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch
```

Reference: [`show`](https://mohammadmd1383.github.io/jdx/docs/reference/show/), [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/), [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/), [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/). The rest of reading one class is [Reading code](reading-code.md). The method body, with or without a sources jar, is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md).

## Where the text comes from

Javadoc comments are not stored in the class file. `jdx doc` reads them from paired sources.

For `foo.jar`, the pair is a sibling `foo-sources.jar`, or the same file name in the Gradle or Maven cache layout under that version. A sources jar with a different file name is not paired. A binary jar that itself contains `.java` or `.kt` entries is its own pair. The JDK pair is `lib/src.zip`.

A `group:artifact:version` coordinate resolves from `~/.gradle/caches` and `~/.m2` first. `--fetch` downloads a missing artifact into the jdx cache (`<cache-dir>/m2`, `~/.cache/jdx/m2` on Linux) with checksum verification, and downloads the `-sources.jar` alongside when the repository publishes one. `--repo` adds a repository tried before Maven Central. The CLI contacts the network only when you pass `--fetch`, or when you run `jdx upgrade` or `jdx kotlin install`. A coordinate that is not in the local caches, and was not given `--fetch`, exits 5 and the message names `--fetch`.

A file you already have is `--jars` (a jar, a class directory, or a glob). Quote the glob so the shell does not expand it. An unquoted expansion exits 3.

```bash
jdx doc com.example.App --jars path/to/app.jar --no-jdk
jdx doc 'com.example.App#run()' --jars 'libs/*.jar' --no-jdk
```

When the class is in that jar and no sources are paired, `jdx doc` exits 1. The text names the class and the jar file:

```text
not found: com.example.App
no sources for com.example.App in app.jar (decompilation not yet implemented, T-026)
```

The parenthetical is the tool's own words. A reconstructed class file has no javadoc comment in it, so `jdx doc` stops there. `jdx body` and `jdx source` on the same reference decompile with Vineflower and label the result `⚠ reconstructed`. `jdx members` and `jdx signature` still answer from bytecode. `jdx members --with-doc` leaves the sentence off when there is no sources comment.

Maven also publishes a `*-javadoc.jar` of HTML pages. `jdx doc` reads the comment in the paired `-sources.jar`, which is the text those pages were generated from.

A member that exists in bytecode and has no counterpart in the sources exits 1, and the message names `SOURCES_VERSION_MISMATCH`. When the comment is found and the sources jar disagrees about other members, the comment is still printed and jdx warns `SOURCES_VERSION_MISMATCH`. Bytecode stays the authority for which members exist.

Kotlin binaries still answer `jdx members` and `jdx signature` from `@Metadata`. KDoc is read from `.kt` sources, which needs a one-time `jdx kotlin install`. A class whose sources are Kotlin only, with that sidecar absent, exits 1 and the message names `jdx kotlin install`. Suspend, nullability, and file facades are [Read a Kotlin library API from a compiled jar](kotlin-jar-api.md).

Inside a Gradle or Maven project, with no classpath flags, jdx discovers project classes and the dependency jars named in the build files. It never runs Gradle or Maven to do that. See [Classpath & workspaces](classpath.md). The source of that dependency, one class or one method, is [View source code of a Maven dependency](dependency-source.md).

## From an AI agent

The same question is the MCP tool `jdx_doc`. `jdx mcp` does not take a jar path or a Maven coordinate on the call, and it does not auto-discover a project. Create a workspace with the CLI, then start the server on it. A tool call may pass `workspace` to pick another stored name. The server never downloads. Prime a coordinate with `--fetch` first, or pass files you already have.

```bash
jdx doc com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
jdx ws create gson --coord com.google.code.gson:gson:2.14.0
jdx mcp -w gson
jdx setup --agent claude-code --scope project
jdx setup --agent cursor --scope project
jdx setup --agent codex --scope project
```

`jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`. The config shapes are in [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

`jdx_doc` takes `query` (a type or a member, such as `com.google.gson.Gson` or `com.google.gson.Gson#toJson(Object)`), `workspace`, `inherited` (default true), `no-inherited`, `raw`, and `maxLines` (default 200). Setting `inherited` and `no-inherited` together is code 3. An ambiguous member is code 2 and lists every candidate. No paired sources is code 1. `jdx serve` answers the same JSON at `GET /v1/doc?query=…`.

Reference: [`jdx_doc`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_doc), [`doc`](https://mohammadmd1383.github.io/jdx/docs/reference/doc/).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs. jdx writes only inside its own cache, config, and runtime directories.

## FAQ

### How do I read Javadoc from a jar in the terminal?

`jdx doc com.example.App --jars path/to/app.jar` prints that class's javadoc. `jdx doc 'com.example.App#run(String)'` prints one method. The text comes from a paired `-sources.jar`. Install jdx with the [one-line installer](installation.md). Java 21 or later is required.

### How do I view Javadoc for a Maven dependency from the command line?

`jdx doc com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch` resolves the coordinate from `~/.gradle/caches` and `~/.m2` first. `--fetch` is what allows a download, including the `-sources.jar`. Without `--fetch`, a coordinate that is not already cached exits 5 and the message names `--fetch`.

### How do I read Gson Javadoc for toJson without an IDE?

`jdx doc 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0 --fetch` prints that overload. The bare name `com.google.gson.Gson#toJson` exits 2 and lists every overload.

### What happens when a library jar has no sources jar?

`jdx doc` exits 1 and reports `no sources for … (decompilation not yet implemented, T-026)`. `jdx members` and `jdx signature` still print the declaration from bytecode. `jdx body` decompiles the method with Vineflower and labels it `⚠ reconstructed`. The javadoc comment itself is only in the sources.

### Can an AI agent read Javadoc from a library jar?

`jdx_doc` is the MCP tool, and its answer matches `jdx doc`. `jdx setup --agent cursor --scope project` wires the server for Cursor. The same command takes `claude-code` and `codex`. The server does not download. Run the coordinate once with `--fetch`, or create a workspace with `--jars`, before the agent calls the tool.

## Where next

- [`jdx doc`](https://mohammadmd1383.github.io/jdx/docs/reference/doc/) — every flag, generated from the binary.
- [Find which JAR contains a class](find-class-in-jar.md) — which file holds a class, and the classes in one jar.
- [Read and decompile a JAR from the terminal](read-jar-from-terminal.md) — one method, source, or a decompilation.
- [View source code of a Maven dependency](dependency-source.md) — a Maven or Gradle project's dependencies, from the terminal.
- [Read a Kotlin library API from a compiled jar](kotlin-jar-api.md) — suspend, nullability, and properties from `@Metadata`.
- [Installation](installation.md) — Linux, macOS, Windows, and offline installs.
- [Reading code](reading-code.md) — `show`, `members`, `signature`, `doc`, and `body`.
- [Symbol references](symbol-references.md) — how to paste a canonical ref.
- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, and `jdx ws`.
- [Output, JSON & exit codes](output.md#exit-codes) — the exit-code table.
- [Command catalogue](../COMMANDS.md) — every flag.
