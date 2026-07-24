package com.ali.ishaqiyin_admin.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.tasks.await
import java.net.URLDecoder

/**
 * مقبض إلغاء لرفع جارٍ: تمرّره الشاشة إلى [StorageService.uploadFile]
 * وتستدعي [cancel] من زرّ «إلغاء» — فيتوقف الرفع فوراً ويرمي
 * StorageException بكود الإلغاء تعالجه الشاشة برسالة لطيفة.
 */
class UploadCanceller {
    private var task: UploadTask? = null
    var cancelled: Boolean = false
        private set

    fun attach(task: UploadTask) {
        this.task = task
        if (cancelled) task.cancel()
    }

    fun cancel() {
        cancelled = true
        runCatching { task?.cancel() }
    }
}

data class UploadResult(val url: String, val path: String)

/**
 * رفع/حذف الملفات في Firebase Storage (نفس بنية المشروع الأصلي).
 * المسارات: الصوتيات في `lessons/`، الكتب في `books/`.
 */
object StorageService {
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    /** هل هذا الخطأ إلغاءً مقصوداً من المستخدم؟ */
    fun isCancellation(e: Throwable): Boolean =
        e is StorageException && e.errorCode == StorageException.ERROR_CANCELED

    fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "amr" -> "audio/amr"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /**
     * يرفع ملفاً (من محدّد نظام أو ملفّ محلّي) ويعيد (downloadUrl, storagePath).
     * [folder] إمّا 'lessons' أو 'books'. [filename] يجب أن يكون فريداً.
     */
    suspend fun uploadFile(
        uri: Uri,
        folder: String,
        filename: String,
        onProgress: ((Double) -> Unit)? = null,
        canceller: UploadCanceller? = null,
    ): UploadResult {
        val ext = filename.substringAfterLast('.', "")
        val storagePath = "$folder/$filename"
        val ref = storage.reference.child(storagePath)
        val task = ref.putFile(
            uri,
            StorageMetadata.Builder().setContentType(mimeForExt(ext)).build(),
        )
        canceller?.attach(task)
        if (onProgress != null) {
            task.addOnProgressListener { snapshot ->
                if (snapshot.totalByteCount > 0) {
                    onProgress(snapshot.bytesTransferred.toDouble() / snapshot.totalByteCount * 100)
                }
            }
        }
        task.await()
        val url = ref.downloadUrl.await().toString()
        return UploadResult(url = url, path = storagePath)
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

    /** يحذف ملفاً من التخزين. يعتبر «الملف غير موجود» نجاحاً. */
    suspend fun deleteFile(urlOrPath: String): Boolean {
        val path = resolvePath(urlOrPath) ?: return false
        return try {
            storage.reference.child(path).delete().await()
            true
        } catch (e: StorageException) {
            e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND
        } catch (_: Exception) {
            false
        }
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
