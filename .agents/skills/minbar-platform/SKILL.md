---
name: minbar-platform
description: "Minbar/منبر app (minbar-adkshk), Kotlin admin dashboard (minbar-adkshk-admin), minbar-cloud backend: features, bugs, build, release. تطبيق منبر ادكصهك، لوحة الإدارة، الخادم، الصوتيات"
license: Proprietary (mwqwf) — internal
---

# منصّة منبر ادكصهك

## الخريطة
| المكوّن | المستودع | ملاحظات |
|---|---|---|
| تطبيق المستمعين | `mwqwf/minbar-adkshk` (عام) | `com.ali.menbaradkshk` · Kotlin/Compose · Media3 · حزم أصول `core_pack`/`rest_pack` (المكتبة دون إنترنت) · دستور الجلسة `docs/CLOUD-SESSION.md` |
| لوحة الإدارة (Kotlin حصراً) | `mwqwf/minbar-adkshk-admin` (عام) | `com.ali.ishaqiyin_admin` — لا تغيّر الحزمة (OAuth/SHA-1 في Firebase `mxqp-8d1e8`). نسخة Flutter `ishaqiyin_admin` غير نشطة |
| الخادم | `mwqwf/minbar-cloud` (خاص) | Worker `minbar-api` + D1 `minbar` + R2 `minbar-media`: الكتالوج، الدلتا، المسبار، النبض، النصوص المشروحة، الإشعارات، المساهمات، جلسات المشرفين، التدقيق |
| المرآة/الموقع (تبعيّة) | `mwqwf/menbar-site` (عام، غير نشط تطويراً) | `minbar-adkassahk.vercel.app`: `/audio/<sha>.ogg` و`/api/catalog` و`api/transcode` وسياسة الخصوصية المُلزمة أمام Play. ⛔ لا تُصلح صفحات الموقع في مستودع التطبيق |

## ثوابت لا تُمسّ (أيّ مساس يُعرض أولاً)
الهوية البصرية · كونه تطبيقاً صوتياً مع «النص المشروح» · مسار رفع المحتوى (القسم ← الفرعي ← الدرس). البساطة والخفّة أولوية قصوى. الرخصة: النسبة للمطوّر إلزامية والتجاريّ ممنوع بلا إذن كتابيّ.

## البناء والنشر — بسير عمل لا من جهاز
```
gh workflow run bundle-with-assets.yml -R mwqwf/minbar-adkshk    # بناء + توقيع + نشر ⇒ production
gh workflow run emulator-check.yml   -R mwqwf/minbar-adkshk      # برهان المحاكي
gh workflow run build-publish.yml    -R mwqwf/minbar-adkshk-admin # اللوحة ⇒ alpha (اختبار مغلق) دائماً
gh workflow run deploy.yml           -R mwqwf/minbar-cloud       # الخادم (what=schema لمخطّطات D1، what=query -f sql=… للقراءة)
gh workflow run play-status.yml      -R mwqwf/minbar-adkshk      # حالة التطبيقين في Play (مجدول يومياً)
gh run list -R <repo> -L 1 && gh run view <id> -R <repo> --log
```
- **الدمج ينشر:** دفعةٌ إلى `main` تمسّ `app/build.gradle.kts` ويتغيّر فيها `versionCode` ⇒ `bundle-with-assets` يبني وينشر للإنتاج. فلا ترفع الرقم إلا وأنت تريد النشر، ومعه «حول» (`ui/AboutScreen.kt`) وملاحظات الإصدار (`data/ReleaseNotes.kt`) وREADME (يحرسها `check_release_notes.py` و`sync_readme_version.py`).
- **اللوحة:** رفع الرقم + سطر في سجلّ التغييرات داخل `app/build.gradle.kts` (لا About/ReleaseNotes)؛ متغيّرات التوقيع `MINBAR_ADMIN_SIGNING_*` لا `MINBAR_SIGNING_*`. الترقية من alpha قرار المالك وحده.
- كل طلب سحب يمرّ بـ`pr-check` (حارس التوثيق، ملاحظات الإصدار، الاختبارات، `lint` كبوّابة، بناء debug) — لا يُضعَّف ولا `continue-on-error`. تُدمج الطلبات متى اخضرّ.
- الأسرار في GitHub تُكتب ولا تُقرأ؛ لا توقيع محلياً ولا طلب مفتاح من المالك. ثوابت النشر في `docs/agents/PLAY_APP.md` (مهارة `google-play`).

## محرّمات وقعت فعلاً
- لا `ndk { abiFilters }` في حزمة متجر · لا `pull_request_target`/`issue_comment` · لا حذف ملفات حزم Play (`LocalStore.isBundledDownload`) · لا استعلام عن تطبيق في Play أثناء نشره · لا `git add <مجلد>` ولا إيداع سرّ · الهاتف ممنوع للاختبار (المحاكي في CI).

## دروس الخادم (minbar-cloud)
- `ctx.waitUntil()` إلزاميّ لكل عملٍ خلفيّ.
- D1: لا `BEGIN/COMMIT` في ملفات `execute`، و`IN (...)` ≤ 90 متغيّراً للدفعة.
- المسارات العامة مغلّفة بـ`cached()` (بدونه يبلغ المجانيّ سقفه عند ~700 مستخدم) — لا تنزعه.
- النبض `pulse.js` على R2 بامتداد `.js` (ليخبّئه CDN). التنبيهات في اللوحة باستطلاعٍ تكيّفيّ منه.
- الإتلاف النهائي وطرد المشرفين للمالك وحده (`role==='owner'`).
- هوية المشرفين «جلسة منبر» (`data/AdminSession.kt`)؛ `firebase-auth` انتقاليّ فقط.

## «أُنجز»
- التطبيق/اللوحة: `pr-check` أخضر + برهان المحاكي + سجلّ النشر (`jar verified` ووصول النسخة للمسار).
- الخادم: نشر ثم قراءة الأثر من الخادم نفسه (`/v1/probe` أو `/v1/pulse`).
- قبل الإصدار: `google-play` و`android-engineering` عند مسّ الأذونات، و`android-engineering` عند مسّ الأداء.
