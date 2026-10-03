#!/usr/bin/env node
/**
 * فحص العوائق قبل العمل الطويل — SessionStart (ويُشغَّل يدوياً: `node .claude/hooks/preflight.cjs`).
 *
 * أمر المالك 2026-10-03: «يجب أن تطلب جميع الأذونات وتعرض عليّ جميع العوائق قبل أن أتركه
 * يعمل، لا أن يتوقّف عند أوّل عائق». فهذا يقيس رخيصاً وبالتوازي (≤ 5 ث) ما يعيق عادةً:
 * الشبكة إلى المضيفات التي نحتاجها، والدفع إلى GitHub، والأدوات، والقرص.
 * ثمّ يأمر Claude — قبل أيّ عملٍ طويل — أن يجمع العوائق كلَّها في قائمةٍ واحدة يعرضها على المالك
 * دفعةً واحدة (مع ما يخصّ المهمة: أسرار، مستودعات، موصلات، تشغيل سير عمل)، ثمّ يبدأ.
 * لا يكتب شيئاً، ولا يعطّل الجلسة عند خلل.
 */
const { execSync, spawn } = require('child_process');
const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const sh = (c) => { try { return execSync(c, { cwd: root, stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 }).toString().trim(); } catch { return null; } };

const HOSTS = ['https://api.github.com', 'https://dl.google.com', 'https://api.cloudflare.com',
  'https://pypi.org', 'https://registry.npmjs.org', 'https://www.googleapis.com'];

function probe(u) {
  return new Promise((res) => {
    const p = spawn('curl', ['-s', '-o', '/dev/null', '-m', '4', '-w', '%{http_code}', u]);
    let o = ''; p.stdout.on('data', (d) => (o += d));
    p.on('close', () => res([u, o])); p.on('error', () => res([u, 'ERR']));
  });
}

(async () => {
  const bad = [];
  try {
    for (const [u, code] of await Promise.all(HOSTS.map(probe)))
      if (!code || code === '000' || code === '403' || code === '407' || code === 'ERR') bad.push(`الشبكة: ${u} ← ${code || 'لا ردّ'}`);
    if (sh('git push --dry-run origin HEAD 2>&1') === null) bad.push('الدفع: `git push --dry-run` فشل (صلاحية أو فرع غير مأذون)');
    for (const t of ['node', 'python3', 'git', 'curl']) if (!sh(`command -v ${t}`)) bad.push(`أداة مفقودة: ${t}`);
    if (!sh('command -v gh')) bad.push('أداة `gh` غير مثبّتة — استعمل أدوات GitHub عبر MCP بدلها');
    const kb = Number(sh("df -Pk . | awk 'NR==2{print $4}'"));
    if (kb && kb < 2e6) bad.push(`القرص: المتاح ${(kb / 1e6).toFixed(1)} ج.ب فقط`);
  } catch {}
  process.stdout.write(
    '🧭 فحص العوائق قبل العمل الطويل (أمر المالك):\n' +
      (bad.length ? bad.map((b) => '- ' + b).join('\n') : '- لا عائق آليّاً مقيساً في الشبكة والدفع والأدوات والقرص.') +
      '\n\nقبل أن تبدأ مهمّةً طويلة: اجرد كلَّ ما ستحتاجه (أسرار، مستودعات، موصلات، تشغيل سير عمل، مضيفات شبكة، ' +
      'موافقات ماليّة) وجرّب كلّاً منها تجربةً رخيصة، ثمّ اعرض على المالك **قائمةً واحدة** بكلّ العوائق وما يحلّها ' +
      '(مع رابط الإعداد) واطلب الأذونات كلَّها دفعةً واحدة — لا واحداً بعد واحد. وبعد بدء العمل لا تسأل: ' +
      'ما استجدّ يُجرَّب له بديل، أو يُسجَّل `[!]` في `.claude/plan.md` و`.claude/blocked.md` ويُمضى عنه.\n',
  );
  process.exit(0);
})();
