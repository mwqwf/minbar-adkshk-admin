package com.ali.ishaqiyin_admin.ui

/**
 * 🪶 إصلاح ترتيب أبيات المنظومات الخارجة من استخراج النص من الصور.
 *
 * لماذا: صفحة المنظومة المطبوعة عمودان — الصدر يمين والعجز يسار من السطر
 * نفسه. واستخراج النص يقرأ العمود الأيمن كلّه ثم الأيسر كلّه، فتخرج
 * الأصدار كلّها أوّلاً ثم الأعجاز كلّها، فيقع الصدر مع عجز بيت آخر
 * وينكسر المعنى (تأكّد ذلك في الدرس 13XGWiEWKPgtAumEa0z4).
 *
 * الحلّ: نقسم الأسطر نصفين ونقرن السطر الأوّل من النصف الأوّل بالأوّل من
 * النصف الثاني وهكذا.
 *
 * أسطر العناوين (فصل/باب/كتاب/…) ليست شعراً ولا تدخل العمودين، فتُستبعد
 * من القسمة ثم تُعاد فوق الأبيات بترتيبها الأصلي — وإلّا اختلّت القسمة
 * كلّها بسبب سطر واحد.
 */
object PoemLineFixer {

    /** كلمات تبدأ بها عناوين المتون عادةً — سطرها ليس بيت شعر. */
    private val HEADING_WORDS = listOf(
        "فصل", "باب", "كتاب", "مقدمة", "مقدّمة", "خاتمة",
        "تنبيه", "مسألة", "مسالة", "فائدة", "تتمة", "تتمّة",
    )

    /** التشكيل وعلامات الترقيم تمنع المطابقة الحرفية، فتُزال قبل الفحص. */
    private val DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")

    fun isHeading(line: String): Boolean {
        val clean = line.replace(DIACRITICS, "")
            .trim()
            .trimStart('(', ')', '[', ']', '«', '»', '-', '—', '*', '.', '،', ':')
            .trim()
        val first = clean.split(' ', '\t').firstOrNull()
            ?.trim(':', '،', '.', ')', '(') ?: return false
        return HEADING_WORDS.any { it == first }
    }

    sealed interface Result {
        /** النصّ بعد الإصلاح — يُعرض للمراجعة، ولا يُحفظ إلا بإقرار المشرف. */
        data class Fixed(val text: String, val verses: Int) : Result

        /** سبب المنع بالعربية البسيطة كما يُعرض للمشرف حرفياً. */
        data class Problem(val message: String) : Result
    }

    fun fix(input: String): Result {
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4) {
            return Result.Problem("النصّ قصير — لا حاجة إلى ترتيب الأبيات.")
        }
        val headings = lines.filter { isHeading(it) }
        val body = lines.filterNot { isHeading(it) }
        if (body.size < 2) {
            return Result.Problem("لا أبيات في النصّ — راجعه يدوياً.")
        }
        if (body.size % 2 != 0) {
            return Result.Problem("عدد الأسطر فرديّ — راجع النصّ يدوياً.")
        }
        val half = body.size / 2
        val verses = (0 until half).map { i -> "${body[i]}\n${body[half + i]}" }
        val out = (headings + verses).joinToString("\n\n")
        return Result.Fixed(out, half)
    }
}

/**
 * «بيت واحد»/«بيتان»/«٣ أبيات»/«١٢ بيتاً» — بصيغة [arabicCount] نفسها
 * الموحَّدة في `ArabicPlural.kt`، كي لا تخرج «1 أبيات» في رسالة المشرف.
 */
fun versesCountLabel(count: Int): String =
    arabicCount(count, "بيت واحد", "بيتان", "أبيات", "بيتاً")
