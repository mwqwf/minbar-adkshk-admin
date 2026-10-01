#!/usr/bin/env node
/**
 * كاشف افتراق المهارات — SessionStart.
 *
 * Claude يقرأ `.claude/skills/` لا `.agents/skills/` (تدقيق 2026-09-20)، والمرآة
 * بينهما تولّدها `sync-skills.sh` على جهاز المالك وحده. فإن عُدّلت مهارةٌ في
 * `.agents/` من جلسةٍ سحابيّة بقيت نسخة Claude قديمةً بلا أن يشعر أحد.
 *
 * يقارن `SKILL.md` لكلّ مهارة في الاتّجاه الذي يهمّ Claude (ما في `.agents`
 * غائبٌ أو مختلفٌ في `.claude`)، ويضع التنبيه في سياق الجلسة. لا يكتب شيئاً.
 */
const fs = require('fs');
const path = require('path');

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const src = path.join(root, '.agents', 'skills');
const dst = path.join(root, '.claude', 'skills');

function read(f) {
  try {
    return fs.readFileSync(f, 'utf8').replace(/\r\n/g, '\n');
  } catch {
    return null;
  }
}

let names = [];
try {
  names = fs.readdirSync(src, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
} catch {
  process.exit(0); // لا مرآة في هذا المستودع
}

const missing = [];
const stale = [];
for (const n of names) {
  const a = read(path.join(src, n, 'SKILL.md'));
  if (a === null) continue;
  const b = read(path.join(dst, n, 'SKILL.md'));
  if (b === null) missing.push(n);
  else if (a !== b) stale.push(n);
}

if (missing.length || stale.length) {
  const lines = ['⚠️ افتراق المهارات بين `.agents/skills` و`.claude/skills` (Claude يقرأ الثانية):'];
  if (missing.length) lines.push(`- غائبة عن نسخة Claude: ${missing.join('، ')}`);
  if (stale.length) lines.push(`- مختلفة عن الأصل: ${stale.join('، ')}`);
  lines.push('اعرف أيّهما الأحدث بـ`git log -1 -- <المسار>` ثمّ انسخه إلى الآخر وأودِعه بمسارٍ صريح.');
  process.stdout.write(lines.join('\n') + '\n');
}
process.exit(0);
