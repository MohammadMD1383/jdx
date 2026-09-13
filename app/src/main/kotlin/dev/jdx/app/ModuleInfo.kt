/**
 * # `:app`
 *

 * Assembly only: the fat jar, the AppCDS archive, and the `jdx` launcher script.
 *
 * No source of consequence should ever live here.
 *
 * The launcher must resolve a JDK **without assuming `JAVA_HOME` is set** — it is unset on the
 * maintainer's machine, and a launcher that assumes otherwise fails with an unreadable error.
 * Resolution order: `JAVA_HOME` -> `java` on `PATH` -> `/usr/lib/jvm/default`.
 */
package dev.jdx.app
