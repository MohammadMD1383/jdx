// Lighthouse budget report for the jdx website (run by .github/workflows/pages.yml).
//
//   node site/lighthouse/check.mjs http://127.0.0.1:8000/jdx/            # warn only (CI default)
//   node site/lighthouse/check.mjs http://127.0.0.1:8000/jdx/ --strict   # exit 1 on a miss
//
// Runs Lighthouse (pinned in package.json) on a representative page of each kind, mobile and
// desktop, and reports every missed budget. WARN-ONLY by design (owner decision on PR #89):
// quality scores must never stop the site from deploying. Misses surface as GitHub warning
// annotations and a job-summary table; only the docs-drift checks in :site:buildSite block.
// Budgets are strict on purpose — the site is static HTML with inlined CSS and no web fonts,
// so a miss is a regression someone introduced, worth a look even when it does not block.
//
// Host-root audits are skipped on purpose: crawlers only read /robots.txt and /llms.txt at the
// root of a host, and a GitHub *project* site lives under /<repo>/. They pass once the site has
// a custom domain (the generator then writes CNAME and serves these files from the root).
import lighthouse from 'lighthouse';
import * as chromeLauncher from 'chrome-launcher';
import {appendFileSync, mkdirSync, writeFileSync} from 'node:fs';

const strict = process.argv.includes('--strict');
const base = (process.argv.slice(2).find((a) => !a.startsWith('--')) || 'http://127.0.0.1:8000/jdx/').replace(/\/?$/, '/');
const summaryRows = [];
const pages = ['', 'docs/', 'docs/quickstart/', 'docs/reference/members/', 'integrations/claude-code/', 'faq/', 'changelog/'];
const hostRootAudits = new Set(['robots-txt', 'llms-txt']);
const budgets = {
  categories: {performance: 0.95, accessibility: 1, 'best-practices': 1},
  metrics: {'largest-contentful-paint': 2500, 'cumulative-layout-shift': 0.1, 'total-blocking-time': 200},
  // Every audit in these categories must pass (score 1) unless listed in hostRootAudits.
  strictCategories: ['seo', 'agentic-browsing'],
};

const chrome = await chromeLauncher.launch({chromeFlags: ['--headless=new', '--no-sandbox']});
const failures = [];
mkdirSync('lighthouse-reports', {recursive: true});
try {
  for (const formFactor of ['mobile', 'desktop']) {
    for (const page of pages) {
      const url = base + page;
      const config = formFactor === 'desktop' ? (await import('lighthouse/core/config/desktop-config.js')).default : undefined;
      const result = await lighthouse(url, {port: chrome.port, output: 'html', logLevel: 'error'}, config);
      const lhr = result.lhr;
      writeFileSync(`lighthouse-reports/${formFactor}-${page.replace(/\//g, '_') || 'home'}.html`, result.report);
      const scores = Object.values(lhr.categories).map((c) => `${c.id}=${Math.round(c.score * 100)}`).join(' ');
      console.log(`${formFactor.padEnd(7)} /${page.padEnd(28)} ${scores}`);
      summaryRows.push(`| ${formFactor} | /${page} | ${Object.values(lhr.categories).map((c) => Math.round(c.score * 100)).join(' | ')} |`);
      if (summaryRows.length === 1) summaryRows.unshift(`| form | page | ${Object.values(lhr.categories).map((c) => c.title).join(' | ')} |`, `|---|---|${Object.values(lhr.categories).map(() => '---').join('|')}|`);
      for (const [id, min] of Object.entries(budgets.categories)) {
        const score = lhr.categories[id]?.score ?? 0;
        if (score < min) failures.push(`${formFactor} /${page}: ${id} ${Math.round(score * 100)} < ${min * 100}`);
      }
      for (const [id, max] of Object.entries(budgets.metrics)) {
        const value = lhr.audits[id]?.numericValue ?? Infinity;
        if (value > max) failures.push(`${formFactor} /${page}: ${id} ${value.toFixed(2)} > ${max}`);
      }
      for (const category of budgets.strictCategories) {
        for (const ref of lhr.categories[category]?.auditRefs ?? []) {
          const audit = lhr.audits[ref.id];
          if (hostRootAudits.has(ref.id) || audit.score === null || ref.weight === 0) continue;
          if (audit.score < 1) failures.push(`${formFactor} /${page}: ${category} audit '${ref.id}' failed: ${audit.title}`);
        }
      }
    }
  }
} finally {
  await chrome.kill();
}

const summaryFile = process.env.GITHUB_STEP_SUMMARY;
if (summaryFile) {
  const lines = ['## Lighthouse', '', ...summaryRows, ''];
  lines.push(failures.length ? `**${failures.length} budget(s) missed** (warning only — deploy is not blocked):` : 'All budgets met.');
  for (const f of failures) lines.push(`- ${f}`);
  appendFileSync(summaryFile, lines.join('\n') + '\n');
}

if (failures.length) {
  // GitHub turns these into yellow annotations on the PR and the run.
  if (process.env.GITHUB_ACTIONS) for (const f of failures) console.log(`::warning title=Lighthouse budget::${f}`);
  console.error(`\nLighthouse budget missed (${failures.length}):\n` + failures.map((f) => `  - ${f}`).join('\n'));
  console.error('Reports: lighthouse-reports/*.html' + (strict ? '' : ' (warning only; pass --strict to fail)'));
  process.exit(strict ? 1 : 0);
}
console.log('\nLighthouse budgets met.');
