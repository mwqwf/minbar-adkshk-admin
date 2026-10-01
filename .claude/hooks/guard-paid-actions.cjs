#!/usr/bin/env node
/**
 * حارس التشغيل المدفوع — PreToolUse.
 *
 * سببه الإيقاف الماليّ (أمر المالك 2026-09-18): لا تشغيلَ لـGitHub Actions
 * قابلاً للفوترة بلا موافقةٍ ماليّةٍ صريحةٍ جديدة. والمستودعات الخاصّة دقائقُها
 * مدفوعة، فإطلاقُ سير عملٍ فيها أو إعادةُ تشغيله إنفاقٌ لا يُستردّ.
 *
 * ما يفعله: إذا طُلب إطلاقٌ صريح (`gh workflow run` · `gh run rerun` ·
 * `gh api …/dispatches|rerun` · أداة `actions_run_trigger`) في مستودعٍ من
 * القائمة أدناه **يرفضه برسالةٍ** («deny») فيمضي Claude إلى البديل المجّانيّ
 * أو البند التالي. ⛔ لا «ask»: السؤال يُعلّق الجلسة السحابيّة التي لا مالك
 * أمامها ساعاتٍ — وهذا ما يريد المالك ألّا يقع (أمره 2026-10-01).
 *
 * الموافقة الماليّة المسمّاة تُسجَّل في `ops/FINANCIAL_APPROVALS.json` بالمستودع:
 *   [{"repo":"mwqwf/quranrafiq","until":"2026-10-05","reason":"…","cap":"…"}]
 * فيمرّ الإطلاق آليّاً حتى `until` (شاملاً). يكتبها Claude فقط بعد موافقةٍ
 * صريحةٍ من المالك تسمّي السبب والتكلفة والسقف، فتبقى مؤرّخةً في git.
 *
 * ما لا يفعله: لا يرى التشغيل الذي يُشعله الدفع بمسارٍ (`on: push: paths`)؛
 * ذاك يحكمه نصّ `github-actions-cost-control`. والإلغاء (`cancel`) لا يُسأل عنه
 * لأنّه يوفّر ولا ينفق.
 */

// المستودعات الخاصّة (دقائقها مدفوعة).
// ⚠️ minbar-cloud خاصٌّ لكنّه مستثنى عمداً: نشرُ منبر آليٌّ بأمر المالك (2026-09-12).
const GUARDED = new Set([
  'mwqwf/quranrafiq',
  'mwqwf/fiqhlab',
  'mwqwf/yarmouk-media',
  'mwqwf/wf-scope-probe',
]);
// ⚠️ mutafail-factory أُخرج 2026-10-01: عامٌّ ودقائقه مجّانيّة، وإيقافه الماليّ رُفع في 2026-09-20.

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

function approved(repo) {
  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  try {
    const list = JSON.parse(fs.readFileSync(path.join(root, 'ops', 'FINANCIAL_APPROVALS.json'), 'utf8'));
    const today = new Date().toISOString().slice(0, 10);
    return (Array.isArray(list) ? list : []).some(
      (a) => String(a.repo || '').toLowerCase() === repo && a.reason && a.cap && String(a.until || '') >= today,
    );
  } catch {
    return false;
  }
}

function repoOfCwd(cwd) {
  try {
    const url = execSync('git config --get remote.origin.url', {
      cwd: cwd || process.cwd(),
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim();
    // يطابق https://…/owner/repo(.git) و git@…:owner/repo(.git) وعناوين الوسيط المحلي
    const m = url.match(/([^/:]+)\/([^/]+?)(?:\.git)?$/);
    return m ? `${m[1]}/${m[2]}`.toLowerCase() : '';
  } catch {
    return '';
  }
}

function ask(repo, what) {
  if (approved(repo)) process.exit(0);
  const reason =
    `حارس التشغيل المدفوع: ${what} في ${repo}، وهو تحت الإيقاف الماليّ (2026-09-18).\n` +
    'لا يمضي إلا بموافقةٍ ماليّةٍ صريحةٍ جديدة من المالك تسمّي السبب والتكلفة والسقف.\n' +
    'البديل المجّانيّ أوّلاً: فحصٌ محلّيٌّ في الجلسة، أو المستودع العامّ mwqwf/rafiq-align-ci.\n' +
    'لا تنتظر: سجّل الحاجة في وثيقة الحالة وامضِ إلى البند التالي. وإن وافق المالك صراحةً ' +
    'فأضِف سطراً في ops/FINANCIAL_APPROVALS.json (repo · until · reason · cap).';
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason: reason,
      },
    }),
  );
  process.exit(0);
}

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  let p = {};
  try {
    p = JSON.parse(input || '{}');
  } catch {
    process.exit(0); // لا نعطّل العمل بخلل تحليل
  }
  const tool = String(p.tool_name || '');
  const ti = p.tool_input || {};

  // (1) أداة GitHub MCP
  if (/actions_run_trigger$/.test(tool)) {
    const method = String(ti.method || '').toLowerCase();
    if (/cancel/.test(method)) process.exit(0);
    const repo = `${ti.owner || ''}/${ti.repo || ''}`.toLowerCase();
    if (GUARDED.has(repo)) ask(repo, `إطلاقٌ عبر actions_run_trigger (${method || 'run'})`);
    process.exit(0);
  }

  // (2) أوامر الطرفيّة
  if (tool !== 'Bash' && tool !== 'PowerShell') process.exit(0);
  // الأفعال لا الألفاظ: تُزال أجسام heredoc والسلاسل المقتبسة والتعليقات قبل المطابقة، فلا يُمنع
  // من يكتب «gh workflow run» في رسالة إيداعٍ أو وثيقة (إيجابيةٌ كاذبة مقيسة 2026-10-01).
  const lower = String(ti.command || '')
    .toLowerCase()
    .replace(/<<-?\s*'?"?([a-z_][a-z0-9_]*)'?"?[\s\S]*?\n\1\b/g, ' ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''")
    .replace(/(^|\s)#[^\n]*/g, ' ');
  const launches =
    /\bgh\s+workflow\s+run\b/.test(lower) ||
    /\bgh\s+run\s+rerun\b/.test(lower) ||
    (/\bgh\s+api\b/.test(lower) && /\/actions\/(?:workflows\/[^\s/]+\/dispatches|runs\/\d+\/rerun)/.test(lower));
  if (!launches) process.exit(0);

  const explicit =
    lower.match(/(?:-r|--repo)[\s=]+([\w.-]+\/[\w.-]+)/) ||
    lower.match(/repos\/([\w.-]+\/[\w.-]+)\/actions/);
  const repo = explicit ? explicit[1] : repoOfCwd(p.cwd);
  if (GUARDED.has(repo)) ask(repo, 'إطلاقٌ صريح لسير عمل');
  process.exit(0);
});
