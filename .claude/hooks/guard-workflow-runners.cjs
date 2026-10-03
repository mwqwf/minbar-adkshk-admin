#!/usr/bin/env node
/**
 * حارس عدّادات سير العمل — PreToolUse على `git push`.
 *
 * يكمّل `guard-paid-actions.cjs`: ذاك يمنع الإطلاق الصريح، وهذا يمنع الإنفاق الذي
 * يُشعله الدفع نفسُه. في المستودعات الخاصّة (دقائقها مدفوعة) يرفض دفع تعديلٍ على
 * `.github/workflows/` يضيف عدّاداً مضاعف الكلفة (macOS ×10 · Windows ×2 · العدّادات
 * الكبيرة والمخصّصة)، ما لم تُسجَّل موافقةٌ ساريةٌ في `ops/FINANCIAL_APPROVALS.json`.
 * يرفض برسالة («deny») ولا يسأل، كي لا تتعلّق جلسةٌ سحابيّة بلا مالك.
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const GUARDED = new Set(['mwqwf/quranrafiq', 'mwqwf/fiqhlab', 'mwqwf/yarmouk-media', 'mwqwf/minbar-cloud',
  'mwqwf/minbar-adkshk', 'mwqwf/minbar-adkshk-admin']);
const STANDARD = /^(ubuntu-(latest|\d{2}\.04)|ubuntu-\d{2}\.04-arm|ubuntu-24\.04-arm|\$\{\{.*\}\})$/;

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const run = (c, cwd) => { try { return execSync(c, { cwd: cwd || root, stdio: ['ignore', 'pipe', 'ignore'] }).toString(); } catch { return ''; } };

function approved(repo) {
  try {
    const list = JSON.parse(fs.readFileSync(path.join(root, 'ops', 'FINANCIAL_APPROVALS.json'), 'utf8'));
    const today = new Date().toISOString().slice(0, 10);
    return list.some((a) => String(a.repo || '').toLowerCase() === repo && a.reason && a.cap && String(a.until || '') >= today);
  } catch { return false; }
}

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  try {
    const p = JSON.parse(input || '{}');
    const cmd = String((p.tool_input || {}).command || '');
    if (!/\bgit\b[^\n;&|]*\bpush\b/.test(cmd)) process.exit(0);
    const m = cmd.match(/\bgit\s+-C\s+(\S+)/);
    const cwd = m ? path.resolve(p.cwd || root, m[1].replace(/^["']|["']$/g, '')) : p.cwd || root;
    const url = run('git config --get remote.origin.url', cwd).trim();
    const r = url.match(/[/:]([^/:]+)\/([^/]+?)(?:\.git)?$/);
    const repo = r ? `${r[1]}/${r[2]}`.toLowerCase() : '';
    if (!GUARDED.has(repo) || approved(repo)) process.exit(0);

    const base = run('git rev-parse --verify -q @{u}', cwd).trim() || run('git rev-parse --verify -q origin/HEAD', cwd).trim();
    if (!base) process.exit(0);
    const diff = run(`git diff ${base}..HEAD -U0 -- .github/workflows`, cwd);
    const bad = [];
    for (const l of diff.split('\n')) {
      const x = l.match(/^\+\s*runs-on:\s*(.+?)\s*$/);
      if (!x) continue;
      const labels = x[1].replace(/^\[|\]$/g, '').split(',').map((s) => s.trim().replace(/^["']|["']$/g, ''));
      for (const lb of labels) if (lb && !STANDARD.test(lb)) bad.push(lb);
    }
    if (!bad.length) process.exit(0);
    process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny',
      permissionDecisionReason: `حارس العدّادات: ${repo} خاصٌّ ودقائقه مدفوعة، والدفع يضيف عدّاداً مضاعف الكلفة (${[...new Set(bad)].join('، ')}). ` +
        'استعمل ubuntu-latest، أو انقل الثقيل إلى المستودع العامّ rafiq-align-ci، أو سجّل موافقةً ماليّةً صريحةً من المالك في ops/FINANCIAL_APPROVALS.json. ثمّ امضِ إلى البند التالي.' } }));
  } catch {}
  process.exit(0);
});
