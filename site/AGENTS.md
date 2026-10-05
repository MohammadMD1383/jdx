# AGENTS.md — `site/`

Build-time generator for the GitHub Pages website (https://mohammadmd1383.github.io/jdx/).
Not a product module: nothing depends on it and it never reaches the fat jar.

## The one idea

The site is **generated from the repository and the freshly built `jdx`**, and the build
**fails instead of publishing something stale**. Docs drift becomes a red PR, not a wrong page.

| Drift | Caught by |
|---|---|
| example output changed / command broke / exit code changed | `console exec` blocks are executed (`ExecBlocks`) |
| flag or command renamed in the CLI, not in the docs | `DocLint` checks every `jdx <cmd> --flag` in code spans against real `--help` |
| reference pages | generated from `jdx --help`, `jdx help --json`, `jdx mcp` `tools/list` (`CliFacts`) |
| broken link / anchor / moved file | `DocRenderer` (repo links) + `LinkChecker` (final HTML) |
| new doc not on the site | `SiteManifestTest` (every `*.md` is published or excluded with a reason) |
| README marketing section renamed | `Markdown.extractSection` (landing quotes README by heading) |
| new `jdx setup --agent` value | `ProductPages.AGENT_NAMES` must name it, or the build fails |
| machine paths in output | `SiteBuilder` path-leak check |
| perf / a11y / SEO / agent-readiness regressions | `site/lighthouse/check.mjs` in `pages.yml` |

## Commands

```bash
./gradlew :site:buildSite        # -> site/build/site (builds :app:installDist first; ~1-2 min;
                                 #    needs network once to fetch the Gson example fixture)
./gradlew :site:serveSite        # preview at http://127.0.0.1:8000/jdx/
./gradlew :site:test :site:integrationTest   # generator tests (also part of `check`)
```

**Run `:site:buildSite` whenever you change `docs/`, `README.md`, CLI help text, flags, exit
codes, or MCP tools.** `pages.yml` runs it on every PR anyway; running it first saves a round trip.

## Writing docs

- **Executable examples:** a fenced block with info string `console exec` holds `$ jdx ...`
  lines. On the site each runs against the real build and its output replaces whatever is
  written under it. Append `# exit N` to a line that should exit non-zero; `max-lines=N` in
  the info string caps long output. Only `jdx ...` and `echo '...' | jdx ...` can run.
- Examples run in a **sandbox**: empty temp working dir (no project auto-discovery), private
  cache/config dirs, the JDK on the classpath, and Gson `2.14.0` prefetched — use
  `--coord com.google.code.gson:gson:2.14.0` for library examples. Never run commands with
  side effects (`ws create`, `cache clear`, `setup` without `--check`, `daemon`, `serve`) in
  an exec block; show them in a plain `bash` block (still linted).
- First paragraph of a doc = its meta description and `llms.txt` summary. Write it as one.
- Link to other docs with **relative repo paths** (`../COMMANDS.md#anchor`) — they work on
  GitHub and are rewritten to site URLs. Generated pages (MCP tools, cheat sheet,
  integrations) have no repo file; link them by absolute site URL.

## Adding a page

Add one line to `SiteManifest` (section = place in the hierarchy Getting started → Guides →
Reference → Advanced). URLs are flat `docs/<slug>/` and must never change once published.

## Key files

- `SiteBuilder.kt` — orchestration, validation, output map. `SiteMain.kt` — entry + preview server.
- `SiteManifest.kt` — doc → URL → section. `Layout.kt` — HTML shell, head/SEO/social meta, nav.
- `CliFacts.kt` — everything read from the binary. `ReferencePages.kt` — per-command pages,
  cheat sheet, MCP tools (+ `EXAMPLES`, executed). `ProductPages.kt` — landing, integrations,
  FAQ, changelog, docs index, 404.
- `SeoFiles.kt` (sitemap, robots, llms.txt, llms-full.txt), `StructuredData.kt` (JSON-LD),
  `AiCatalog.kt` (ARD `ai-catalog.json`), `OgImage.kt` (per-page social cards, touch icon).
- `src/main/resources/site/` — `site.css` (inlined), `site.js` (copy buttons), `favicon.svg`.

## Gotchas

- CSS is inlined into every page on purpose (one request, no render-blocking). Keep it small;
  no web fonts, no frameworks, no client-side rendering.
- Every page gets an `index.md` twin; agents read Markdown far better than HTML.
- `robots.txt` / `llms.txt` only count at a host root. On `github.io/jdx/` they are ignored by
  crawlers; a custom domain (`SITE_BASE_URL` repo variable) fixes that and writes `CNAME`.
- The launcher fails when `JAVA_TOOL_OPTIONS` is set (#88); `ProcessJdxRunner` unsets it.
