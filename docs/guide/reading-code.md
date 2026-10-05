# Reading code

Look at a type, list what you can call on it, read one method, or search the whole classpath — each with one command that returns only what was asked. This guide covers `show`, `members`, `outline`, `signature`, `doc`, `body`, `source`, `search`, `resolve`, `ls`, `tree`, and Kotlin.

The examples use Gson by Maven coordinate; any classpath works the same way
([Classpath & workspaces](classpath.md)).

## A type at a glance: `show`

```console exec
$ jdx show com.google.gson.JsonArray --coord com.google.code.gson:gson:2.14.0
```

## What can I call? `members` and `outline`

`members` is the `.` completion of an IDE: inherited members are included and grouped by the
type that declares them, with `java.lang.Object` collapsed to one line.

```console exec max-lines=16
$ jdx members com.google.gson.JsonArray --kind method --limit 10 --coord com.google.code.gson:gson:2.14.0
```

Narrow it down instead of reading more:

| Want | Flag |
|---|---|
| only this type's own members | `--declared` (or use `jdx outline`) |
| methods / fields / constructors | `--kind method\|field\|ctor\|property` |
| visibility | `--access public\|protected\|package\|private\|all` |
| statics or instances | `--static` / `--instance` |
| members from one supertype | `--from java.util.AbstractList` |
| names matching a regex | `--grep '^get'` |
| the first javadoc sentence per member | `--with-doc` |
| a hard token budget | `--brief`, `--max-lines N` |

```console exec
$ jdx members com.google.gson.JsonArray --grep '^as' --declared --coord com.google.code.gson:gson:2.14.0
```

`outline` prints one dense line per declared member — the file-structure view.

## One member: `signature`, `doc`, `body`

`signature` shows every overload with real parameter names, generics, `throws` and defaults:

```console exec
$ jdx signature 'com.google.gson.JsonParser#parseString' --coord com.google.code.gson:gson:2.14.0
```

`doc` renders the javadoc as plain text; an undocumented override falls back to the nearest
documented supertype (and says so):

```console exec max-lines=12
$ jdx doc 'com.google.gson.JsonParser#parseString(String)' --coord com.google.code.gson:gson:2.14.0
```

`body` shows exactly one method — tens of lines instead of a whole file:

```console exec
$ jdx body 'com.google.gson.JsonParser#parseString(String)' --coord com.google.code.gson:gson:2.14.0
```

Useful flags: `--line-numbers`, `--context N` (lines around the body), `--with-doc`,
`--with-signature`, `--max-lines N`.

## A whole file, sliced: `source`

When you do need more than one member, slice the file instead of dumping it:

```bash
jdx source com.google.gson.Gson --lines 120:160
jdx source com.google.gson.Gson --around 'com.google.gson.Gson#toJson(Object)' --context 5
```

## Sources, decompilation, bytecode

jdx always prefers real sources (a paired `-sources.jar`, a source directory, the JDK's
`src.zip`). Without them it decompiles with Vineflower and marks the result
`⚠ reconstructed`; `--engine javap` shows raw bytecode instead:

```console exec max-lines=12
$ jdx body 'java.util.HashMap#get(java.lang.Object)' --engine javap
```

When sources and binary disagree, jdx warns `SOURCES_VERSION_MISMATCH` — bytecode is the
truth for structure, sources supply bodies, names and docs.

## Search the classpath: `search`, `resolve`, `ls`, `tree`

```console exec max-lines=10
$ jdx search 'JsonParser*' --coord com.google.code.gson:gson:2.14.0
$ jdx search 'parse*' --kind method --in 'gson*' --coord com.google.code.gson:gson:2.14.0
```

Globs by default, `--regex` for regular expressions; bare words match case-insensitively or as
camel humps (`HMap` finds `HashMap`); `--fuzzy` retries a miss by edit distance. `--kind`
narrows to classes, interfaces, methods, fields, packages or modules.

- `jdx resolve <name>` — every candidate for a simple name, with kind and artifact.
- `jdx ls 'com.google.*'` — packages with type counts; an exact package lists its types.
- `jdx tree 'gson*'` — the package tree of an artifact (`--depth`, `--counts`).

## Kotlin

Kotlin binaries are read through their `@Metadata`, so jdx shows true Kotlin declarations —
`suspend` functions, properties, default arguments, `@JvmName` mappings — rather than the JVM
projection. `--view jvm` shows the raw JVM view (getters, `Continuation` parameters, mangled
names) when that is what you need.

Reading Kotlin *sources* (`body`, `source`, `doc` on `.kt` files) uses the Kotlin compiler's
parser, which is installed separately on demand to keep jdx small:

```bash
jdx kotlin install
```

Without it, Kotlin binaries still render from metadata and source queries degrade with a label.
