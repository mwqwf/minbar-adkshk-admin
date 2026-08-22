package com.ali.ishaqiyin_admin.data

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.tasks.await
import java.net.URLDecoder

/**
 * حذف ملفّات Firebase Storage ونوع محتواها. المسارات: الصوتيات في
 * `lessons/`، الكتب في `books/`.
 *
 * ⛔ لا مسار رفع هنا: الرفع كلّه صار عبر طابور `LessonUploadWorker`
 * باستئناف من البايت نفسه، وبقاء رافعٍ ثانٍ بلا استئناف ولا طابور ولا
 * إشعار اكتمال كان يغري بالبناء عليه فتعود المشكلة التي حُلّت.
 */
object StorageService {
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    /**
     * نوع المحتوى من الامتداد.
     *
     * ⚠️ الافتراضي **صوتيّ** لا `application/octet-stream`: قواعد التخزين
     * تشترط `contentType.matches('audio/.*')` على مسار `lessons/`، فكلّ
     * امتداد خارج القائمة كان يُرفض بـERROR_NOT_AUTHORIZED — رسالة توحي
     * بنقص صلاحية لا بصيغة ملفّ. والحالات واقعيّة: امتداد صوتيّ غير مغطّى
     * (.wma/.mka/.m4b/.oga) يظهره محدّد الصوت العامّ، وملفّ يصل بالمشاركة من
     * مزوّد لا يعرض DISPLAY_NAME فيبقى بلا نقطة ولا امتداد أصلاً.
     * أنواع الكتب (pdf) تبقى كما هي.
     */
    fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aac" -> "audio/aac"
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "amr" -> "audio/amr"
        "flac" -> "audio/flac"
        "wma" -> "audio/x-ms-wma"
        "mka" -> "audio/x-matroska"
        "weba", "webm" -> "audio/webm"
        "3gp", "3gpp" -> "audio/3gpp"
        "aif", "aiff" -> "audio/aiff"
        "mid", "midi" -> "audio/midi"
        "pdf" -> "application/pdf"
        else -> "audio/mpeg"
    }

    /** يستخرج مسار التخزين من رابط تنزيل Firebase أو يقبل مساراً مباشراً. */
    private fun resolvePath(urlOrPath: String): String? {
        if (urlOrPath.isEmpty()) return null
        if (!urlOrPath.startsWith("http")) return urlOrPath
        // .../o/lessons%2Ffile.mp3?alt=media&token=...
        val afterO = urlOrPath.split("/o/")
        if (afterO.size >= 2) {
            val encoded = afterO[1].substringBefore('?')
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        }
        return Regex("(lessons/[^?]+|books/[^?]+)").find(urlOrPath)?.value
    }

    /** نسخة صارمة للحذف: لا تسمح بحذف وثيقة Firestore إن بقي ملفها الصوتي. */
    suspend fun deleteFileOrThrow(urlOrPath: String) {
        val path = resolvePath(urlOrPath)
            ?: throw IllegalArgumentException("مسار تخزين غير صالح: $urlOrPath")
        try {
            storage.reference.child(path).delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }
}
