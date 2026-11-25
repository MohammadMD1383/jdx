# Read a Kotlin library API from a compiled jar

Read a Kotlin library API from a compiled jar: suspend, nullability, properties, default arguments, and class-file signatures from @Metadata.

A Java decompiler or `javap` shows the JVM projection of a Kotlin class file. A `suspend` function gains a trailing `Continuation` parameter and often returns `Object`. A default argument becomes a `$default` synthetic. A property becomes a getter. Nullability is erased. A file of top-level functions becomes a `*Kt` class, and a multi-file facade splits into part classes such as `BuildersKt__BuildersKt`. jdx reads the Kotlin `@Metadata` annotation and prints the Kotlin declaration. Nullability on parameters, returns, and properties is that rendering (v1.7.0, #13). File facades and multi-file parts use the same view (#12). jdx needs Java 21 or later, runs on Linux, macOS, and Windows, and is Apache-2.0. Text is the default, and every command accepts `--json`. An ambiguous reference exits 2 and lists every candidate. jdx does not guess. Install with the [one-line installer](installation.md). Every command output on this page was produced by running that command when the page was built.

Finding which file holds the class is [Find which JAR contains a class](find-class-in-jar.md). One method of a Java class, or a decompilation when you want the bytecode, is [Read and decompile a JAR from the terminal](read-jar-from-terminal.md). The comment on a symbol is [Read Javadoc from a jar](read-javadoc-from-jar.md).

## Open the class

`jdx show` prints the class card: kind, modifiers, supertypes, and member counts. A Kotlin class has a `kotlin` line under the supertypes. The `next:` line is the following command.

The examples use `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0`, the JVM class jar of kotlinx-coroutines-core. `--fetch` allows a download when the coordinate is not already in `~/.gradle/caches` or `~/.m2`. Each `--coord` example on this page passes it, so any one of them can be copied on its own. A coordinate is not downloaded without that flag. The coordinate without `-jvm` resolves an artifact that does not contain these classes.

`BuildersKt` is the multi-file facade. `BuildersKt__Builders_commonKt` is one part class inside the same jar.

```console exec
$ jdx show kotlinx.coroutines.BuildersKt --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
$ jdx show kotlinx.coroutines.BuildersKt__Builders_commonKt --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
```

kotlinx-coroutines-core-jvm 1.11.0 is a multi-release jar, so jdx warns `MULTI_RELEASE_VARIANT` and still prints the card. The same warning appears on the other commands on this page.

Reference: [`show`](https://mohammadmd1383.github.io/jdx/docs/reference/show/).

## Suspend, and the Continuation parameter

`jdx signature` prints one declaration line per matching member. The default `--view` is `kotlin`. `Flow.collect` is `suspend`. The Kotlin line has no `Continuation` parameter.

```console exec
$ jdx signature 'kotlinx.coroutines.flow.FlowKt#collect' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
$ jdx signature 'kotlinx.coroutines.flow.FlowKt#collect' --view jvm --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
```

`--view jvm` is the class-file signature: the return type is `java.lang.Object`, and the last parameter is `kotlin.coroutines.Continuation`. That is the method a stack trace and `javap` name. The Kotlin view is what a Kotlin caller writes.

Always quote references in a shell: `#`, `(`, and `$` are shell syntax. The reference grammar is [Symbol references](symbol-references.md).

Reference: [`signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/).

## Nullability and properties

`Job.parent` is a property. The Kotlin signature carries the `?` from `@Metadata`. The JVM getter is `getParent`, and its return type has no `?`.

```console exec
$ jdx signature 'kotlinx.coroutines.Job#parent' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
$ jdx signature 'kotlinx.coroutines.Job#getParent' --view jvm --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
```

The `?` is display text on the declaration line. The canonical reference on the first line has no `?`. Pasting a `?` into a later reference is accepted, and the parsed ref drops it. `jdx members --kind property` lists properties. `--kind method` lists methods. The default `--kind` is `all`.

A metadata type with no Java spelling stays the JVM type. Names such as `String` and `List` are spelled. `kotlin.sequences.Sequence` is not, so a property of that type stays the erased JVM type. `Job?` spells, because `kotlinx.coroutines.Job` has a Java name.

## Default arguments and `@JvmName`

A parameter `@Metadata` records as defaulted is marked `= ...`. On `runBlocking` that parameter is `context`.

```console exec
$ jdx signature 'kotlinx.coroutines.BuildersKt#runBlocking' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
```

The compiler also emits a synthetic for the defaulted call. It is hidden in the Kotlin view. `--view jvm --include-synthetic` prints it. `--grep` is a Java regex matched against the member name. `^launch` matches `launch` and `launch$default`.

```console exec
$ jdx members kotlinx.coroutines.BuildersKt --declared --kind method --include-synthetic --view jvm --grep '^launch' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
```

`@JvmName` is the other rename. Querying the JVM name `debounceDuration` still resolves. The Kotlin view prints `debounce`. `--view jvm` prints `debounceDuration`.

```console exec
$ jdx signature 'kotlinx.coroutines.flow.FlowKt#debounceDuration' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
$ jdx signature 'kotlinx.coroutines.flow.FlowKt#debounceDuration' --view jvm --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
```

Reference: [`members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/). `--declared` is only the type's own members. Inherited members are the default.

## File facades and multi-file parts

Top-level Kotlin functions compile to a `*Kt` class. A multi-file facade is an umbrella class plus one part class per file. `jdx search` lists the part classes in this jar:

```console exec max-lines=12
$ jdx search 'BuildersKt__*' --kind class --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch --no-jdk
```

`runBlocking` is declared on the part `BuildersKt__BuildersKt`. The umbrella `BuildersKt` lists it too. jdx merges each part's metadata into the umbrella when the jar is open, so the name a Kotlin caller uses and the part name from a stack trace both answer.

```console exec
$ jdx members kotlinx.coroutines.BuildersKt --declared --kind method --grep '^runBlocking$' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
$ jdx members kotlinx.coroutines.BuildersKt__BuildersKt --declared --kind method --grep '^runBlocking$' --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
```

`^runBlocking$` keeps `runBlockingImpl` and `runBlockingK` out of the list. Corrupt or missing metadata falls back to the JVM projection for that class. The command still prints the members.

Reference: [`search`](https://mohammadmd1383.github.io/jdx/docs/reference/search/).

## One method body

`jdx body` prints one member. `members` and `signature` come from bytecode either way. The body is real source when a paired sources file can be sliced, and a reconstruction when it cannot.

This coordinate publishes no `-sources.jar` that jdx pairs. The facade method is labelled `⚠ reconstructed`, and the provenance names the engine. Here the engine is `javap`: the line shows the class-file body, a single call into `BuildersKt__Builders_commonKt.launch`.

```console exec max-lines=20
$ jdx body 'kotlinx.coroutines.BuildersKt#launch(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2)' --max-lines 20 --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
```

`jdx doc` on the same jar exits 1. There is no paired sources file, and a reconstructed class file has no KDoc to read. The parenthetical is the tool's own words:

```console exec
$ jdx doc kotlinx.coroutines.Job --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch   # exit 1
```

Reference: [`body`](https://mohammadmd1383.github.io/jdx/docs/reference/body/), [`doc`](https://mohammadmd1383.github.io/jdx/docs/reference/doc/).

## Kotlin sources need one install

Slicing a `.kt` member and reading KDoc use the Kotlin compiler parser. It is not inside the jdx jar. `jdx kotlin install` downloads the pinned compiler jars, checks SHA-1, and stores them under `<cache-dir>/kotlin/` (`~/.cache/jdx/kotlin/` on Linux). Jars already present are skipped, so the network is used for that download and not on later queries. `--force` re-downloads. `jdx doctor` reports whether the sidecar is installed.

```bash
jdx kotlin install
```

`jdx members` and `jdx signature` do not need it. They read `@Metadata` from the class file.

`kotlin-stdlib` 2.1.21 does publish a `-sources.jar`. `jdx source kotlin.Unit` prints `Unit.kt`, because that path is a direct hit and a whole file is not parsed. `jdx doc` on the same class exits 1 and names `jdx kotlin install`. `jdx body` of `toString` is still `⚠ reconstructed`.

```console exec max-lines=10
$ jdx source kotlin.Unit --max-lines 8 --coord org.jetbrains.kotlin:kotlin-stdlib:2.1.21 --fetch
```

```console exec
$ jdx doc kotlin.Unit --coord org.jetbrains.kotlin:kotlin-stdlib:2.1.21 --fetch   # exit 1
$ jdx body 'kotlin.Unit#toString()' --max-lines 12 --coord org.jetbrains.kotlin:kotlin-stdlib:2.1.21 --fetch
```

A file you already have is `--jars` (a jar, a class directory, or a glob). Quote the glob so the shell does not expand it. An unquoted expansion exits 3. `--no-jdk` leaves the JDK out.

```bash
jdx signature 'kotlinx.coroutines.Job#parent' --jars path/to/library.jar --no-jdk
jdx members com.example.ApiKt --declared --kind method --jars 'libs/*.jar' --no-jdk
```

Inside a Gradle or Maven project, with no classpath flags, jdx discovers project classes and the dependency jars named in the build files. It never runs Gradle or Maven to do that. See [Classpath & workspaces](classpath.md). The rest of the Kotlin rules are in [Reading code](reading-code.md#kotlin).

## From an AI agent

The same Kotlin view is what an agent gets from `jdx mcp`. `jdx_members` and `jdx_signature` take `view`: `kotlin` (the default) or `jvm`. `jdx_show` is the class card. `jdx_body` is the one member, reconstructed and labelled when the `.kt` slice is unavailable. A call may pass `jars` or `coord`, and `discover` true reads a Gradle or Maven project from the server's working directory when no stored workspace exists. The server never downloads. A tool call may pass `workspace` to pick another stored name. Prime a coordinate with `--fetch` first, or pass files you already have as `jars`.

```bash
jdx show kotlinx.coroutines.BuildersKt --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch
jdx ws create coroutines --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
jdx mcp -w coroutines
jdx setup --agent claude-code --scope project
jdx setup --agent cursor --scope project
jdx setup --agent codex --scope project
```

`jdx setup` writes `args` as `mcp` only. Add `-w` by hand when the workspace is not `default`. Per-agent pages are on [integrations](https://mohammadmd1383.github.io/jdx/integrations/). The config shapes are in [An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

Reference: [`jdx_members`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_members), [`jdx_signature`](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/#jdx_signature).

Bytecode is parsed with ASM. Inspected classes are never loaded, so no static initialiser from the jar runs. jdx writes only inside its own cache, config, and runtime directories.

## FAQ

### How do I read a Kotlin library API from a jar?

`jdx show kotlinx.coroutines.BuildersKt --coord org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 --fetch` prints the class card. `jdx members` lists what you can call. `jdx signature` prints one declaration. The default view is the Kotlin declaration from `@Metadata`. Install jdx with the [one-line installer](installation.md). Java 21 or later is required.

### Why does a decompiled Kotlin suspend function take Continuation?

The class file appends `kotlin.coroutines.Continuation` and often returns `java.lang.Object`. `jdx signature 'kotlinx.coroutines.flow.FlowKt#collect'` prints `suspend` and omits that parameter. `jdx signature ... --view jvm` prints the class-file signature, including `Continuation`.

### How do I see nullability in a decompiled Kotlin jar?

`jdx signature 'kotlinx.coroutines.Job#parent'` prints `val kotlinx.coroutines.Job? parent`. The JVM getter `getParent` returns `Job` with no `?`. The marker comes from `@Metadata` (v1.7.0). A reconstructed method body does not add it back. The `?` stays on the declaration line. The canonical reference omits it.

### How do I view Kotlin metadata in a jar?

jdx decodes `@Metadata` while it renders `jdx members` and `jdx signature`. There is no separate metadata dump. `--view kotlin` is the default. `--view jvm` is the class file: getters, `Continuation`, `$default`, and `@JvmName` JVM names. A file facade and its multi-file parts both render from that metadata (#12).

### Does reading a Kotlin jar need jdx kotlin install?

Not for declarations. `jdx members` and `jdx signature` read `@Metadata` with no extra download. `jdx kotlin install` is once, for KDoc and for slicing a `.kt` member. Without it, `jdx doc` on Kotlin sources exits 1 and names the install command, and `jdx body` is labelled `⚠ reconstructed`. The network is that install, plus `--fetch` when you ask to download a coordinate.

## Where next

- [`jdx signature`](https://mohammadmd1383.github.io/jdx/docs/reference/signature/) — every flag, generated from the binary.
- [`jdx members`](https://mohammadmd1383.github.io/jdx/docs/reference/members/) — filters, `--view`, and `--kind property`.
- [Read and decompile a JAR from the terminal](read-jar-from-terminal.md) — one method, source, or a decompilation.
- [Read Javadoc from a jar](read-javadoc-from-jar.md) — the comment, from a paired `-sources.jar`.
- [Find which JAR contains a class](find-class-in-jar.md) — which file holds a class.
- [Reading code](reading-code.md#kotlin) — the Kotlin view, `--view jvm`, and the sidecar.
- [An MCP server for Java and Kotlin bytecode](java-mcp-server.md) — `jdx mcp` for Claude Code, Cursor, and Codex.
- [Installation](installation.md) — Linux, macOS, Windows, and offline installs.
- [Symbol references](symbol-references.md) — how to paste a canonical ref.
- [Classpath & workspaces](classpath.md) — `--jars`, `--coord`, and `jdx ws`.
- [Output, JSON & exit codes](output.md#exit-codes) — the exit-code table.
- [Command catalogue](../COMMANDS.md) — every flag.
