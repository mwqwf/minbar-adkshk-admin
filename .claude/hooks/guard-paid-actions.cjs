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
 * القائمة أدناه، لا يمنع بل **يسأل المالك** («ask») مع السبب. فالموافقة
 * الصريحة تمضي، والإطلاق السهو يقف.
 *
 * ما لا يفعله: لا يرى التشغيل الذي يُشعله الدفع بمسارٍ (`on: push: paths`)؛
 * ذاك يحكمه نصّ `github-actions-cost-control`. والإلغاء (`cancel`) لا يُسأل عنه
 * لأنّه يوفّر ولا ينفق.
 */

// المستودعات ذات الإيقاف الماليّ: الخاصّة، والمصنع بنصّ CLAUDE.md فيه.
// ⚠️ minbar-cloud خاصٌّ لكنّه مستثنى عمداً: نشرُ منبر آليٌّ بأمر المالك (2026-09-12).
const GUARDED = new Set([
  'mwqwf/quranrafiq',
  'mwqwf/fiqhlab',
  'mwqwf/yarmouk-media',
  'mwqwf/wf-scope-probe',
  'mwqwf/mutafail-factory',
]);

const { execSync } = require('child_process');

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
  const reason =
    `حارس التشغيل المدفوع: ${what} في ${repo}، وهو تحت الإيقاف الماليّ (2026-09-18).\n` +
    'لا يمضي إلا بموافقةٍ ماليّةٍ صريحةٍ جديدة من المالك تسمّي السبب والتكلفة والسقف.\n' +
    'البديل المجّانيّ أوّلاً: فحصٌ محلّيٌّ في الجلسة، أو المستودع العامّ mwqwf/rafiq-align-ci.';
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'ask',
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
  const cmd = String(ti.command || '');
  const lower = cmd.toLowerCase();
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
