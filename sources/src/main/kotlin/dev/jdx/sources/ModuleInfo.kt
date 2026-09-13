/**
 * # `:sources`
 *

 * Everything that reads `.java` and `.kt` **text** — sources jars and source directories.
 *
 * Bodies, real parameter names, javadoc/KDoc and comments come from here. Structure does
 * not: that always comes from bytecode (D-009, "bytecode is the skeleton, sources are the
 * flesh"). When the two disagree, this module's job is to report
 * `SOURCES_VERSION_MISMATCH` — never to quietly win.
 *
 * ## The Kotlin-parsing constraint (D-008) — read before adding a dependency
 *
 * Kotlin sources are parsed with `kotlin-compiler-embeddable` PSI. It is ~55 MB and takes
 * ~1 s to initialise, so it is **not** a normal dependency of this module. It is:
 *
 *  - hidden behind the `SourceParser` interface — **nothing outside this module may import
 *    Kotlin compiler classes**;
 *  - side-loaded at runtime into an isolated `URLClassLoader` (it shades Guava and IntelliJ
 *    platform classes that will otherwise collide);
 *  - fetched and verified on first use, not bundled in the fat jar;
 *  - initialised once per daemon lifetime.
 *
 * Java sources use JavaParser, which is small and fast and needs none of this.
 */
package dev.jdx.sources
