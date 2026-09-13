/**
 * # `:server`
 *

 * The HTTP/JSON API (`jdx serve`) and the background daemon (`jdx daemon`).
 *
 * Two separate listeners serving different audiences, deliberately not sharing state
 * ownership (D-004):
 *
 *  - **daemon** — a unix domain socket holding a hot index, so the CLI's per-query latency
 *    drops from ~250 ms to ~10-20 ms. **Shuts itself down after 5 minutes of inactivity**
 *    (configurable with `--idle`; `0` disables). The socket path is version-stamped so an
 *    upgraded `jdx` never talks to a stale daemon.
 *  - **HTTP** — `127.0.0.1` by default, built on the JDK's own `com.sun.net.httpserver` so it
 *    costs no dependency. For non-MCP agents, editor plugins, scripts and notebooks.
 *
 * Adapters, like `cli` and `mcp`: no behaviour here.
 */
package dev.jdx.server
