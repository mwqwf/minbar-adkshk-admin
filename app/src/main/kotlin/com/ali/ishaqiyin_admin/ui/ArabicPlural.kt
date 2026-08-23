package com.ali.ishaqiyin_admin.ui

/**
 * صياغة عربية سليمة للعدد: مفرد ومثنّى وجمع قلّة (3–10) وجمع كثرة (11+).
 *
 * ⚠️ كانت هذه الدالّة مكرّرة في `DashboardScreen` و`BatchUploadSheet` بينما
 * بقيّة الشاشات تكتب «$n درساً» فتخرج «1 درساً» و«2 درساً» — فوُحِّدت هنا
 * لتكون مرجعاً واحداً لكلّ الواجهة.
 *
 * [one] و[two] نصّان كاملان بلا رقم (المفرد والمثنّى لا يُسبقان بعدد في
 * العربية الفصيحة)، و[few] و[many] تمييزان يُسبقان بالرقم.
 */
fun arabicCount(n: Int, one: String, two: String, few: String, many: String): String =
    when {
        n == 1 -> one
        n == 2 -> two
        n <= 10 -> "$n $few"
        else -> "$n $many"
    }

/** «درس واحد»/«درسان»/«ن دروس»/«ن درساً». */
fun lessonsCountLabel(count: Int): String =
    arabicCount(count, "درس واحد", "درسان", "دروس", "درساً")

/** «ملفّ واحد»/«ملفّان»/«ن ملفّات»/«ن ملفّاً». */
fun filesCountLabel(count: Int): String =
    arabicCount(count, "ملفّ واحد", "ملفّان", "ملفّات", "ملفّاً")

/** «صورة واحدة»/«صورتان»/«ن صور»/«ن صورة». */
fun imagesCountLabel(count: Int): String =
    arabicCount(count, "صورة واحدة", "صورتان", "صور", "صورة")

/** «رسالة واحدة»/«رسالتان»/«ن رسائل»/«ن رسالة». */
fun messagesCountLabel(count: Int): String =
    arabicCount(count, "رسالة واحدة", "رسالتان", "رسائل", "رسالة")
