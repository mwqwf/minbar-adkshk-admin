#!/usr/bin/env node
/**
 * حفظ الحالة قبل التلخيص — PreCompact + SessionStart.
 *
 * precompact ← قبل أن تلخّص الجلسة الطويلة سياقها يكتب `.claude/handoff.md`:
 *              الفرع والرأس وآخر الإيداعات وما لم يُودَع وما لم يُدفع وبنود الخطّة.
 * start      ← بعد التلخيص أو الاستئناف يضع تلك اللقطة في سياق الجلسة، وفي كلّ بدءٍ
 *              يذكّر بالبنود المفتوحة في `.claude/plan.md` والمتعذّرة في `blocked.md`.
 * لا يكتب إلا ملفّه، ولا يعطّل شيئاً عند خلل.
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const mode = process.argv[2];
const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const dir = path.join(root, '.claude');
const out = path.join(dir, 'handoff.md');
const git = (a) => { try { return execSync(`git ${a}`, { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); } catch { return ''; } };
const read = (f) => { try { return fs.readFileSync(path.join(dir, f), 'utf8'); } catch { return ''; } };
const items = (txt, re) => txt.split('\n').filter((l) => re.test(l)).join('\n');

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  try {
    const p = JSON.parse(input || '{}');
    const plan = read('plan.md');
    const open = items(plan, /^\s*[-*]\s+\[ \]/);
    const blocked = items(plan, /^\s*[-*]\s+\[!\]/);

    if (mode === 'precompact') {
      const md = [
        `# لقطة قبل التلخيص — ${new Date().toISOString()}`,
        `الفرع: \`${git('branch --show-current')}\` · الرأس: \`${git('rev-parse --short HEAD')}\` · غير مدفوع: ${git('rev-list --count @{u}..HEAD') || '؟'}`,
        '## آخر الإيداعات', '```', git('log --oneline -10'), '```',
        '## غير المودَع', '```', git('status --short') || '(لا شيء)', '```',
        '## بنود مفتوحة', open || '(لا شيء)',
        '## متعذّرة', blocked || '(لا شيء)',
      ].join('\n');
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(out, md + '\n');
      process.exit(0);
    }

    const parts = [];
    if ((p.source === 'compact' || p.source === 'resume') && fs.existsSync(out))
      parts.push('لقطة الحالة المحفوظة قبل التلخيص (`.claude/handoff.md`):\n' + fs.readFileSync(out, 'utf8'));
    else if (open) parts.push('بنود مفتوحة في `.claude/plan.md` — أكملها ولا تتوقّف قبلها:\n' + open);
    if (open || p.source === 'startup')
      parts.push('قاعدة المالك: لا توقّف. اكتب خطّتك بنوداً `- [ ]` في `.claude/plan.md`؛ والعائق يُجرَّب له بديل، فإن استحال صار `- [!]` بسببه وسُجّل في `.claude/blocked.md` ومُضي عنه.');
    if (parts.length) process.stdout.write(parts.join('\n\n'));
  } catch {}
  process.exit(0);
});
