# AGENTS.md — `sources/`

Uniform source access (`SourceRoot`: `-sources.jar`, source dirs, `src.zip`) plus
JavaParser extraction and side-loaded Kotlin PSI. **Nothing outside this module may
import Kotlin compiler classes** — the compiler is side-loaded, never a compile
dependency, never in the fat jar. `explicitApi()` is on.

## Key files

- `SourceRoot` — `binaryName → source file` mapping seam every M3 query reads through.
- `JavaBodies` — verbatim slices via AST `Range` (ground-truth bytes, never
  pretty-printed); overload matching is arity-first with source-simple-name
  narrowing; knows `AnnotationMemberDeclaration` (annotation elements aren't
  `MethodDeclaration`). `JavaDocs` — rendered plain text + inherited-doc fallback.
- `SourceSiblings` — same-file top-level siblings (`Matrix`/`Tag` in one `.java`).
- `SourcesMismatch` — `SOURCES_VERSION_MISMATCH` detection; Kotlin pairs in
  declaration space (display + property names, data/value synthetics and
  `@JvmOverloads` shorts excused).
- `KotlinToolchain` — versioned sidecar path probe (`Installed`/`Missing`, never
  throws); `KotlinParser` — isolated-`URLClassLoader` seam (presence-vs-usability
  split); `KotlinBodies` — real PSI ranges, property-aware (getter/setter →
  property name). Without the sidecar, degrade to decompile/javap — emit
  *something* and say what it is.

## Rules

- Structure stays bytecode-authoritative: ambiguity is decided before sources load;
  the per-command parser instance is shared, never throws.
- KDoc attaches to the *following* declaration — pin type docs only on classes whose
  comment directly precedes them.

## Gotchas (distilled)

- Kotlin `/* */` comments nest: never write `/*` or `/**` inside KDoc or test-source
  comments (a `/*.jar` glob in a comment breaks compilation).
- Standalone PSI bootstrap is four steps: `idea.home.path` → app env for production
  → `setExtensionsStorage` on the config → `createForProduction`; the sidecar set
  must include the compiler's runtime jars.
- Test the isolated-loader seam with a JDK-compiled stub of the presence class
  (proves Available without the 55 MB jar) + a classpath-absence pin so a future
  compile dep reds instead of bloating the fat jar.
- Callable references don't apply defaults — injectable seams narrowing a function
  with defaulted trailing params must use a lambda, never `::ref`.
