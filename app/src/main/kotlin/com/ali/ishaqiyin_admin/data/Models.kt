package com.ali.ishaqiyin_admin.data

import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** بعض الوثائق القديمة مخزّنة ملفوفة بصيغة `{ data: {...} }`. */
@Suppress("UNCHECKED_CAST")
fun unwrap(raw: Map<String, Any?>): Map<String, Any?> {
    val inner = raw["data"]
    return if (inner is Map<*, *>) inner as Map<String, Any?> else raw
}

fun str(v: Any?): String = v?.toString()?.trim().orEmpty()

fun int(v: Any?): Int = when (v) {
    is Int -> v
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: 0
    else -> 0
}

/** يحوّل createdAt بأيّ صيغة (Timestamp/ISO/رقم) إلى ملّي ثانية للترتيب. */
fun parseDateMs(v: Any?): Long = when (v) {
    null -> 0L
    is Timestamp -> v.toDate().time
    is Date -> v.time
    is Number -> v.toLong()
    is String -> parseIsoMs(v)
    is Map<*, *> -> (v["seconds"] as? Number)?.let { it.toLong() * 1000 } ?: 0L
    else -> 0L
}

private val isoFormats = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd",
)

/** تحليل ISO-8601 المتساهل — نفس تساهل `DateTime.tryParse` في Dart. */
fun parseIsoMs(value: String): Long {
    val s = value.trim()
    if (s.isEmpty()) return 0L
    s.toLongOrNull()?.let { return it }
    for (pattern in isoFormats) {
        runCatching {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            if (pattern.endsWith("'Z'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.parse(s)!!.time
        }
    }
    return 0L
}

/** طابع UTC بصيغة ISO — نفس ما كانت تكتبه نسخة Flutter في Firestore. */
fun nowIso(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}

fun isoOf(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(Date(millis))
}

data class Category(
    val id: String,
    val name: String,
    val createdAtMs: Long,
) {
    companion object {
        fun fromDoc(id: String, raw: Map<String, Any?>): Category {
            val d = unwrap(raw)
            return Category(id = id, name = str(d["name"]), createdAtMs = parseDateMs(d["createdAt"]))
        }
    }
}

data class Subcategory(
    val id: String,
    val name: String,
    val categoryId: String,
    val createdAtMs: Long,
) {
    companion object {
        fun fromDoc(id: String, raw: Map<String, Any?>): Subcategory {
            val d = unwrap(raw)
            return Subcategory(
                id = id,
                name = str(d["name"]),
                categoryId = str(d["categoryId"]),
                createdAtMs = parseDateMs(d["createdAt"]),
            )
        }
    }
}

data class Lesson(
    val id: String,
    val title: String,
    val categoryId: String,
    val subcategoryId: String,
    val audioUrl: String,
    val audioStoragePath: String,
    val createdAtMs: Long,
    val hasCreatedAt: Boolean,
    val views: Int = 0,
    val featured: Boolean = false,
    /**
     * نهاية مدّة التمييز. `null` = تمييز دائم. بانقضائها يسقط الدرس من
     * «مختارات المنبر» في التطبيق العام فوراً (ترشيح عميل) وتُنظَّف الراية
     * خادميّاً بعدها.
     */
    val featuredUntilMs: Long? = null,
    val addedBy: String = "",
    val publishAtMs: Long? = null,
) {
    companion object {
        private fun extractSubcategoryId(d: Map<String, Any?>): String {
            val direct = str(d["subcategoryId"])
            if (direct.isNotEmpty()) return direct
            val sub = d["subcategory"]
            if (sub is Map<*, *>) return str(sub["_id"])
            return ""
        }

        fun fromDoc(id: String, raw: Map<String, Any?>): Lesson {
            val d = unwrap(raw)
            val rawCreated = d["createdAt"]
            val title = str(d["title"]).ifEmpty { str(d["name"]) }
            return Lesson(
                id = id,
                title = title,
                categoryId = str(d["categoryId"]),
                subcategoryId = extractSubcategoryId(d),
                audioUrl = str(d["audioUrl"]),
                audioStoragePath = str(d["audioStoragePath"]),
                createdAtMs = parseDateMs(rawCreated),
                hasCreatedAt = rawCreated != null && str(rawCreated).isNotEmpty(),
                views = int(d["views"]),
                featured = d["featured"] == true,
                featuredUntilMs = d["featuredUntil"]?.let { parseDateMs(it) }
                    ?.takeIf { it > 0L },
                addedBy = str(d["addedBy"]),
                publishAtMs = d["publishAt"]?.let { parseDateMs(it) },
            )
        }
    }
}

/**
 * مشرف لوحة التحكّم (نظير dashboard_users في نبراس) — مخزَّن في مجموعة
 * `dashboard_admins` بمعرّف وثيقة = البريد بحروف صغيرة. يُنشأ فقط عبر
 * رمز الاعتماد؛ المالك يحظر/يلغي الحظر/يحذف من التطبيق.
 */
data class DashAdmin(
    val email: String,
    val role: String,
    val blocked: Boolean,
    val blockMode: String,
    val displayName: String,
    val photoURL: String,
    val addedBy: String,
    val addedAtMs: Long,
    val lastSignedInAtMs: Long?,
) {
    val isOwner: Boolean get() = role == "owner"

    companion object {
        fun fromDoc(id: String, raw: Map<String, Any?>): DashAdmin {
            val d = unwrap(raw)
            val lastSignIn = d["lastSignedInAt"]
            return DashAdmin(
                email = str(d["email"]).ifEmpty { id },
                role = str(d["role"]).ifEmpty { "supervisor" },
                blocked = d["blocked"] == true,
                blockMode = str(d["blockMode"]),
                displayName = str(d["displayName"]),
                photoURL = str(d["photoURL"]),
                addedBy = str(d["addedBy"]),
                addedAtMs = parseDateMs(d["addedAt"]),
                lastSignedInAtMs = lastSignIn?.let { parseDateMs(it) },
            )
        }
    }
}

/**
 * رمز اعتماد مشرف معلَّق (نظير owner_codes في نبراس) — يقرؤه المالك فقط
 * ليُبلّغ به المرشّح يدوياً، إذ لا يوجد بريد مُعَدّ لإرساله تلقائياً.
 */
data class PendingOwnerCode(
    val code: String,
    val candidateEmail: String,
    val candidateName: String,
    val candidatePhotoURL: String,
    val expiresAtMs: Long,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs

    companion object {
        fun fromDoc(raw: Map<String, Any?>): PendingOwnerCode {
            val d = unwrap(raw)
            return PendingOwnerCode(
                code = str(d["code"]),
                candidateEmail = str(d["candidateEmail"]),
                candidateName = str(d["candidateName"]),
                candidatePhotoURL = str(d["candidatePhotoURL"]),
                expiresAtMs = parseDateMs(d["expiresAt"]),
            )
        }
    }
}
