#!/usr/bin/env node
/**
 * لا توقّف — Stop (أمر المالك 2026-10-03: «لا تتوقّف الجلسة تحت أيّ ظرف»).
 *
 * الخطّة في `.claude/plan.md` بنودٌ مربّعة:
 *   - [ ] بندٌ مفتوح   - [x] منجز   - [!] متعذّرٌ مسجَّلٌ سببُه (تُجووز)
 * ما دام بندٌ مفتوحاً يُرفض التوقّف ويُعاد Claude إلى العمل. فإن أعاقه شيء:
 * يجرّب الطرق البديلة، فإن استحال يجعل البند `[!]` بسببه وما جرّبه، ويُلحقه بـ
 * `.claude/blocked.md`، ويمضي إلى التالي — فلا يقف عند عائقٍ واحد أبداً.
 * وإن تكرّر الرفض بلا تغيّرٍ في الخطّة ثلاث مرّات يُؤمر بتسجيل البند الحاليّ `[!]`
 * والمضيّ (يكسر الدوران الفارغ دون أن يأذن بالتوقّف).
 * وفي السحابة: لا توقّف وفي الشجرة عملٌ غيرُ مودَعٍ أو إيداعٌ غيرُ مدفوع، لأنّ الحاوية تُمحى.
 * ولا يعطّل شيئاً عند خللٍ في نفسه.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const planPath = path.join(root, '.claude', 'plan.md');
const git = (a) => execSync(`git ${a}`, { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
const block = (msg) => { console.error(msg); process.exit(2); };

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  try {
    const p = JSON.parse(input || '{}');
    const sid = String(p.session_id || 'x').replace(/[^\w-]/g, '');
    const plan = fs.existsSync(planPath) ? fs.readFileSync(planPath, 'utf8') : '';
    const open = plan.split('\n').filter((l) => /^\s*[-*]\s+\[ \]/.test(l));

    if (open.length) {
      const h = crypto.createHash('sha1').update(plan).digest('hex');
      const mark = path.join(os.tmpdir(), `claude-nostop-${sid}`);
      let [lastH, n] = fs.existsSync(mark) ? fs.readFileSync(mark, 'utf8').split(' ') : ['', '0'];
      n = lastH === h ? Number(n) + 1 : 1;
      fs.writeFileSync(mark, `${h} ${n}`);
      const cur = open[0].replace(/^\s*[-*]\s+\[ \]\s*/, '');
      block(
        (n >= 3
          ? `⛔ رُفض التوقّف ${n} مرّاتٍ والخطّة لم تتغيّر. اجعل البند «${cur}» الآن \`- [!]\` بسببه وما جرّبتَه، وألحقه بـ\`.claude/blocked.md\`، ثمّ امضِ إلى التالي.\n`
          : `⛔ لا توقّف (أمر المالك): في \`.claude/plan.md\` ${open.length} بنداً مفتوحاً. التالي: «${cur}».\n`) +
          'إن أعاقك شيءٌ فجرّب طريقاً بديلاً (أداةً أخرى، سير عملٍ، مستودعاً، مصدراً). فإن استحال حقّاً ' +
          'فاجعله `- [!]` بسببه ونصِّ الخطأ وما جرّبتَ، وسجّله في `.claude/blocked.md`، وامضِ إلى ما بعده. ' +
          'ولا تسأل المالك وتقف: اكتب السؤال في السجلّ وتابع.',
      );
    }

    if (process.env.CLAUDE_CODE_REMOTE === 'true' && !p.stop_hook_active) {
      let dirty = '', ahead = 0;
      try { dirty = git('status --porcelain --untracked-files=no'); } catch {}
      try { ahead = Number(git('rev-list --count @{u}..HEAD')) || 0; } catch {}
      if (dirty || ahead)
        block(
          '☁️ الحاوية السحابيّة تُمحى عند انتهاء الجلسة: ' +
            (dirty ? 'في الشجرة تعديلاتٌ غيرُ مودَعة. ' : '') +
            (ahead ? `و${ahead} إيداعاً غيرَ مدفوع. ` : '') +
            'أودِعها بمساراتٍ صريحة وادفعها إلى فرعك قبل الإنهاء (أو قل في سطرٍ لماذا تُترك).',
        );
    }
  } catch {}
  process.exit(0);
});
