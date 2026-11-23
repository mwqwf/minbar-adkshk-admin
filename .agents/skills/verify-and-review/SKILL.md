---
name: verify-and-review
description: Verification ladder and independent review before any commit, PR, merge, or "done" claim — cheapest checks first, then heavier ones; per-project commands from the repo's docs/agents/VERIFY.md; evidence format; rules against weakening tests/guards; flaky-test policy; three-strikes rule; Verified commit trailer. Use before committing code, data, workflow, or doc changes, before merging a PR, and before reporting success. سلّم التحقّق والمراجعة قبل أي إيداع أو إعلان «أُنجز».
license: Proprietary (mwqwf) — internal
metadata:
  unified-from: "QuranRafiq verify + rafiq-align-ci verify (2026-11-23)"
  related: "vendor/verification-before-completion, vendor/requesting-code-review (obra/superpowers, MIT)"
---

# التحقّق والمراجعة قبل الإعلان

> «أُنجز» بلا فحصٍ شغّلتَه ودليلٍ أمامك ليست إنجازاً. الأرخص أولاً، وإن سقط رخيصٌ فلا حاجة للثقيل.

## ١. أوامر المشروع
الأوامر الحرفية لكل مستودع في `docs/agents/VERIFY.md` (جدول: نوع التغيير ← رخيص ← أثقل ← الأثقل). إن غاب الملف فاستخرج الأوامر من سير عمل CI (`pr-check`/`app-test`) ومن `scripts/check.sh` واكتبه.

السلّم العامّ إن لم يُحدّد المشروع غيره:
| النوع | رخيص | أثقل | الأثقل |
|---|---|---|---|
| Kotlin/Compose | فحوص بايثون/ساكنة المجاورة · `./gradlew :app:compileDebugKotlin` | `./gradlew :app:testDebugUnitTest` · `lintDebug` | المحاكي في CI |
| Cloudflare Worker | `npx tsc --noEmit` | `npm test` (vitest) | نشر معاينة + قراءة العنوان |
| بيانات قرآنية/حديثية | مُدقّق السلامة (`arabic-rtl-and-islamic-typography` §٣) | اختبار الوحدة للنصّ | مقارنة differential قديم/جديد وعدّ الفروق |
| workflows | `python3 -c "import yaml,sys;yaml.safe_load(open(sys.argv[1]))" f.yml` + مراجعة `on:` | `actionlint` | لا تشغيل حيّ إلا لضرورة مسمّاة |
| بايثون | `python3 -m py_compile` | `python3 -m unittest discover` | — |
| توثيق/مهارات فقط | `git diff --check` + فحص الروابط | `tools/validate.py` في agent-skills | — |

## ٢. أين يُشغَّل
بيئة الجلسة/حاسوب الوكيل أولاً؛ ثم CI المجانيّ (مستودع عام) أو المشغّل الذاتيّ؛ والمستودع الخاص لا يُطلق تشغيلة إلا لضرورة مسمّاة (`mwqwf-workflow` §٧). قبل الدفع افحص `on:` في `.github/workflows/*.yml`: هل تطابق مساراتُك `paths` محفّزٍ مدفوع؟

## ٣. الدليل في التقرير
لكل فحص: **الأمر حرفياً + سطر المخرج الدالّ** (`Ran 14 tests … OK`، `BUILD SUCCESSFUL`) أو لقطة للبصريّ. ما لم يُشغَّل يُذكر بسببه.

## ٤. قواعد لا تُكسر
1. التغيير لا يعدّل الفحص الذي يحكم عليه: لا إضعاف حارس أو اختبار أو عتبة ليمرّ. تعديل الحارس يجعله أصعب خداعاً فقط. قواعد المراجعة تُقرأ من الفرع الرئيسيّ لا من فرعك.
2. الاختبار المتقلّب لا يُتخطّى ولا يُضاف له retry/sleep: يُصلح سببه أو يُحذف بسببٍ مكتوب.
3. ثلاث جولات إصلاحٍ فاشلة لفحصٍ واحد ⇒ توقّف عن الترقيع، دوّن العائق بدليله، وامضِ لبندٍ آخر (طبّق `systematic-debugging`).
4. خطأ فائت تكرّر مرّتين ⇒ قاعدة في `REVIEW.md` أو حارس آليّ.

## ٥. مراجعة مستقلّة (لكل تغيير غير بسيط)
اطلب مراجعة بسياقٍ جديد (وكيل فرعيّ للقراءة فقط إن أتاحته المنصّة، أو جلسة أخرى، أو مراجعة ذاتية صارمة بقائمة `vendor/requesting-code-review`): يقارن الفرق بالطلب ويبلغ ما يمسّ الصحّة والأمان فقط. أصلح ما تأكّد، والجولات ≤ ٣. وعند تلقّي مراجعة طبّق `receiving-code-review` (تحقّق قبل القبول، لا مجاملة).

## ٦. وسم الإيداع
آخر سطر في رسالة الإيداع:
```
Verified: gradle testDebugUnitTest + lintDebug
Verified: none (توثيق فقط)
```
`none` صراحةً مع السبب إن لم يُتحقّق، ولا تكتب فحصاً لم تشغّله.
