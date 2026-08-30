package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * 🛡️ حارس تكرار المحتوى عند الرفع (طلب المالك 2026-08-30 بعد اكتشاف 11
 * درساً مكرّر الملف، سبعةٌ منها أخطاء رفعٍ حقيقية ما كان أحد سيلحظها).
 *
 * قبل دخول أي ملف طابور الرفع تُحسب بصمته SHA-256 ويُبحث عنها في المكتبة
 * على حقلين: `sha256` (بصمة النسخة المُقدَّمة — تُطابق الرفع المعاد لملف
 * Opus/Ogg يُبقيه خطّ التطبيع كما هو) و`sourceSha256` (بصمة الملف الخام قبل
 * التطبيع — تُطابق إعادة رفع نفس MP3 الأصلي ولو اختلفت نسخته المقدَّمة).
 * وجود مطابق لا يمنع الرفع: يُبلَّغ المشرف فوراً بعنوان الدرس الموجود وقسمه
 * ومدته، ويلزمه **تأكيد ثانٍ صريح** ليمضي وهو يعلم أنه يكرّر درساً قائماً.
 */
object DuplicateAudioGuard {

    data class Match(
        val sha256: String,
        val fileName: String,
        val existingTitle: String,
        val existingSection: String,
        val existingDurationSeconds: Long,
    )

    /** بصمة محتوى ملف عبر ContentResolver — تدفّقياً بلا تحميل في الذاكرة. */
    fun sha256Of(context: Context, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * أول ملف من [files] بصمته موجودة في المكتبة ولم يؤكّده المشرف بعد
     * (بصمات [confirmed] تُتخطّى — جواب «أكّد الرفع مكرَّراً» السابق).
     * أي فشل شبكة/قراءة = لا اعتراض: الحارس تنبيهٌ لا بوابة تعطيل.
     */
    suspend fun firstMatch(
        context: Context,
        files: List<Pair<Uri, String>>,
        confirmed: Set<String>,
    ): Match? {
        val db = FirebaseFirestore.getInstance()
        for ((uri, name) in files) {
            val sha = sha256Of(context, uri) ?: continue
            if (sha in confirmed) continue
            for (field in listOf("sha256", "sourceSha256")) {
                val doc = runCatching {
                    db.collection("lessons")
                        .whereEqualTo(field, sha)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                }.getOrNull() ?: continue
                val data = doc.data.orEmpty()
                @Suppress("UNCHECKED_CAST")
                val unwrapped = (data["data"] as? Map<String, Any?>)
                    ?.let { it + data } ?: data
                return Match(
                    sha256 = sha,
                    fileName = name,
                    existingTitle = (unwrapped["title"] as? String).orEmpty()
                        .ifEmpty { (unwrapped["name"] as? String).orEmpty() },
                    existingSection = listOfNotNull(
                        unwrapped["categoryName"] as? String,
                        unwrapped["subcategoryName"] as? String,
                    ).filter { it.isNotBlank() }.joinToString(" ← "),
                    existingDurationSeconds =
                        (unwrapped["durationSeconds"] as? Number)?.toLong() ?: 0L,
                )
            }
        }
        return null
    }
}
