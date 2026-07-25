package com.ali.ishaqiyin_admin.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.ishaqiyin_admin.R
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** قناة إشعار تقدّم الرفع (منخفضة الأهميّة — لا تُصدر صوتاً). */
private const val UPLOAD_CHANNEL = "admin_uploads"
private const val UPLOAD_NOTIFICATION_ID = 4711

/**
 * 📤 عامل رفع الدروس — يعالج الطابور **تسلسليّاً بالدور** فيصل أوّل درس
 * أُضيف أوّلاً إلى التطبيق العام.
 *
 * الاستئناف الحقيقي: نحفظ `uploadSessionUri` من Firebase فور صدورها، فإذا
 * انقطع الاتصال (أو مات التطبيق) استُكمل الرفع من البايت الذي توقّف عنده
 * بدل البدء من الصفر. وWorkManager يعيد تشغيلنا تلقائياً بقيد «متّصل».
 */
class LessonUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        UploadQueue.init(applicationContext)
        ensureChannel()
        runCatching { setForeground(foregroundInfo(null, 0)) }

        while (true) {
            val item = UploadQueue.peek() ?: break
            val file = File(item.localPath)
            if (!file.exists() || file.length() == 0L) {
                // الملفّ ضاع (مسح يدويّ/تخزين ممتلئ) — لا معنى لإبقائه.
                Log.w(TAG, "missing local file for ${item.id}")
                UploadQueue.remove(item.id)
                continue
            }

            UploadQueue.setProgress(UploadProgress(item.id, item.title, 0))
            try {
                val url = uploadWithResume(item, file)
                AdminRepository.addLesson(
                    title = item.title,
                    categoryId = item.categoryId,
                    subcategoryId = item.subcategoryId,
                    audioUrl = url.first,
                    audioStoragePath = url.second,
                    addedBy = item.addedBy,
                    featured = item.featured,
                    // ختم لحظة الإدراج: يحفظ الترتيب في التطبيق العام.
                    createdAtMs = item.queuedAtMs,
                )
                UploadQueue.remove(item.id)
                UploadQueue.setProgress(null)
            } catch (io: IOException) {
                // انقطاع شبكة: نُبقي الجلسة والملفّ ونطلب إعادة التشغيل.
                UploadQueue.update(item.copy(lastError = null))
                UploadQueue.setProgress(
                    UploadProgress(item.id, item.title, lastPercent, waitingForNetwork = true),
                )
                return Result.retry()
            } catch (e: Exception) {
                val attempts = item.attempts + 1
                Log.w(TAG, "upload failed (${attempts}) for ${item.id}: $e")
                if (attempts >= MAX_ATTEMPTS) {
                    // فشل مستمرّ غير شبكيّ (قسم محذوف/صلاحية) — نُبقيه معلّماً
                    // بالخطأ ليقرّر المشرف، وننتقل للتالي كي لا يتوقّف الطابور.
                    UploadQueue.update(
                        item.copy(
                            attempts = attempts,
                            lastError = e.message ?: "تعذّر الرفع",
                            seq = item.seq + PARK_OFFSET,
                        ),
                    )
                } else {
                    UploadQueue.update(item.copy(attempts = attempts, lastError = e.message))
                    return Result.retry()
                }
                UploadQueue.setProgress(null)
            }
        }

        UploadQueue.setProgress(null)
        return Result.success()
    }

    private var lastPercent: Int = 0

    /** رفع مع استئناف — يعيد (رابط التنزيل، مسار التخزين). */
    private suspend fun uploadWithResume(
        item: PendingUpload,
        file: File,
    ): Pair<String, String> {
        val storagePath = "lessons/${item.queuedAtMs}_${file.name.substringAfter('_')}"
        val ref = FirebaseStorage.getInstance().reference.child(storagePath)
        val metadata = StorageMetadata.Builder()
            .setContentType(StorageService.mimeForExt(item.fileName.substringAfterLast('.', "")))
            .build()

        val downloadUrl = suspendCancellableCoroutine { cont ->
            val existing = item.sessionUri?.let(Uri::parse)
            val task = if (existing != null) {
                ref.putFile(Uri.fromFile(file), metadata, existing)
            } else {
                ref.putFile(Uri.fromFile(file), metadata)
            }
            var sessionSaved = item.sessionUri != null
            task.addOnProgressListener { snap ->
                // نحفظ جلسة الرفع مرّة واحدة فور صدورها: بها وحدها يُستأنف
                // الرفع بعد موت العمليّة بدل إعادته من الصفر.
                if (!sessionSaved) {
                    snap.uploadSessionUri?.let {
                        sessionSaved = true
                        UploadQueue.update(item.copy(sessionUri = it.toString()))
                    }
                }
                if (snap.totalByteCount > 0) {
                    val pct = (snap.bytesTransferred * 100 / snap.totalByteCount).toInt()
                    if (pct != lastPercent) {
                        lastPercent = pct
                        UploadQueue.setProgress(UploadProgress(item.id, item.title, pct))
                        runCatching { setForegroundAsync(foregroundInfo(item.title, pct)) }
                    }
                }
            }
            task.addOnSuccessListener { cont.resume(Unit) }
            task.addOnFailureListener { cont.resumeWithException(it) }
            cont.invokeOnCancellation { runCatching { task.cancel() } }
        }.let { ref.downloadUrl.await().toString() }

        return downloadUrl to storagePath
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                UPLOAD_CHANNEL,
                "رفع الدروس",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun foregroundInfo(title: String?, percent: Int): ForegroundInfo {
        val remaining = UploadQueue.count()
        val text = when {
            title == null -> "جارٍ تجهيز الرفع…"
            remaining > 1 -> "$title — وبقي ${remaining - 1} غيره"
            else -> title
        }
        val notification = NotificationCompat.Builder(applicationContext, UPLOAD_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("رفع درس صوتي")
            .setContentText(text)
            .setProgress(100, percent, title == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UPLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(UPLOAD_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "LessonUpload"
        private const val MAX_ATTEMPTS = 5

        /** يُنحّى الفاشل نهائياً إلى آخر الطابور كي لا يحجب ما بعده. */
        private const val PARK_OFFSET = 1_000_000L
        const val WORK_NAME = "lesson_upload_queue"

        /** يوقظ الطابور: يبدأ فوراً إن كان هناك اتصال، وينتظره إن لم يكن. */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<LessonUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
