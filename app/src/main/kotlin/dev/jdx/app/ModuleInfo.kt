/**
 * # `:app`
 *

 * Assembly only: the fat jar, the AppCDS archive, and the `jdx` launcher script.
 *
 * No source of consequence should ever live here.
 *
 * The launcher must resolve a JDK **without assuming `JAVA_HOME` is set** — it is unset on the
 * maintainer's machine, and a launcher that assumes otherwise fails with an unreadable error.
 * Resolution order and failure behaviour are D-026: `JAVA_HOME` -> `java` on `PATH` ->
 * `JDX_JVM_DEFAULT_DIR` (default `/usr/lib/jvm/default`), one `jdx:` line on stderr and
 * exit 6 when any of it fails.
 */
package dev.jdx.app
