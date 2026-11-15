# Usages, hierarchy & calls

Find who uses a symbol, what implements a type, who calls a method and what it calls, and real call sites to learn from — across every jar on the classpath, not just one.

## Find usages: `usages`

One row per referencing method, named canonically so the next command is a copy-paste:

```console exec max-lines=14
$ jdx usages com.google.gson.JsonNull --limit 8 --coord com.google.code.gson:gson:2.14.0
```

Narrow by edge kind with `--kind`:

| `--kind` | Finds |
|---|---|
| `call` | method calls |
| `read` / `write` | field reads and writes |
| `new` | constructor call sites |
| `ref` | any other reference (casts, `instanceof`, class literals, signatures) |
| `throw` | methods declaring the type in `throws` (declaration-level, not `ATHROW` throw sites) |
| `annotation` | classes and members carrying the annotation |

`--in` / `--exclude` filter by artifact (jar file name, JDK module or source directory), and
`--src <dir>` adds your project's source directories — those hits are textual mentions with
line numbers. `--context N` shows a short source window around each call site when paired
sources (or `--src`) are available; `0` (default) is location-only (`File.java:line`).

## Type hierarchy: `hierarchy` and `implementors`

Supertypes upward (the full chain, interfaces included) and subtypes downward across the whole
classpath:

```console exec
$ jdx hierarchy com.google.gson.JsonElement --coord com.google.code.gson:gson:2.14.0
```

`--up` / `--down` pick a direction, `--direct` stops at one level, `--depth N` caps it.
`jdx implementors <type>` is `hierarchy --down`:

```console exec max-lines=10
$ jdx implementors com.google.gson.TypeAdapterFactory --direct --coord com.google.code.gson:gson:2.14.0
```

## Call hierarchy: `callers` and `calls`

```console exec max-lines=12
$ jdx callers 'com.google.gson.JsonParser#parseReader(com.google.gson.stream.JsonReader)' --coord com.google.code.gson:gson:2.14.0
```

`--depth N` follows the graph transitively (cycle-safe, cycles are marked). `jdx calls` goes
the other way; add `--external-only` to see only what a method needs from *other* artifacts —
its dependency footprint.

## Learn from real code: `samples`

The fastest way to learn an API is to read code that already uses it. `samples` ranks real call
sites by how exemplary they are — non-test before test, non-generated before generated, fuller
overloads first — and shows the enclosing method when sources are available. Each snippet is
capped at 15 lines by default (`--max-lines N` to raise it; the truncation marker names the total):

```console exec max-lines=24
$ jdx samples 'com.google.gson.JsonParser#parseString(String)' --coord com.google.code.gson:gson:2.14.0
```

## String constants

To find *which class holds this English string / resource key* without `unzip | tr | grep`
(issue #86):

```console exec
$ jdx literals 'com.google.gson' --coord com.google.code.gson:gson:2.14.0 --limit 5 --no-jdk
```

Matches bytecode `LDC` loads and `String` field `ConstantValue`s. Resource `.properties`
files are not scanned — keys that appear as class-file constants are found.

## SPI / ServiceLoader registrations

To answer *who provides this service interface?* without unzipping jars (issue #25):

```console exec
$ jdx services java.sql.Driver --coord org.postgresql:postgresql:42.7.4 --fetch --no-jdk
```

Omit the pattern to list every `META-INF/services` registration in the workspace. A
pattern matches the service interface FQN or a provider class FQN. `module-info`
`provides` directives are read by `jdx modules` instead.

## JPMS module-info

To answer *what does this module require / export / provide?* without `jmod describe`
(issue #25):

```console exec
$ jdx modules java.sql --limit 5
```

Omit the pattern to list every `module-info.class` in the workspace (including the
running JDK). A pattern matches the module name, an exported/opened package, a
`uses`/`provides` service, or a provider class.

## Manifest attributes

To answer *what is this jar's Main-Class / Implementation-Version / Multi-Release?*
without `unzip -p …/MANIFEST.MF` (issue #25):

```console exec
$ jdx manifest Multi-Release --coord com.google.code.gson:gson:2.14.0 --no-jdk
```

Omit the pattern to list every main attribute. A pattern matches the attribute name or
value. Pass `--entries` to list per-entry (JarEntry) attributes instead (`Name:`
sections — digests, Content-Type, …); the pattern then also matches the entry path.

## Resource paths

To answer *which jar holds this `.properties` / `META-INF/…` path?* without
`unzip -l` loops (issue #25):

```console exec
$ jdx resources META-INF --coord com.google.code.gson:gson:2.14.0 --limit 5 --no-jdk
```

Omit the pattern to list every non-class resource. A pattern matches the entry path.
Versioned copies under `META-INF/versions/` are listed as stored. Pass `--cat` to print
the unique match's text (binary / non-UTF-8 payloads refuse to dump bytes; ambiguous
matches exit 2 with `path --in artifact` candidates):

```console exec
$ jdx resources META-INF/MANIFEST.MF --cat --coord com.google.code.gson:gson:2.14.0 --no-jdk
```

## Limitations

- Graph queries re-scan the bytecode roots on each query (there is no persistent graph index
  yet); the daemon and MCP server keep the scan warm.
- `callers` is override-aware by default (issue #6): querying an interface/base method also
  finds call sites through overrides, and querying an override also finds polymorphic call
  sites through declaring supertypes. Pass `--exact` for invoke-owner matching only.
  `calls` stays bytecode-exact — an invoke instruction names one owner, so callees are not
  expanded to the override set.
- Usages found in source directories are textual (`ref` kind only).
- `usages --kind throw` reads `throws` declarations, not `ATHROW` sites (throw-site attribution needs data-flow analysis and is deferred to future work).

Open limitations are tracked in [GitHub Issues](https://github.com/MohammadMD1383/jdx/issues).
