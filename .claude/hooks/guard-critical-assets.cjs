#!/usr/bin/env node
/**
 * حارس الأصول الحرجة — PreToolUse.
 *
 * سببه حادثة 2026-08-12: طلب تنظيف عام استثنى المستخدم فيه صراحةً كل ما يخصّ
 * منبر، فحُذف مع ذلك مفتاح رفع منبر الأصلي ولم يعد له وجود. هذا الحارس يمنع
 * تكرارها آلياً بدل الاعتماد على انتباه النموذج:
 *
 *  1) أي أمر إتلافي (حذف/نقل/كتابة فوق/تهيئة git) يلمس مواد التوقيع
 *     (secure-keys، ‎*.jks، ‎*.keystore، signing.properties، ‎*.pem/‎*.p12) → يُمنع.
 *  2) أي أمر إتلافي واسع (rm -rf / Remove-Item -Recurse / rmdir /s) يلمس مسار
 *     مشروع منبر → يُمنع ويُطلب تأكيد صريح ومحدَّد من المستخدم.
 *
 * المنع هنا لا يُلغي الحذف المشروع: يبقى ممكناً بأمر مُصاغ لهدف واحد محدَّد
 * بعد طلب صريح من المستخدم على ذلك الملف بعينه (خارج الأنماط الجارفة أعلاه).
 */

let input = '';
process.stdin.on('data', (c) => (input += c));
process.stdin.on('end', () => {
  let payload = {};
  try {
    payload = JSON.parse(input || '{}');
  } catch {
    process.exit(0); // لا نعطّل العمل بسبب خلل في التحليل
  }

  const tool = payload.tool_name || '';
  if (!['Bash', 'PowerShell'].includes(tool)) process.exit(0);

  const cmd = String(payload.tool_input?.command || '');
  if (!cmd.trim()) process.exit(0);

  const lower = cmd.toLowerCase();

  // (0) حارس الشجرة المشتركة لرفيق القرآن — أمر المشرف 2026-09-02 بعد ثالث
  //     كنسٍ لعملٍ حيّ (stash 5dcfc70 عند 17:40 كنس 8 ملفات لست جلسات).
  //     في شجرة QuranRafiq يُمنع كل ما يعيد كتابة شجرة العمل أو التاريخ المشترك:
  //     stash (عدا list/show) · reset --hard/--merge/--keep أو إلى مرجع · rebase ·
  //     pull --rebase · autostash · checkout (عدا -b) · restore · switch -f · clean.
  //     المسموح: fetch ثم merge --ff-only ثم push؛ الاسترجاع بـ git show <ref>:<path>.
  const cwd = String(payload.cwd || '');
  const inRafiq = /quranrafiq/i.test(cwd) || /quranrafiq/i.test(cmd);
  // نفحص الأفعال لا الألفاظ: تُزال أجسام heredoc والسلاسل المقتبسة (رسائل
  // الإيداع، نصوص التوثيق) قبل المطابقة كي لا يُمنع من يكتب اسم أمرٍ في رسالة.
  const acts = lower
    .replace(/<<-?\s*'?"?([a-z_][a-z0-9_]*)'?"?[\s\S]*?\n\1\b/g, ' ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''");
  if (inRafiq && /\bgit\b/.test(acts)) {
    const G = String.raw`\bgit\s+(?:-c\s+\S+\s+)*`;
    const has = (re) => new RegExp(G + re).test(acts);
    const gitDanger =
      (has(String.raw`stash\b`) && !has(String.raw`stash\s+(?:list|show)\b`)) ||
      has(String.raw`reset\s+(?:--hard|--merge|--keep)`) ||
      has(String.raw`reset\s+(?:-q\s+)?(?:origin/|head~|head\^|[0-9a-f]{7,40}\b)`) ||
      has(String.raw`rebase\b`) ||
      has(String.raw`pull\b[^;&|]*(?:--rebase|--autostash)`) ||
      /--autostash/.test(acts) ||
      (has(String.raw`checkout\b`) && !has(String.raw`checkout\s+-b\b`)) ||
      has(String.raw`restore\b`) ||
      has(String.raw`switch\s+(?:-f|--force|--discard-changes)`) ||
      has(String.raw`clean\b`);
    if (gitDanger) {
      console.error(
        'مُنع بحارس الشجرة المشتركة (رفيق القرآن): هذا الأمر يعيد كتابة شجرة العمل أو التاريخ ' +
          'المشترك (stash/reset/rebase/pull --rebase/autostash/checkout/restore/clean).\n' +
          'سبب القاعدة: ثلاث حوادث كنسٍ لعملٍ حيّ في 2026-09-02 آخرها stash 5dcfc70 (8 ملفات لست جلسات).\n' +
          'المسموح: git fetch ثم git merge --ff-only origin/main ثم git push؛ الإيداع بمسارات صريحة؛ ' +
          'الاسترجاع بـ git show <ref>:<path> > <path>. وإن تعذّر ff-only فأبلغ المشرف github-f4 ولا تلتفّ.',
      );
      process.exit(2);
    }
  }

  // أفعال إتلافية محتملة
  const destructive =
    /\brm\b|\brmdir\b|\bdel\b|\berase\b|remove-item|\bmv\b|move-item|\bshred\b|\btruncate\b|git\s+clean|git\s+reset\s+--hard|>\s*\/dev\/null\s*2>&1\s*;?\s*$/.test(
      lower,
    ) || /\bdd\s+if=/.test(lower);

  if (!destructive) process.exit(0);

  // (1) مواد التوقيع والأسرار — ممنوعة مطلقاً
  const secretPattern =
    /secure-keys|\.jks\b|\.keystore\b|signing\.properties|upload[-_]?key|\.pem\b|\.p12\b|key\.properties|google-services\.json|serviceaccount/i;

  if (secretPattern.test(cmd)) {
    console.error(
      'مُنع بحارس الأصول الحرجة: هذا أمر إتلافي يلمس مادة توقيع/سرّاً ' +
        '(مفتاح، keystore، شهادة، أو ملف إعداد سرّي).\n' +
        'سبب القاعدة: فقدان مفتاح رفع منبر في 2026-08-12 رغم استثنائه صراحةً.\n' +
        'لا تُعِد المحاولة بصيغة أخرى. اطلب من المستخدم تنفيذها بنفسه إن كانت مقصودة.',
    );
    process.exit(2); // 2 = منع مع إعادة السبب إلى النموذج
  }

  // (2) حذف جارف يلمس مشاريع منبر
  const sweeping =
    /rm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)/.test(lower) ||
    /remove-item[^\n]*-recurse/.test(lower) ||
    /rmdir\s+\/s/.test(lower) ||
    /del\s+\/s/.test(lower);

  const minbarPattern = /minbar|menbar|منبر|ادكصهك|adkshk|adkassahk|ishaqiyin/i;

  if (sweeping && minbarPattern.test(cmd)) {
    console.error(
      'مُنع بحارس الأصول الحرجة: حذف جارف (‎-rf/‎-Recurse) يلمس مسار مشروع منبر.\n' +
        'القاعدة: مشاريع منبر مستثناة من أي تنظيف عام ما لم يطلب المستخدم حذف ' +
        'هذا المسار بعينه صراحةً وبالاسم.\n' +
        'الصواب: احصر الأمر في ملف/مجلد واحد محدَّد، أو اعرض الخطة على المستخدم أولاً.',
    );
    process.exit(2);
  }

  process.exit(0);
});
