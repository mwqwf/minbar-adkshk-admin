---
name: android-performance
description: Diagnose and fix Android performance — Jetpack Compose recomposition/stability/lazy lists, startup, jank, memory, battery, ANRs (static audit or stack dumps), Perfetto/profiler traces, and R8/ProGuard keep-rule bloat and APK/AAB size. Use when an Android screen is slow or janky, an ANR/vitals alert appears, startup regressed, app size grew, or before a release performance pass. لتشخيص أداء أندرويد: Compose والـANR والتتبّع وحجم الحزمة.
license: Apache-2.0 (upstream android/skills); pack by mwqwf
metadata:
  sources: "android/skills jetpack-compose-performance, profilers/android-profiler, performance/anr-audit-and-fix, performance/r8-analyzer"
  merged: "2026-11-23"
---

# حزمة أداء أندرويد

قاعدة المالك: **التطبيق خفيفٌ وسريع ولا يضرّ جهاز المستخدم (بطارية/حرارة/ذاكرة/بيانات) بلا التضحية بأي ميزة.** والقياس قبل الإصلاح وبعده إلزاميّ.

## اختر المسار من العرَض (ثم اقرأ دليله فقط)
| العرَض | الدليل |
|---|---|
| شاشة Compose بطيئة، إعادة تركيب زائدة، قوائم متقطّعة، حركة ثقيلة | `references/jetpack-compose-performance/GUIDE.md` (ومراجعه الفرعية حسب البند) |
| تحتاج قياساً: تتبّع Perfetto، startup، ذاكرة، CPU، heap dump | `references/android-profiler/GUIDE.md` |
| ANR في Play Vitals أو تقرير، أو تدقيق وقائيّ للخيط الرئيسيّ | `references/anr-audit-and-fix/GUIDE.md` (المسار A ثابت، B من الأثر) |
| حجم الحزمة كبر، قواعد keep واسعة، تعارض R8 | `references/r8-analyzer/GUIDE.md` |

## سير العمل
1. **ثبّت خطّ الأساس:** رقمٌ مقيس (زمن الإطار، startup ms، حجم AAB، عدد ANR من `play-vitals`) مع أمر القياس حرفياً.
2. **فرضيّة واحدة في كل مرة**، وتغييرٌ صغير، ثم إعادة القياس بالأداة نفسها والجهاز/المحاكي نفسه.
3. **Baseline Profiles** متى لمس التغيير مسار الإقلاع أو شاشة رئيسية (إن كان للمشروع `baselineProfile`).
4. التحقّق: اختبارات الوحدة + بناء release مع R8 + المحاكي في CI، ثم `verify-and-review`.

## قواعد
- لا تحذف ميزةً ولا تُطفئ تحريكاً مطلوباً لكسب أداء؛ اعرض البديل.
- لا تعلن تحسّناً بلا رقمَين (قبل/بعد) من مصدرٍ واحد.
- أرقام الأداء على هاتف المالك يقيسها هو؛ الوكيل يقيس على المحاكي/CI فقط.
