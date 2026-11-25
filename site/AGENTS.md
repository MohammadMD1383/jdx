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
| perf / a11y / SEO / agent-readiness regressions | `site/lighthouse/check.mjs` in `pages.yml` — **warn only** |

Everything above the last row **blocks** (a failing build deploys nothing; the last good site
stays live). Lighthouse **never blocks**: misses are warning annotations plus a job-summary
table, by owner decision — quality scores must not stand between a fix and a working site.
Run it locally with `--strict` to make misses fail.

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

- **FAQ JSON-LD:** an H2 titled `FAQ` (or `Frequently asked questions`) whose questions are
  H3s is published as FAQPage JSON-LD next to the page's TechArticle. The answer is the
  plain text under that H3, so the structured data matches the visible page. A question
  with no prose fails the build.
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
Optional `headTitle` sets the `<title>` tag when the H1 plus ` · jdx docs` is the wrong
length; the H1 stays the on-page title and the Open Graph title.

## Key files

- `SiteBuilder.kt` — orchestration, validation, output map. `SiteMain.kt` — entry + preview server.
- `SiteManifest.kt` — doc → URL → section. `Layout.kt` — HTML shell, head/SEO/social meta, nav.
- `CliFacts.kt` — everything read from the binary. `ReferencePages.kt` — per-command pages,
  cheat sheet, MCP tools (+ `EXAMPLES`, executed). The command catalogue page
  (`docs/COMMANDS.md` → `/docs/reference/`) gets a generated "Command pages" section:
  every command, grouped, linked with its `jdx help` one-line summary. Commands in
  `RELATED` get a "Related" section (2–5 descriptive anchors) instead of the single
  learn-more line. A command missing from `COMMAND_GROUPS` or from the help sheet
  fails the build. The MCP tools page copies each
  parameter's description and, when `tools/list` sends them, its `enum`, `default`
  and `examples`. `ProductPages.kt` — landing, integrations, FAQ, changelog, docs index, 404.
- `SeoFiles.kt` (sitemap, robots, llms.txt, llms-full.txt), `StructuredData.kt` (JSON-LD),
  `AiCatalog.kt` (ARD `ai-catalog.json`), `OgImage.kt` (per-page social cards, touch icon).
- `src/main/resources/site/` — `site.css` (inlined), `site.js` (copy buttons), `favicon.svg`.

## Gotchas

- CSS is inlined into every page on purpose (one request, no render-blocking). Keep it small;
  no web fonts, no frameworks, no client-side rendering.
- Card grids and prose wrap long tokens (`overflow-wrap: anywhere`, `min-width: 0`,
  `minmax(min(100%, …), 1fr)`). A usage line, URL, or descriptor must stay inside its card
  and must not widen the page (#133). Command labels insert a `<wbr>` after each `|` so the
  break prefers the bar. Do not put `height: 100%` back on a card link: the percentage height
  makes the row height cyclical and the topics stop sharing a top edge (#132).
- Every page gets an `index.md` twin; agents read Markdown far better than HTML.
- `robots.txt` / `llms.txt` only count at a host root. On `github.io/jdx/` they are ignored by
  crawlers; a custom domain (`SITE_BASE_URL` repo variable) fixes that and writes `CNAME`.
- `JAVA_TOOL_OPTIONS` / `_JAVA_OPTIONS` / `JDK_JAVA_OPTIONS`: the launcher tolerates their
  `Picked up ...` banners since #88, but `ProcessJdxRunner` still unsets them — published
  output must never depend on the host's JVM options.
- Example `--fetch` commands retry a transient Maven Central failure four times
  (2s, then 5s, then 10s) inside `ProcessJdxRunner` / `FixtureFetch`. A checksum
  mismatch (the resolver says `checksum rejected` without `network unreachable`)
  is not retried. The sandbox is recreated every build except `sandbox/cache/jdx/m2`,
  which `pages.yml` caches so a warm runner does not download those coordinates again.
