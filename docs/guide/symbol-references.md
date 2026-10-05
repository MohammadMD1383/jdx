# Symbol references

Every jdx command takes a symbol reference in Javadoc style. jdx is generous about what it accepts and canonical about what it prints, so any reference it prints can be pasted back into the next command.

## The forms

| Reference | Means |
|---|---|
| `com.google.gson.Gson` | a type |
| `java.util.Map$Entry`, `java.util.Map.Entry` | a nested type (`$` or `.`) |
| `Gson` | a short name — resolved, or exit `2` with candidates |
| `com.google.gson.Gson#toJson` | every overload of a method |
| `com.google.gson.Gson#toJson(Object)` | one overload, simple parameter names |
| `com.google.gson.Gson#toJson(java.lang.Object)` | one overload, qualified parameter names |
| `…#toJson(Object):String` | disambiguated by return type (bridge methods) |
| `…#toJson(Ljava/lang/Object;)Ljava/lang/String;` | an exact JVM descriptor — never ambiguous |
| `com.google.gson.Gson#<init>(...)` | a constructor |
| `java.lang.Integer#MAX_VALUE` | a field |
| `com.google.code.gson:gson:2.14.0/com.google.gson.Gson` | scoped to one Maven artifact |

`::` and `.` are accepted in place of `#`. The last dot-separated segment before the member is
always the class.

**Quote references in a shell.** `#` starts a comment, `(` opens a subshell and `$` expands a
variable — single quotes avoid all three.

## Nested types

Both spellings resolve to the same type, and jdx prints the canonical `$` form:

```console exec
$ jdx show java.util.Map.Entry
$ jdx show 'java.util.Map$Entry'
```

## Overloads and descriptors

Simple names, qualified names and raw descriptors all select a single overload; fields work the same way:

```console exec
$ jdx signature 'java.util.HashMap#put(Object, Object)'
$ jdx signature 'java.util.HashMap#get(Ljava/lang/Object;)Ljava/lang/Object;'
$ jdx signature 'java.lang.Integer#MAX_VALUE'
```

## Ambiguity: jdx never guesses

When a reference matches more than one symbol, jdx exits `2` and prints every candidate in its
canonical form. An agent retries with one of them — no reconstruction needed:

```console exec
$ jdx signature 'com.google.gson.Gson#toJson(Object, Appendable)' --coord com.google.code.gson:gson:2.14.0
$ jdx body 'com.google.gson.Gson#toJson' --coord com.google.code.gson:gson:2.14.0   # exit 2
```

## Not found: did you mean

A miss exits `1` with the closest candidates (camel humps, then edit distance, then the same
simple name in other packages):

```console exec
$ jdx show com.google.gson.JsonParse --coord com.google.code.gson:gson:2.14.0   # exit 1
```

When you only know a simple name — from a stack trace or a snippet — `jdx resolve` lists every
candidate with its kind and artifact, and several candidates are a success:

```console exec max-lines=6
$ jdx resolve JsonParser --coord com.google.code.gson:gson:2.14.0
```

## Round-tripping

`parse(print(ref)) == ref` is a tested property: every reference jdx prints — in `members`,
`usages`, `callers`, ambiguity lists, `next:` hints — is accepted verbatim as input.

The full grammar is in the [design specification](../PROPOSAL.md#6-symbol-reference-grammar).
