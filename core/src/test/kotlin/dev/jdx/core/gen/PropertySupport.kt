package dev.jdx.core.gen

import io.kotest.property.PropTestConfig

/**
 * The standing rules for every property test in this repo (T-055; TESTING.md §4).
 *
 * - **Iterations:** [JDX_PROPERTY_ITERATIONS] (1,000) is the minimum per property in tier 1.
 *   Fast pure functions (parsers, resolvers, renderers) absorb this in milliseconds; if a new
 *   property threatens the 30 s tier-1 budget, shrink its *scope* (smaller arbs), never the
 *   count — and say so in the session log.
 * - **Seeds:** kotest prints the failing seed with the failure (`Seed=…`). Pin it as a
 *   regression with one line — pass [pinnedConfig] as the second argument:
 *
 *   `checkAll(1_000, pinnedConfig(987654321L), arbFoo()) { foo -> … }`
 *
 *   Delete the pin once the bug is fixed and the property passes unpinned again; a pinned
 *   seed left behind is a test that stopped generating.
 */
const val JDX_PROPERTY_ITERATIONS: Int = 1_000

fun pinnedConfig(seed: Long): PropTestConfig = PropTestConfig(seed = seed)
