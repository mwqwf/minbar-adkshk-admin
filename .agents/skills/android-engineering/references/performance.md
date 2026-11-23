# أداء أندرويد — التشخيص (افتحه عند بطء، تقطيع، ANR، ذاكرة، حجم)
المصدر الكامل عند الحاجة: [android/skills](https://github.com/android/skills) (jetpack-compose-performance · android-profiler · anr-audit-and-fix · r8-analyzer).

## Compose
- اكشف إعادة التركيب: Layout Inspector (عدّاد recomposition) أو تقارير مترجم Compose (`-P…composeCompiler reports`) لمعرفة المعاملات غير المستقرّة.
- غير المستقرّ عادةً: `List`/`Map` القابلة للتغيير، الأنواع من وحدات بلا Compose، lambdas تلتقط حالة متغيّرة. الحلّ: مجموعات ثابتة (`kotlinx.collections.immutable`)، `@Immutable`، أو ملف إعداد الاستقرار.
- القوائم: `key` ثابت، `contentType`، لا قياس متداخل لعناصر كسولة، الصور بحجم العرض.
- الحركة: `graphicsLayer {}` بدل تغيير التخطيط؛ `animate*AsState` بدل حلقات يدوية.

## القياس
- Perfetto/System Trace للإقلاع والتقطيع؛ Macrobenchmark لزمن الإقلاع والإطارات؛ heap dump/LeakCanary للتسرّب.
- قِس على محاكي/جهاز CI ثابت؛ كرّر 3 مرّات على الأقل وخذ الوسيط.

## ANR
- مساران: (A) تدقيق ثابت للخيط الرئيسيّ: I/O، قفل `synchronized` متنازع، `SharedPreferences.commit`، `Binder` متزامن، ContentResolver؛ (B) من أثر Play Vitals: حدّد الخيط الرئيسيّ وما ينتظره.
- الإصلاح: انقل إلى `Dispatchers.IO`/WorkManager، `apply()` بدل `commit()`، `goAsync()` في المستقبِل لعمل قصير.

## الحجم وR8
- `./gradlew :app:bundleRelease` ثم قارن الحجم؛ `apkanalyzer` لأكبر المكوّنات.
- قواعد keep: الأضيق الممكن (فئة/عضو)، احذف القواعد الواسعة الموروثة بعد اختبار، وتحقّق من الانعكاس (Gson/Retrofit) بقواعد مكتبتها.
