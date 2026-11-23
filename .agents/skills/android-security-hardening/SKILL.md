---
name: android-security-hardening
description: Audit and harden Android app security — runtime/custom permissions, exported components, intents and PendingIntents, ContentProviders, bound-service caller verification, broadcasts (API 34+). Use when adding or reviewing a permission, an exported Activity/Service/Receiver/Provider, deep links, PendingIntent, IPC, before a Play release security pass, or when Play flags a security issue. تُستعمل لتدقيق أذونات أندرويد وأمان الـIntent والمكوّنات المصدَّرة قبل النشر.
license: Apache-2.0 (upstream android/skills); merge by mwqwf
metadata:
  sources: "android/skills security/android-permissions-security + security/android-intent-security"
  merged: "2026-11-23"
---

# تقوية أمان تطبيقات أندرويد (مدمجة من مهارتَي جوجل الرسميّتين)

هذه نواةٌ قصيرة؛ التفاصيل والشيفرة الكاملة في المراجع ولا تُقرأ إلا عند الحاجة:
- `references/permissions-security.md` — الأذونات المخصّصة ومستويات الحماية، المكوّنات المصدَّرة وأذونات URI، التحقّق التشفيريّ من هويّة المتّصل (`CallerVerifier`)، فرض Binder على مستوى الدالة، مسار الأذونات وقت التشغيل، بدائل بلا إذن (Photo Picker)، البثّ الآمن API 34+.
- `references/intent-security.md` — التوجيه الصريح/الضمنيّ، `PendingIntent` (FLAG_IMMUTABLE)، إعادة توجيه الـIntent الآمنة و`IntentSanitizer`، `onNewIntent`، `ContentProvider`، التحقّق من توقيع المتّصل بالخدمة.
- `references/permissions/` — مراجع جوجل الإضافية.

## سير العمل (بالترتيب، وتوقّف عند أوّل فشل)
1. **الجرد:** اقرأ `AndroidManifest.xml` المدمج (`app/build/intermediates/merged_manifests/` أو `./gradlew :app:processDebugMainManifest`) لا اليدويّ وحده. اسرد: كل `<uses-permission>`، كل مكوّن `exported="true"` أو له `intent-filter`، كل `provider`، كل `PendingIntent.get*`.
2. **قاعدة أقلّ إذن:** كل إذنٍ خطِر يحتاج سبباً مرئياً للمستخدم وبديلاً أُثبت تعذّره (Photo Picker بدل `READ_MEDIA_*`، `ACCESS_COARSE` قبل `FINE`، إشعارات `POST_NOTIFICATIONS` عند الحاجة لا عند الإقلاع). وكل إذنٍ يُضاف يُطابَق مع إقرار Data Safety (`google-play-publish`).
3. **المكوّنات المصدَّرة:** كل مكوّنٍ مصدَّر إمّا يحميه إذنٌ `signature` أو يتحقّق من مدخلاته كلها. لا تثق بأي extra يحمل اسم حزمة — تحقّق بـ`Binder.getCallingUid()` + التوقيع (المرجع §4).
4. **الـIntent:** `PendingIntent` بـ`FLAG_IMMUTABLE` إلا لضرورةٍ مسمّاة، وIntent داخليّ صريحٌ دائماً؛ إعادة التوجيه عبر `IntentSanitizer`.
5. **ContentProvider:** `exported=false` افتراضياً؛ عند المشاركة `grantUriPermissions` بنطاقٍ ضيّق ومنح مؤقّت.
6. **التحقّق:** `./gradlew :app:lintDebug` (فحوص الأمان مفعّلة)، واختبار وحدة لكل حارس جديد، ثم `verify-and-review`.

## قواعد لا تُكسر
- لا تُضعف حارساً قائماً ولا تحذف إذناً لازماً لميزةٍ موجودة لتجاوز مراجعة Play؛ ولا تخفض `targetSdk`.
- لا `android:exported="true"` بلا سببٍ مكتوب في التعليق.
- لا `MODE_WORLD_READABLE`، ولا تخزين رموز في `SharedPreferences` غير مشفّرة إن كانت أسراراً.
- كل تغيير أمنيّ يُذكر في تقرير المهمة بقالب «Reporting guidelines» في `references/intent-security.md`.
