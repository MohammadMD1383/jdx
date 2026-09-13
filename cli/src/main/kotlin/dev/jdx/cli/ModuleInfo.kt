/**
 * # `:cli`
 *

 * The one-shot command line interface — and **an adapter, nothing more** (D-004).
 *
 * A command class here parses flags, calls `JdxService`, hands the result to a renderer, and
 * maps the outcome to an exit code. That is all. If you are writing an `if` about *behaviour*
 * in this module, it belongs in `core`.
 *
 * This rule is what keeps the CLI, MCP server, daemon and HTTP API from drifting apart — and
 * a parity test asserts they return byte-identical payloads (`docs/TESTING.md` §9).
 *
 * Exit codes are a public contract that agents branch on without parsing prose (D-015):
 * `0` ok · `1` not found · `2` ambiguous · `3` usage · `4` no index · `5` read error ·
 * `6` internal.
 */
package dev.jdx.cli
