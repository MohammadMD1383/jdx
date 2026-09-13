/**
 * # `:mcp`
 *

 * Model Context Protocol server over stdio — `jdx mcp`.
 *
 * Exposes each command as a typed MCP tool so an agent gets structured arguments and tool
 * descriptions instead of guessing shell syntax, and cannot make quoting mistakes with `#`
 * and `(`. For the project's stated goal — an IDE for agents — this is the highest-leverage
 * interface.
 *
 * Tool schemas are **generated from the same command metadata the CLI uses**. Hand-writing
 * them twice guarantees they drift. An adapter, like `cli`: no behaviour here.
 *
 * The server holds the index in memory for its lifetime, so it is effectively a per-session
 * daemon and repeated queries are instant.
 */
package dev.jdx.mcp
