// Lighthouse budget gate for the jdx website (run by .github/workflows/pages.yml).
//
//   node site/lighthouse/check.mjs http://127.0.0.1:8000/jdx/
//
// Runs Lighthouse (pinned in the workflow) on a representative page of each kind, mobile and
// desktop, and fails when a budget is missed. Budgets are deliberately strict: the site is
// static HTML with inlined CSS and no web fonts, so anything below these numbers is a
// regression someone introduced, not noise.
//
// Host-root audits are skipped on purpose: crawlers only read /robots.txt and /llms.txt at the
// root of a host, and a GitHub *project* site lives under /<repo>/. They pass once the site has
// a custom domain (the generator then writes CNAME and serves these files from the root).
import lighthouse from 'lighthouse';
import * as chromeLauncher from 'chrome-launcher';
import {mkdirSync, writeFileSync} from 'node:fs';

const base = (process.argv[2] || 'http://127.0.0.1:8000/jdx/').replace(/\/?$/, '/');
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

if (failures.length) {
  console.error(`\nLighthouse budget missed (${failures.length}):\n` + failures.map((f) => `  - ${f}`).join('\n'));
  console.error('Reports: lighthouse-reports/*.html');
  process.exit(1);
}
console.log('\nLighthouse budgets met.');
