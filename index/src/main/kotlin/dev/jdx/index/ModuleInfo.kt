/**
 * # `:index`
 *

 * Reads compiled artifacts and stores what it learns.
 *
 * ## Responsibilities
 *
 *  - opening jars, class directories and the JDK's own `jrt:/` filesystem, uniformly
 *  - pairing a binary jar with its `-sources.jar` (`docs/PROPOSAL.md` §5.2)
 *  - reading class files with ASM into `core`'s `ClassInfo`/`MemberInfo`
 *  - decoding Kotlin `@Metadata`
 *  - extracting the reference edges that power find-usages and call hierarchy
 *  - the persistent index: SQLite, content-hash keyed, shared across workspaces (D-013)
 *
 * ## Two rules that are easy to break
 *
 *  1. **Never load an inspected class into the JVM** (D-017). ASM parses class files without
 *     executing them; reflection would run their static initialisers. Agents point this tool
 *     at untrusted third-party jars, so this is a security property, not a preference.
 *  2. **No SQL outside the `IndexStore` implementation package.** SQLite is a choice we may
 *     reverse; it must stay behind the interface.
 */
package dev.jdx.index
