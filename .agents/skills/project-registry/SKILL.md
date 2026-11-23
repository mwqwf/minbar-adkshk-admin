---
name: project-registry
description: Authoritative list of the owner's ACTIVE vs INACTIVE projects and repositories, what each repo is, where its state lives, and the no-touch rule for inactive ones (do not scan, monitor, schedule, or modify without an explicit owner request). Use whenever the owner says "the project"/"المشروع" or names Minbar, Mushafak, FiqhLab, Mutafail, Nebras, Mishkat; before scanning repos, creating monitors/routines/CI schedules, or doing cross-repo work. سجلّ المشاريع النشطة وغير النشطة وقاعدة عدم المساس.
license: Proprietary (mwqwf) — internal
metadata:
  updated: "2026-11-23"
---

# سجلّ المشاريع (مرجع واحد لكل الوكلاء)

> حين يقول المالك «المشروع» أو «مشاريعي» فالمقصود **النشطة فقط**. نسخة مشتركة لبوتات Grok: `/home/box/shared-notes/PROJECTS.md` (تُحدَّث مع هذا الملف).

## النشطة
| المشروع | المستودعات | الرؤية | الحالة تُقرأ من |
|---|---|---|---|
| **منبر ادكصهك** (تطبيق المستمعين + لوحته بنسخة Kotlin حصراً) | `mwqwf/minbar-adkshk` (التطبيق `com.ali.menbaradkshk`) · `mwqwf/minbar-adkshk-admin` (لوحة Kotlin `com.ali.ishaqiyin_admin`) · `mwqwf/minbar-cloud` (خادم `minbar-api` على Cloudflare D1+R2 — يخدم التطبيقين) | عام · عام · خاص | `docs/PROJECT_STATE.md` في كلٍّ منها · `minbar-adkshk/docs/CLOUD-SESSION.md` |
| **مصحفك** وكل ما يتعلّق به | `mwqwf/QuranRafiq` (التطبيق `com.mushafak.app` + `cloud/worker` + `store/site`) · `mwqwf/rafiq-align-ci` (محاذاة التلاوات والحوسبة العامة) · `mwqwf/mushafak-ci` (عدّاء اختبارات عام) | خاص · عام · عام | `QuranRafiq/docs/PROJECT_STATE.md` · `docs/NEXT_ACTION.md` · أحدث `docs/ops/HANDOFF_*` |
| **مختبر الفقه** | `mwqwf/FiqhLab` (`com.ali.fiqhlab`) · `mwqwf/fiqhlab-packs` (صفحات الحزم والخصوصية) | خاص · عام | `FiqhLab/START_HERE.md` ⇒ `docs/STATE.md` · `docs/PROJECT_STATE.md` |
| **مصنع المتفائل** (قناة يوتيوب @Al-Mutafail) وما يتعلّق به | `mwqwf/mutafail-factory` (المصنع وسير العمل) · `mwqwf/yarmouk-media` (أصول الوسائط) | عام · خاص | `mutafail-factory/docs/PROJECT_STATE.md` · `ops/state/plan_state.json` |

**تبعيّة حيّة لا مشروع مستقلّ:** `mwqwf/menbar-site` (عام) ينشر `minbar-adkassahk.vercel.app` — مرآة صوت منبر و`/api/catalog` و`api/transcode` وسياسة الخصوصية المُلزمة أمام Play، ويجدّد لقطة الكتالوج الاحتياطية يومياً (`snapshot-catalog.yml`، مجانيّ). ⛔ لا تطوير فيه بلا طلب، ولا تعطيل لجدوله (التطبيق يعتمد عليه).

## غير النشطة — ⛔ قاعدة عدم المساس
**لا تُفحص، ولا تُراقب، ولا تُجدول عليها مهام، ولا تُعدَّل — إلا بطلبٍ صريح من المالك يسمّيها.** ولا تُدرج في النشرات أو الجرد أو الروتينات (توفيراً للتوكنز).
- نبراس: `nebras` · `nebras-app` · `nebras-dashboard` · `Nebras_dashboard-main` · `Nebras_dashboard_flutter` · `nibras-app-website`
- مشكاة الهداية: `mishkat-alhidaya`
- نسخ منبر غير Kotlin/قديمة: `Ishaqiyin_App` · `ishaqiyin_admin` (Flutter) · `minbar-adkshk-oss` · `archive_mobileapp-master`
- أخرى: `Quran-Quiz-Keeper` · `tadaksahak-qamus` · `rafiq-site` · `dmc` · `dmfls` · `-` · `mwqwf` (ملف التعريف) · `wf-scope-probe`
- فحص لمرّة واحدة (2026-11-23): لا جدول تشغيل فعّال في أيٍّ منها. `nebras/.../ia-cron.yml` (كل 5 دقائق) موجود في مسارٍ متداخل لا يشغّله GitHub، و`Nebras_dashboard-main` بلا سير عمل منذ 2026-07-24.

## تغيير السجلّ
بأمر المالك فقط: عدّل هذا الملف في `mwqwf/agent-skills` و`/home/box/shared-notes/PROJECTS.md` معاً، ثم زامن المستودعات (`tools/sync-all.sh`).
