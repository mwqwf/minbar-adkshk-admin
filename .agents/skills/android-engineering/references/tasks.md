# مهام أندرويد الخاصّة (افتح القسم المعنيّ قبل البدء؛ الدليل الكامل في [android/skills](https://github.com/android/skills) و[Kotlin/kotlin-agent-skills](https://github.com/Kotlin/kotlin-agent-skills))

## ترقية AGP 9
- ليست لمشاريع KMP بهذه الطريقة. Kotlin المدمج في AGP 9: أزل `org.jetbrains.kotlin.android` وانقل `kotlinOptions` إلى `kotlin { compilerOptions { } }`.
- لا تضف `android.disallowKotlinSourceSets=false`. حدّث Gradle وKSP/Hilt المتوافقة أولاً، ثم ابنِ واختبر بعد كل خطوة.

## ترقية Play Billing
- اقفز إصداراً رئيسياً تلو الآخر، وطبّق كل إهمالات كل قفزة (`queryProductDetailsAsync`، `enablePendingPurchases(PendingPurchasesParams)`، إقرار الشراء خلال 3 أيام). لا تكتفِ بتغيير الرقم؛ ابنِ عند كل قفزة.

## ودجت Glance
- مرّر Bitmap مصغّراً في الذاكرة لا `content://` (Android 17 يحسبه من الحدّ)، ولا تصغّر داخل `provideContent` لكل حجم.
- بعد `updateAppWidgetState` استدعِ `update`/`updateAll`. استبدال الصور بـ`updateAppWidget` كامل لا جزئيّ.
- `ActionCallback` بمُنشئ بلا معاملات (R8)، ولا WorkManager غير مشروط في `onUpdate`، ولا خدمة أمامية من الودجت.

## Media3 Cast
- `MediaRouteButton` من `androidx.media3.cast` بجوار أدوات التشغيل (لا `AndroidView`)، و`CastPlayer` مع المشغّل المحليّ؛ `INTERNET` فقط من الأذونات؛ أجبر إعادة التركيب عند تغيّر موضع التشغيل.

## Navigation 3 والشاشات المتكيّفة
- متعدّد الألواح عبر `SceneStrategy` في Navigation 3، و`detailPlaceholder` عند عدم الاختيار. لا `configChanges`. لقطات معاينة لأحجام الهاتف/اللوحيّ/الطيّ.

## الاختبارات ولقطات الشاشة
- وحدة للمنطق (ViewModel، مستودعات بمزيّفات لا mocks ثقيلة)، Compose UI tests للتفاعل، لقطات الشاشة بأداة AGP المدمجة أو الإضافة المستقلّة — لا تجمع الطريقتين في وحدة واحدة، ولا تحدّث الصور المرجعية دون مراجعة.

## أداة `android` CLI والمحاكي
- `android layout` لشجرة الواجهة JSON، و`android screen --annotate` للقطة مرقّمة — افحص الصورة بصرياً قبل أي فعل؛ تأكّد من `FOCUSED` قبل الكتابة، ومرّر ببطء. على حاسوب الوكيل أو CI فقط.
