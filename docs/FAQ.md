# Frequently asked questions

Short answers about what jdx is, how it compares to the tools agents use today, and what it needs. Each links to the docs with the details.

## What is jdx?

jdx is an IDE for AI agents, as a command line tool. It answers the questions an IDE answers for a human — what members a class has, what a method does, who calls it, what implements an interface, what an upgrade breaks — for any JVM jar, Maven coordinate, class or source directory and the JDK. It ships as a CLI and as an MCP server. See the [introduction](guide/introduction.md).

## Which AI agents does it work with?

Any agent that speaks the Model Context Protocol or can run shell commands. `jdx setup` wires it into Claude Code, Cursor, Codex CLI, GitHub Copilot CLI, OpenCode, Kilo Code and Cline with one command; other MCP clients take a one-line config. See [Use with AI agents](guide/ai-agents.md).

## How is it different from javap, unzip and grep?

Those tools answer one low-level question each, so an agent chains several of them and reads large outputs into its context. jdx answers the actual question in one command: only the method you asked for, inherited members resolved transitively with generics, usages across every jar, real source when a sources jar exists, and a labelled decompilation when it does not. On a measured task it used 2 calls and about a quarter of the bytes. See the [README benchmark](https://github.com/MohammadMD1383/jdx#readme).

## Does it run code from the jars it inspects?

No. jdx parses bytecode with ASM and never loads inspected classes, so no static initialiser or any other code from those jars ever runs. It also never writes outside its own cache, config and runtime directories.

## Does it need the library's sources?

No. With a `-sources.jar` (or a source directory, or the JDK's `src.zip`) it shows real source. Without one it decompiles with Vineflower and marks the result as reconstructed, and `--engine javap` shows raw bytecode. Structure always comes from bytecode, so member lists and signatures are exact either way.

## Does it support Kotlin?

Yes. Kotlin binaries are read through their `@Metadata`, so jdx shows real Kotlin declarations — `suspend` functions, properties, default arguments, `@JvmName` mappings. Reading Kotlin sources needs a one-time `jdx kotlin install`. See [Reading code](guide/reading-code.md#kotlin).

## Does it work on my Gradle or Maven project?

Yes. Run it inside the project and it discovers the project's sources and dependency jars from the Gradle and Maven caches, without running Gradle or Maven. You can also name a classpath once as a workspace. See [Classpath & workspaces](guide/classpath.md).

## Does it access the network?

Only when you pass `--fetch`, to download a Maven artifact that is not in your local caches, and when you run `jdx upgrade` or `jdx kotlin install`. Everything else is local.

## How fast is it?

About 250 ms for a cold one-shot query and about 20 ms when the daemon or MCP server keeps the index warm. The index is persistent and keyed by content hash, so unchanged jars are never re-read. See [MCP, daemon, HTTP & batch](guide/serving.md#performance).

## What does it need to run?

A Java 21 or later runtime on Linux, macOS or Windows. Install is per user and needs no root. See [Installation](guide/installation.md).

## Is it free?

Yes. jdx is open source under the Apache-2.0 license, and every bundled dependency uses a permissive license (Apache-2.0, BSD or MIT).

## How do I report a bug or ask for a feature?

Open an issue on [GitHub](https://github.com/MohammadMD1383/jdx/issues) with the exact command, what you expected and what you got. `jdx doctor` output helps with environment problems.
