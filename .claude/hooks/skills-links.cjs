#!/usr/bin/env node
/**
 * SessionStart — يضمن أن `.claude/skills/<name>` يشير إلى `.agents/skills/<name>` (المصدر الوحيد).
 * على Windows بلا دعم الروابط الرمزية يُخرج git الرابطَ ملفاً نصّياً؛ فيستبدله هذا الخطاف بوصلة (junction)
 * لا تحتاج صلاحية مدير، ويُخفي التغيير عن git (skip-worktree) كي لا يُودَع خطأً.
 * يحلّ محلّ skills-drift.cjs: لا نسخ مكرّرة بعد اليوم، فلا انحراف. المصدر: mwqwf/agent-skills.
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const ag = path.join(root, '.agents', 'skills');
const cl = path.join(root, '.claude', 'skills');
try {
  if (!fs.existsSync(ag)) process.exit(0);
  fs.mkdirSync(cl, { recursive: true });
  const fixed = [];
  for (const d of fs.readdirSync(ag, { withFileTypes: true })) {
    if (!d.isDirectory() || !fs.existsSync(path.join(ag, d.name, 'SKILL.md'))) continue;
    const link = path.join(cl, d.name);
    let st = null;
    try { st = fs.lstatSync(link); } catch {}
    if (st && (st.isSymbolicLink() || st.isDirectory()) && fs.existsSync(path.join(link, 'SKILL.md'))) continue;
    if (st) fs.rmSync(link, { recursive: true, force: true });
    fs.symlinkSync(path.join(ag, d.name), link, process.platform === 'win32' ? 'junction' : 'dir');
    fixed.push(d.name);
  }
  if (fixed.length) {
    try { execSync(`git update-index --skip-worktree ${fixed.map((n) => `".claude/skills/${n}"`).join(' ')}`, { cwd: root, stdio: 'ignore' }); } catch {}
    process.stdout.write(`أُصلحت روابط ${fixed.length} مهارة في .claude/skills (المصدر .agents/skills).\n`);
  }
} catch {}
process.exit(0);
