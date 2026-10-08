# Quickstart

Five minutes, two classpaths: the JDK (no setup at all) and a library pulled by its Maven coordinate. Every output below is real — it was produced by running the command when this page was built.

## 1. Ask about the JDK — zero setup

The running JDK is always on the classpath, so this works straight after installing:

```console exec max-lines=14
$ jdx members java.util.HashMap --limit 5
```

The footer says what was cut and which `--limit` shows the rest. The `source:` line says where
the answer came from.

Now read one method — only that method:

```console exec
$ jdx body 'java.util.HashMap#get(java.lang.Object)'
```

Always quote references in a shell: `#`, `(` and `$` are shell syntax. When there are no
sources, jdx decompiles and marks the output `⚠ reconstructed`.

## 2. Ask about a library by Maven coordinate

`--coord` resolves a library from your local Gradle and Maven caches; `--fetch` allows
downloading it (and its `-sources.jar`) from Maven Central once:

```console exec
$ jdx show com.google.gson.Gson --coord com.google.code.gson:gson:2.14.0 --fetch
```

With sources available, `body` shows the real source with its file and line range:

```console exec
$ jdx body 'com.google.gson.Gson#toJson(Object)' --coord com.google.code.gson:gson:2.14.0
```

## 3. Let it refuse to guess

`toJson` has several overloads. jdx will not pick one for you — it exits `2` and prints every
candidate as a ready-to-paste reference:

```console exec
$ jdx body 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0   # exit 2
```

## 4. Find things and follow the graph

```console exec max-lines=12
$ jdx search '*Json*Reader*' --coord com.google.code.gson:gson:2.14.0
$ jdx hierarchy com.google.gson.JsonElement --coord com.google.code.gson:gson:2.14.0
```

## 5. Name your classpath once

Typing `--coord` on every command gets old. A named workspace stores the classpath; every
command (and the MCP server, daemon and HTTP API) can reuse it:

```bash
jdx ws create gson --coord com.google.code.gson:gson:2.14.0
jdx -w gson usages com.google.gson.TypeAdapter
jdx ws use gson        # make it the default for this machine
```

Inside a Gradle or Maven project you often need nothing at all: jdx discovers the project's
sources and dependency jars from the working directory. See
[Classpath & workspaces](classpath.md).

## 6. Hand it to your agent

```bash
jdx setup --agent claude-code --scope project   # or cursor, codex, opencode, copilot, antigravity, …
```

That is the whole integration — see [Use with AI agents](ai-agents.md).

## Where next

- [Symbol references](symbol-references.md) — every way to name a class or member.
- [Reading code](reading-code.md) and [Usages, hierarchy & calls](navigating-code.md).
- The [command catalogue](../COMMANDS.md).
