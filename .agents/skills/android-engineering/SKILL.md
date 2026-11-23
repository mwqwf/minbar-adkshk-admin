---
name: android-engineering
description: "Kotlin/Compose app work (Minbar, Mushafak, FiqhLab): UI, performance, ANR, security, permissions, widgets, AGP upgrade. أندرويد كوتلن: واجهة، أداء، أمان، أذونات، ترقية"
license: "Apache-2.0 (condensed from android/skills, Kotlin/kotlin-agent-skills) + mwqwf"
metadata:
  merged: "android-performance + android-security-hardening + edge-to-edge + adaptive + navigation-3 + navigation-event + testing-setup + agp-9-upgrade + kotlin-tooling-agp9-migration + play-billing-library-version-upgrade + glance-appwidget-updates-and-memory + media3-cast-integration + android-cli + impeccable (2026-11-23)"
---

# هندسة تطبيقات أندرويد (Kotlin/Compose)

قاعدة المالك: **التطبيق خفيف وسريع ولا يضرّ جهاز المستخدم (بطارية/حرارة/ذاكرة/بيانات) بلا التضحية بميزة.** الاختبار على المحاكي/CI فقط؛ هاتف المالك لا يُلمس. البناء في CI (الحاويات تحجب `dl.google.com`).

## الواجهة
- Compose مع `start/end`، و`enableEdgeToEdge()` مع targetSdk ≥ 35: طبّق الحواف (`innerPadding` على `contentPadding` للقوائم الكسولة لا `Modifier.padding`)، و`adjustResize` + `imePadding()` قبل `verticalScroll` لكل شاشة فيها حقل نصّ.
- لا `android:configChanges`/`screenOrientation` لحلّ مشكلات التدوير أو الطيّ؛ احفظ الحالة (`rememberSaveable`/ViewModel) واختبر استعادتها.
- الرجوع: كل شاشة فرعيّة تعود إلى أمّها (في مصحفك `SubScreenBack` + `BackNavigationEmulatorTest`)؛ لا تربط `NavigationEventState` واحداً بأكثر من معالج نشط.
- جودة بصرية: تباين النصّ ≥ 4.5:1، أهداف لمس ≥ 48dp، كل الحالات (تحميل، فارغ، خطأ، معطّل)، نصوص الأزرار تسمّي فعلها والأخطاء تسمّي العلاج، حركة مقصودة واحدة لا مؤثّرات مبعثرة، لا بطاقات متداخلة ولا رموز تعبيرية بدل الأيقونات. الصقل يحفظ الهوية؛ لا إعادة تصميم بلا طلب.
- العربية وRTL والتشكيل: مهارة `arabic-text-integrity`.

## الأداء (قِس قبل وبعد بالأداة نفسها)
1. خطّ أساس مقيس (زمن الإطار، الإقلاع ms، حجم AAB، ANR من Play Vitals) مع الأمر حرفياً.
2. فرضيّة واحدة وتغيير صغير ثم إعادة القياس. لا إعلان تحسّن بلا رقمين من مصدر واحد، ولا حذف ميزة أو إطفاء تحريك مطلوب لكسب أداء.
3. Compose: معاملات مستقرّة (`@Immutable`/`@Stable` أو أنواع ثابتة)، `key` و`contentType` في القوائم الكسولة، `derivedStateOf` للحالة المشتقّة المتكرّرة، قراءة الحالة المتغيّرة في lambda (`Modifier.offset { }`) لتأجيلها إلى التخطيط/الرسم، لا تخصيص كائنات في التركيب.
4. الخيط الرئيسيّ: لا قرص/شبكة/قاعدة بيانات ولا `runBlocking` فيه؛ `Dispatchers.IO`؛ لا عمل ثقيل في `onCreate`/`Application`/`BroadcastReceiver.onReceive` (ANR بعد 5–10 ث).
5. Baseline Profiles لمسار الإقلاع والشاشات الرئيسية إن وُجد `baselineProfile`. R8 مفعّل في الإصدار، وقواعد keep ضيّقة (لا `-keep class **`)، وتحقّق من الحجم بعد كل اعتمادية.
التفصيل وأدوات القياس (Perfetto، heap) في `references/performance.md` — افتحه عند تشخيص بطء أو ANR أو حجم.

## الأمان والأذونات
1. اجرد من الـManifest **المدمج** (`processDebugMainManifest`): كل `uses-permission`، كل مكوّن `exported`/`intent-filter`، كل `provider`، كل `PendingIntent`.
2. أقلّ إذن: Photo Picker بدل `READ_MEDIA_*`، `COARSE` قبل `FINE`، `POST_NOTIFICATIONS` عند الحاجة لا عند الإقلاع؛ وكل إذنٍ يُطابَق مع Data Safety (مهارة `google-play`). لا تحذف إذناً لازماً ولا تخفض `targetSdk` لتجاوز مراجعة.
3. كل مكوّن مصدَّر محميّ بإذن `signature` أو يتحقّق من كل مدخلاته؛ لا تثق بـextra يحمل اسم حزمة (`Binder.getCallingUid()` + التوقيع). `exported="true"` بتعليقٍ يذكر السبب.
4. `PendingIntent` بـ`FLAG_IMMUTABLE`، والـIntent الداخليّ صريح، وإعادة التوجيه عبر `IntentSanitizer`. البثّ API 34+ بـ`RECEIVER_NOT_EXPORTED` افتراضاً.
5. `ContentProvider` غير مصدَّر افتراضاً؛ المشاركة بـ`grantUriPermissions` ضيّقة مؤقّتة. لا `MODE_WORLD_READABLE`، والأسرار لا تُخزّن غير مشفّرة.
6. التحقّق: `lintDebug` + اختبار لكل حارس. التفصيل والشيفرة `references/security.md` — افتحه عند إضافة إذن أو مكوّن مصدَّر أو IPC.

## محرّمات وقعت فعلاً
- لا `ndk { abiFilters }` في حزمة متجر (حصر arm64 أسقط 9,432 جهازاً).
- لا `pull_request_target` ولا `issue_comment` في سير عمل فيه أسرار.
- لا حذف لحزم الأصول المضمّنة (Play Asset Packs).

## مهام خاصّة (افتح المرجع قبل البدء)
`references/tasks.md`: ترقية AGP 9 ونقل Kotlin المدمج · ترقية Play Billing · ودجت Glance (الذاكرة والتحديث) · Media3 Cast · Navigation 3 والشاشات المتكيّفة (لوحيّ/طيّ) · إعداد الاختبارات ولقطات الشاشة · أداة `android` CLI والمحاكي.
