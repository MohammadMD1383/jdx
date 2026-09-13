/**
 * # `:core`
 *

 * The shared vocabulary of `jdx`: the domain model, symbol references, resolution
 * algorithms, and rendering.
 *
 * ## Boundary — this is the one rule that matters here
 *
 * `core` has **no third-party runtime dependencies and performs no IO**. No ASM, no SQLite,
 * no JavaParser, no files, no network. Everything here is pure data and pure functions, so it
 * is trivially unit-testable and is developed **test-first** (D-020).
 *
 * If you find yourself wanting to `import org.objectweb.asm` in this module, you need a
 * different design: define the shape you need here as a plain type, and let `index` produce
 * it.
 *
 * ## What lives here
 *
 *  - the model: `TypeName`, `JvmDescriptor`, `GenericSignature`, `ClassInfo`, `MemberInfo`
 *  - symbol references: parsing and canonical printing (`docs/PROPOSAL.md` §6)
 *  - member resolution, including inherited members and generic substitution (§9.3)
 *  - rendering to text and JSON (§8), and the truncation/token-budget rules
 *
 * Produced by: `index` (from bytecode), `sources` (from `.java`/`.kt`), `decompile`.
 * Consumed by: every adapter — `cli`, `mcp`, `server`.
 */
package dev.jdx.core
