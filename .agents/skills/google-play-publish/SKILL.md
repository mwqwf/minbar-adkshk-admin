---
name: google-play-publish
description: Build, sign, and publish Android apps to Google Play from CI, submit the Data Safety form via the Android Publisher API, read/verify tracks, handle Foreground Service declarations and commit failures. Reads each app's constants (package, tracks policy, workflow, trigger, secret names) from the project file docs/agents/PLAY_APP.md. Use when releasing a version ("ابنِ"، "ارفع"، "انشر")، bumping versionCode, a Data Safety or policy blocker appears in Play Console, or verifying what is live on a track. نشر تطبيقات أندرويد إلى Google Play آلياً.
license: Proprietary (mwqwf) — internal
metadata:
  unified-from: "QuranRafiq/FiqhLab/minbar-adkshk google-play-publish (3 diverged copies, 2026-11-23)"
---

# نشر Google Play آلياً (نسخة موحّدة لكل التطبيقات)

## ٠. اقرأ ثوابت التطبيق أولاً
كل مستودع تطبيق يحمل `docs/agents/PLAY_APP.md` (الحزمة، المسار المأذون افتراضياً، سير العمل، الزناد، أسماء الأسرار — **أسماء لا قيم**، الحرّاس الخاصّة). ⛔ لا تستعر ثابتاً أو إجابة Data Safety من تطبيقٍ آخر. إن غاب الملف فأنشئه من `references/PLAY_APP.template.md` بعد قراءة سير العمل الفعليّ، ثم تابع.

الملخّص الحاليّ (المرجع هو ملف كل مشروع):
| التطبيق | الحزمة | المسار الافتراضي | الزناد |
|---|---|---|---|
| منبر (مستمعون) | `com.ali.menbaradkshk` | production مباشرة | دمجٌ إلى `main` يغيّر `versionCode` ⇒ `bundle-with-assets.yml` |
| لوحة منبر (Kotlin) | `com.ali.ishaqiyin_admin` | alpha (اختبار مغلق) دائماً | `build-publish.yml` |
| مصحفك | `com.mushafak.app` | حسب الطلب (`store/release-request.json`) | دفع `store/release-request.json` إلى `main` ⇒ `play-publish.yml` |
| مختبر الفقه | `com.ali.fiqhlab` | internal ما لم يُطلب | `ops/release-request.json` ⇒ `release.yml` (خلف `FIQHLAB_ACTIONS_BUDGET`) |

## ١. القاعدة
- الافتراض تنفيذٌ سحابيّ مجانيّ متحقّق؛ والأسرار في GitHub Secrets تُكتب ولا تُقرأ — **لا توقيع محلياً ولا طلب مفتاح من المالك**.
- إذن النشر والمسار من الطلب وقواعد المشروع؛ لا تطلب تكرار إذنٍ واضح، ولا تفترض الإنتاج حيث لا يقرّه الملف.
- مستودع خاص ⇒ راجع حدّ الإنفاق (`mwqwf-workflow` §٧) قبل أي تشغيل.

## ٢. «ابنِ/ارفع» = ثلاثة معاً
1. رفع `versionCode`/`versionName` (رقمٌ غير مستعمل — اقرأ المسار أولاً) + تحديث شاشة «حول» وملاحظات الإصدار وREADME حيث يفرضها المشروع.
2. البناء والتوقيع في CI عبر سير العمل المسمّى في `PLAY_APP.md`.
3. الرفع إلى المسار المأذون فقط، ثم التحقّق (§٤).

## ٣. إقرار Data Safety عبر الواجهة البرمجية (قبل اعتبار Console عائقاً)
`POST https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/dataSafety` بجسم `{"safetyLabels": "<CSV كاملاً>"}`، نطاق OAuth `https://www.googleapis.com/auth/androidpublisher`؛ النجاح 200/204 بجسم فارغ. المرجع: [applications.dataSafety](https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety).
1. راجع سلوك النسخة الفعليّ وتدفّقات البيانات وسياسة الخصوصية المنشورة؛ ولّد CSV من قالب Google الحاليّ بإجابات **هذا المشروع** المقيسة. لا تدّعِ حذفاً غير منفّذ.
2. أرسل من CI بحساب الخدمة المصرّح، ولا تُظهر السرّ في سجلّ أو أثر.
3. احتفظ بإيصال: الحزمة، HTTP الفعليّ، وقت UTC، بصمة CSV، رقم الإصدار، رابط التشغيل.
4. صنّف الرفض: 400 نموذج، 401 اعتماد، 403 صلاحية. لا تكرّر عميانياً.
5. حارسٌ قديم يشترط Console؟ أضف قبول إيصال API الصحيح باختبارات رفض — لا تعطّله.

## ٤. التحقّق بعد النشر
- اقرأ `edits.tracks.get` في edit جديد بعد commit: versionCode وحالته في المسار المقصود؛ احذف edit التحقّق غير الملتزم فقط.
- ⛔ لا استعلام عن تطبيقٍ أثناء نشره (التحرير الثاني يُبطل الأول) — راقب `concurrency` وحارس التصادم.
- ميّز: رُفعت الحزمة ≠ أُرسلت للمراجعة ≠ متاحة للمختبرين/الناس. نجاح بناء AAB لا يثبت النشر.
- فشل commit بعد رفع AAB: احتفظ بالأثر الموقّع، افحص tracks/bundles، وأعد استعمال الحزمة نفسها بدل بناءٍ جديد أو رفع الرقم.

## ٥. Foreground Service وسياسات أخرى
نجاح Data Safety لا يغطّي إقرارات FGS. لا توجد واجهة FGS عامة (حتى revision20260917)؛ جهّز وصف كل خدمة وأثر انقطاعها وفيديو فعلياً، والنموذج في Console بجلسة المالك المخوّلة — هذا «يحتاج المالك» حقيقيّ: دوّنه وواصل. لا تحذف إذناً لازماً ولا تخفض `targetSdk`. قبل أي إصدار شغّل فحص `play-policy-insights`.

## ٦. أسرار JSON بـgh (عند ضرورة إعادة الضبط بأمر المالك)
تحقّق من البنية دون طباعتها، ومرّر البايتات عبر stdin: `gh secret set NAME --repo OWNER/REPO < file` (لا `--body -`).

## محرّمات وقعت فعلاً
- لا `ndk { abiFilters }` في حزمة متجر (أسقط 9,432 جهازاً).
- لا `pull_request_target` ولا `issue_comment` في سير عمل فيه أسرار.
- لا حذف لملفات حزم Play المضمّنة (Asset Packs).
- لا إضعاف لحارسٍ أو تعطيل اختبار ليخضرّ النشر.
