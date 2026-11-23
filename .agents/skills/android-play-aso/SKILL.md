---
name: android-play-aso
description: Google Play store listing optimization (ASO) for Arabic-first apps — title (30), short description (80), full description (4000), keyword placement, screenshots/feature graphic, store listing experiments, localized listings, ratings/reviews replies, competitor comparison, and pushing listing text via the Play Developer API from CI. Use when writing or auditing a Play listing, release notes ("ما الجديد"), screenshots, when installs/conversion are low, or before a major release of Minbar, Mushafak, or FiqhLab. تحسين صفحات المتجر بالعربية الفصحى.
license: MIT (upstream method coreyhaines31/marketingskills aso) + mwqwf adaptation
metadata:
  upstream: "https://github.com/coreyhaines31/marketingskills/tree/main/skills/aso"
---

# تحسين صفحة Google Play (عربيّ أوّلاً)

المنهج الكامل للتدقيق والتسجيل في `references/upstream/aso-audit-method.md`، ومواصفات Play في `references/upstream/google-play-specs.md`، والمعايير في `scoring-criteria.md` و`benchmarks.md`، وقالب التقرير في `report-template.md`. هنا ما يخصّ مشاريعنا.

## ١. ما يفهرسه Google Play
العنوان (30 حرفاً) والوصف القصير (80) والوصف الكامل (4000) كلّها مفهرسة — **لا حقل كلمات مفتاحية**. الكلمة الأهمّ في العنوان ثم أوّل سطر من الوصف القصير، وتكرارها طبيعياً 3–5 مرات في الكامل (لا حشو؛ Play يعاقب عليه).

## ٢. صياغة عربية
- الفصحى الواضحة؛ وكلمات البحث الفعلية للناس (مثلاً: «مصحف»، «تسميع القرآن»، «تحفيظ»، «تلاوة»، «دروس صوتية»، «فقه»، «مواريث»، «زكاة») — اختبرها في اقتراحات بحث Play.
- لا ادّعاءات مبالغة أو مضلّلة («يصحّح تلاوتك 100%»، «بلا انقطاع») — رُفضت إعلاناتٌ لنا بسببها؛ وتطابق الوصف مع الميزات الفعلية في النسخة المنشورة.
- لا رموز تعبيرية في العنوان، ولا «الأفضل/رقم 1» (سياسة Play).
- النصوص الشرعية في الصفحة تخضع لـ`arabic-rtl-and-islamic-typography` §٣.

## ٣. الصور
- 4–8 لقطات بنسبة 9:16، أوّل لقطتين تحملان القيمة (عبارة عربية قصيرة كبيرة + الشاشة)، صور RTL حقيقية من المحاكي لا محاكاة.
- صورة مميزة 1024×500 بلا نصّ صغير؛ لا عناصر واجهة مزيّفة في صور الإعلانات (وُسمت سابقاً MISLEADING_AD_DESIGN).

## ٤. التنفيذ الآليّ
- اللقطات تُولّد في CI على المحاكي (سير عمل اللقطات إن وُجد في المستودع).
- نصوص الصفحة تُحفظ في المستودع (`store/listing/ar.json` أو ما يحدّده المشروع) وتُدفع عبر `edits.listings.update` في سير عمل النشر — لا نسخ يدوي في Console.
- تجارب الصفحة (Store listing experiments) تُقترح للعنوان/الأيقونة/أول لقطة عند كل إصدار كبير.
- الردّ على المراجعات: مسوّدات عربية مهذّبة قصيرة تُراجع ثم تُرسل عبر `reviews.reply` (حيث يوجد سير عمل لذلك).

## ٥. المخرَج
تقرير بالدرجات (قالب upstream مترجماً) + نصّ مقترح جاهز في كتلة قابلة للنسخ لكل حقل مع عدد الأحرف + قائمة تغييرات مرتّبة بالأثر.
