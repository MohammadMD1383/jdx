# Classpath & workspaces

jdx answers from a classpath made of jars, class directories, source directories, Maven coordinates and the JDK. This guide covers every way to supply one — per command, as a named workspace, or by letting jdx discover your project.

## The JDK is always there

The running JDK's standard library is on every classpath (through `jrt:/`), paired with its
`src.zip` when the JDK ships one. Pass `--no-jdk` to leave it out.

## Per-command flags

| Flag | Adds |
|---|---|
| `--jars <path\|glob\|dir>` | jar files, class directories or globs (repeatable) |
| `--coord g:a:v` | a Maven artifact, plus its `-sources.jar` when available (repeatable) |
| `--fetch` | permission to download `--coord` artifacts; without it, only local caches are used |
| `--repo <url>` | an extra Maven repository, tried before Maven Central (repeatable) |
| `--no-jdk` | removes the JDK |

`jdx usages` additionally takes `--src <dir>` for project source directories.

```console exec max-lines=8
$ jdx ls com.google.gson.stream --coord com.google.code.gson:gson:2.14.0
```

### Maven coordinates

Coordinates resolve from `~/.gradle/caches` and `~/.m2` first — usually no download is
needed. With `--fetch`, a missing artifact is downloaded from Maven Central (or your `--repo`
mirrors) into the jdx cache, with checksum verification. Network access is opt-in per
invocation: jdx never downloads anything you did not ask for.

## Named workspaces

A workspace is a named, ordered classpath stored once and reused everywhere — the CLI, the MCP
server, the daemon, the HTTP API and `jdx batch`:

```bash
jdx ws create myapp --jars 'libs/*.jar' --src ./src --coord com.google.code.gson:gson:2.14.0
jdx ws add myapp build/classes/java/main
jdx ws list
jdx ws info myapp
```

Select one per command with `-w`, per shell with `JDX_WORKSPACE`, or per machine with
`jdx ws use`:

```bash
jdx -w myapp usages 'com.example.Service#run()'
export JDX_WORKSPACE=myapp
jdx ws use myapp            # default when neither -w nor JDX_WORKSPACE is set
jdx ws use --clear
```

Explicit `--jars` / `--coord` flags always merge in front of the selected workspace.

## Project auto-discovery

With no workspace selected, jdx walks up from the working directory looking for
`settings.gradle(.kts)`, `build.gradle(.kts)`, `pom.xml` or `.idea/`. On a hit it assembles a
classpath from the project's own sources and build output plus the dependency jars it can find
in the Gradle and Maven caches. jdx never runs Gradle or Maven to do this; when it cannot pin
down the exact dependency set it says so with a `PROJECT_DISCOVERY_FALLBACK` warning.

## Resolution order

1. Explicit flags (`--jars`, `--src`, `--coord`).
2. `-w <name>`.
3. `JDX_WORKSPACE`, then the `jdx ws use` default.
4. Project auto-discovery.
5. The JDK — always appended unless `--no-jdk`.

When nothing resolves for a command that needs a classpath, jdx exits `4`.

## The index cache

jdx keeps a persistent index keyed by content hash, so an unchanged jar is never re-read.
Everything in it can be regenerated:

```bash
jdx cache info     # location, size, artifact and class counts
jdx cache gc       # evict artifacts no workspace references
jdx cache clear    # wipe it; re-indexed on demand
```

Cache, config and runtime directories follow each OS's conventions (`~/.cache/jdx` and
`$XDG_*` on Linux, `~/Library/Caches` on macOS, `%LOCALAPPDATA%` on Windows) and can be
overridden with `JDX_*` variables — the full table is in the
[design specification](../PROPOSAL.md#17-security-and-safety).
