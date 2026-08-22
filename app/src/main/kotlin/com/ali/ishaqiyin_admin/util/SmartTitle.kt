package com.ali.ishaqiyin_admin.util

import java.net.URLDecoder

// 🧠 استخراج عنوان نظيف من اسم ملف — منقول حرفياً من smart_title.dart في الأصل.
// يعالج أنماط المسجّلات وواتساب ومواقع التنزيل، ويعيد '' للأسماء الآلية البحتة.

private val autoPatterns = listOf(
    Regex("^(AUD|VID|PTT|IMG|DOC|GIF)[-_]\\d{8}[-_]WA\\d+", RegexOption.IGNORE_CASE),
    Regex("^WhatsApp\\s+(Audio|Video|Ptt|Image)\\b", RegexOption.IGNORE_CASE),
    Regex("^(new\\s+)?record(ing)?[-_\\s]*[\\d_.\\-\\s]*$", RegexOption.IGNORE_CASE),
    Regex(
        "^(voice|audio|video|sound|note|memo|track|file|item|untitled" +
            "|تسجيل|مقطع|ملف|صوت|بدون\\s*عنوان)[-_\\s]*[\\d٠-٩_.\\-\\s]*$",
        RegexOption.IGNORE_CASE,
    ),
    Regex("^[\\d٠-٩\\s_.\\-()~]+$"),
    Regex("^(REC|MIC|ZOOM|DS|DM|VN)[-_]?\\d+$", RegexOption.IGNORE_CASE),
)

private val junkInside = Regex(
    "(www\\.|https?:|\\.com|\\.net|\\.org|\\.info|kbps|kb/s|\\d{3,4}p\\b" +
        "|mp3|mp4|m4a|wav|flac|\\bhd\\b|\\bhq\\b|\\b4k\\b|official|lyrics" +
        "|audio\\s*only|youtube|download|free|copy|نسخة|تحميل|موقع|بجودة|حصري)",
    RegexOption.IGNORE_CASE,
)

fun smartTitleFromFileName(fileName: String): String {
    var s = fileName.trim()
    if (s.isEmpty()) return ""

    s = s.split(Regex("[/\\\\]")).last()
    val dot = s.lastIndexOf('.')
    if (dot > 0) s = s.substring(0, dot)

    if (s.contains('%')) {
        s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrElse { s.replace("%20", " ") }
    }

    for (pattern in autoPatterns) {
        if (pattern.containsMatchIn(s) && pattern.matchAt(s, 0) != null) return ""
    }

    // أقواس بمحتوى دعائي/تقني تُحذف كاملة؛ الأقواس ذات النص العادي تبقى.
    s = Regex("[\\[({]([^\\])}]*)[\\])}]").replace(s) { match ->
        val inner = match.groupValues[1]
        if (junkInside.containsMatchIn(inner)) " " else match.value
    }

    // بصمة موقع طليقة في الاسم.
    s = Regex("(^|\\s)(www\\.)?[\\w-]+\\.(com|net|org|info|me|tv|cc)(?=\\s|$)", RegexOption.IGNORE_CASE)
        .replace(s, " ")

    // ترقيم تسلسلي في البداية.
    val leadingIndex = Regex("^[\\s\\-–—ـ_.]*[(\\[]?\\s*[0-9٠-٩]{1,4}\\s*[)\\]]?[\\s\\-–—ـ_.]+")
    repeat(2) {
        if (leadingIndex.containsMatchIn(s) && leadingIndex.matchAt(s, 0) != null) {
            s = leadingIndex.replaceFirst(s, "")
        }
    }

    s = s.replace(Regex("[_~•·]+"), " ")
    s = s.replace(Regex("(?<=\\S)\\.(?=\\S)"), " ")
    s = s.replace(Regex("\\b(64|96|128|192|256|320)\\s?kbps\\b", RegexOption.IGNORE_CASE), " ")

    if (!s.contains(' ') && s.contains('-')) s = s.replace('-', ' ')

    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.replace(Regex("^[\\s\\-–—ـ_.,،؛;:]+|[\\s\\-–—ـ_.,،؛;:]+$"), "").trim()

    if (!Regex("[A-Za-z؀-ۿ]").containsMatchIn(s)) return ""
    if (s.length < 2) return ""
    return s
}

/**
 * 📁 عنوان درس من اسم ملفّه **لا يعود فارغاً أبداً** — لرفع مجلّد كامل حيث
 * لا يكتب المشرف عشرين عنواناً بيده.
 *
 * [smartTitleFromFileName] تعيد '' عمداً للأسماء الآليّة البحتة (AUD-…-WA0001،
 * «003») لأنّ حقل العنوان الواحد يبقى فارغاً ليكتبه المشرف — وهذا لا يصلح
 * لعشرين درساً دفعةً: تسقط هنا إلى تنظيف بسيط للاسم ثمّ إلى «درس ن»، ويبقى
 * التعديل بنقرة واحدة في ورقة المراجعة.
 */
fun lessonTitleFromFileName(fileName: String, index: Int): String {
    smartTitleFromFileName(fileName).takeIf { it.isNotBlank() }?.let { return it }
    var s = fileName.split(Regex("[/\\\\]")).last()
    val dot = s.lastIndexOf('.')
    if (dot > 0) s = s.substring(0, dot)
    s = s.replace(Regex("[_~•·\\-]+"), " ")
    // ترقيم بادئ: الرقم يعيش في ترتيب القائمة لا في العنوان.
    s = s.replace(Regex("^[\\s0-9٠-٩.)\\]]+"), "")
    s = s.replace(Regex("\\s+"), " ").trim()
    return s.ifBlank { "درس ${index + 1}" }
}

/**
 * ترتيب طبيعيّ بأرقام أسماء الملفّات: «10» **بعد** «9» لا قبله.
 *
 * ⛔ ترتيب الرفع هو ترتيب ظهور الدروس في التطبيق، ومنتقي النظام يعيدها
 * بترتيب عرضه النصّيّ الذي يضع «الدرس 10» قبل «الدرس 9» — فينقلب ترتيب
 * سلسلة كاملة.
 *
 * ⚠️ يكفي **ملفّان مرقّمان**: شرطُ «كلّها مرقّمة» كان يُسقط الفرز كلّه عن
 * مجلّد فيه «مقدمة.mp3» أو «خاتمة.mp3» بين تسعة عشر ملفّاً مرقّماً — وهو
 * المجلّد الواقعيّ لا الحالة النادرة. والأسماء بلا أرقام تُرتَّب نصّياً
 * (المقارن يتحمّلها)، والفرز مستقرّ فلا يُحرّك المتساويات.
 */
fun <T> List<T>.sortedByNaturalName(name: (T) -> String): List<T> {
    if (size < 2) return this
    if (count { hasDigit.containsMatchIn(name(it)) } < 2) return this
    return sortedWith { a, b -> compareNaturalNames(name(a), name(b)) }
}

private val hasDigit = Regex("[0-9٠-٩]")

/** مقاطع متناوبة: أرقام تُقارن عدديّاً وما سواها نصّاً. */
private val nameChunks = Regex("\\d+|\\D+")

private fun westernDigits(s: String): String =
    s.map { ch -> if (ch in '٠'..'٩') '0' + (ch - '٠') else ch }.joinToString("")

internal fun compareNaturalNames(a: String, b: String): Int {
    val left = nameChunks.findAll(westernDigits(a)).map { it.value }.toList()
    val right = nameChunks.findAll(westernDigits(b)).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val diff = if (x[0].isDigit() && y[0].isDigit()) {
            // بالطول ثمّ نصّاً بعد إسقاط الأصفار البادئة: `toLong` تنهار على
            // أرقام أسماء المسجّلات (تاريخ+وقت من 14 خانة).
            val xs = x.trimStart('0').ifEmpty { "0" }
            val ys = y.trimStart('0').ifEmpty { "0" }
            if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys)
        } else {
            x.compareTo(y, ignoreCase = true)
        }
        if (diff != 0) return diff
    }
    return left.size - right.size
}
