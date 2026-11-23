# أمان أندرويد — التفصيل (افتحه عند إذن جديد، مكوّن مصدَّر، IPC، PendingIntent)
المصدر الكامل: [android/skills](https://github.com/android/skills) (android-permissions-security · android-intent-security).

- **الأذونات المخصّصة:** `protectionLevel="signature"` للتواصل بين تطبيقاتنا؛ عرّف الإذن في التطبيق المزوِّد، وتحقّق في الشيفرة أيضاً (`checkCallingPermission`).
- **هوية المتّصل:** `Binder.getCallingUid()` ⟵ `packageManager.getPackagesForUid()` ⟵ تحقّق من بصمة التوقيع (`PackageManager.hasSigningCertificate`)؛ لا اسم حزمة من extra.
- **الخدمة المربوطة:** افرض الإذن في `onBind` وفي كل دالة Binder (`enforceCallingPermission`).
- **الإذن وقت التشغيل:** اشرح السبب قبل الطلب، تعامل مع الرفض الدائم (افتح الإعدادات بطلب المستخدم)، ولا تطلب عند الإقلاع.
- **إعادة توجيه Intent:** لا تمرّر Intent مستلماً كما هو؛ استعمل `IntentSanitizer` أو أنشئ Intent صريحاً جديداً. اعتبر `onNewIntent` مدخلاً غير موثوق.
- **PendingIntent:** `FLAG_IMMUTABLE`؛ `FLAG_MUTABLE` فقط لـinline reply/bubbles مع Intent صريح.
- **ContentProvider:** `exported=false`، و`FileProvider` بمسارات محدّدة في `file_paths.xml`، و`grantUriPermission` للمستلم المحدّد فقط.
- **روابط عميقة:** App Links موثّقة (`autoVerify`) وتحقّق من كل معامل.
- **التقرير:** لكل تغيير أمنيّ: المكوّن، الخطر، الإصلاح، الاختبار.
