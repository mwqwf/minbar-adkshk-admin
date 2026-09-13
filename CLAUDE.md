# لوحة إدارة منبر (يُحمَّل تلقائياً في كل جلسة)

> **الدستور الكامل في مستودع التطبيق: `mwqwf/minbar-adkshk` ← `docs/CLOUD-SESSION.md`.**
> استنسخه واقرأه قبل أيّ فعل (`gh repo clone mwqwf/minbar-adkshk`). الردود بالعربية دائماً.

## من أنت وأين

هذا مستودع **لوحة الإدارة** `com.ali.ishaqiyin_admin` (Kotlin/Compose)، فرع `main`، والدفع
مباشرةً. الخادم `mwqwf/minbar-cloud` (خاصّ)، وتطبيق المستمعين `mwqwf/minbar-adkshk`.

## ⛔⛔ اللوحة تُنشر على «الاختبار المغلق» دائماً

أمر المالك 2026-09-12: لا إنتاج مباشر من هنا أبداً؛ الترقية قراره وحده من Play Console.

```bash
gh workflow run build-publish.yml -R mwqwf/minbar-adkshk-admin   # بناء + توقيع + نشر ⇐ alpha
gh run list -R mwqwf/minbar-adkshk-admin -L 1                    # ثمّ اقرأ السجلّ
```

⛔ لا أسرار عندك: مفتاح التوقيع وحساب خدمة Play أسرارٌ في GitHub تُكتب ولا تُقرأ.
⚠️ ومتغيّرات التوقيع هنا **`MINBAR_ADMIN_SIGNING_*`** لا `MINBAR_SIGNING_*` (أسقطت بناءً كاملاً).

## «ابنِ» و«ارفع»

رفع `versionCode` و`versionName` + سطر في سجلّ التغييرات داخل `app/build.gradle.kts`
(لا `AboutScreen` ولا `ReleaseNotes` هنا — ليسا في هذا المشروع، والإصدار يظهر في «الحساب والصلاحية»)،
ثم البناء والنشر بسير العمل أعلاه.

## ما يجب أن تعرفه عن اللوحة

- الهوية: **«جلسة منبر»** (`data/AdminSession.kt`) — رمزٌ يصدره الخادم؛ و`firebase-auth`
  باقيةٌ **انتقالياً** حتى ينتقل كلّ المشرفين، ثم تُحذف. ولا FCM ولا App Check (أُزيلا).
- التنبيهات باستطلاعٍ تكيّفي من `media.menbar.app/pulse.js`.
- الصلاحيات: المالك وحده يطرد ويحظر ويغيّر الرتب ويُتلف نهائياً؛ وسجلّ التدقيق حيّ من `/admin/audit`.
- ⛔ لا `abiFilters` (حصرُ arm64 أسقط ٩٬٤٣٢ جهازاً)، ولا `pull_request_target`، ولا إيداع سرّ.

## معيار «أُنجز»

ترجمة + اختبارات + برهانٌ مقروء: `jar verified` في السجلّ وإثبات وصول النسخة إلى `alpha`.
