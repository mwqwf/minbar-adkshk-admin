package com.ali.ishaqiyin_admin.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import com.google.firebase.storage.StorageException
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
 * أساس معرّفات إشعارات الاكتمال — بعيد عن [UPLOAD_NOTIFICATION_ID] فلا
 * يمحو إشعارُ اكتمالٍ إشعارَ التقدّم الجاري.
 */
private const val DONE_NOTIFICATION_BASE = 500_000

/**
 * أساس معرّفات إشعارات الفشل الدائم — بعيد عن [DONE_NOTIFICATION_BASE]
 * (أقصى إزاحة 0xFFFF) فلا يمحو إشعارُ فشلٍ إشعارَ اكتمالٍ ولا العكس.
 */
private const val FAILED_NOTIFICATION_BASE = 600_000

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
            // ⏸ إيقاف مؤقّت: العامل ينسحب بنجاح ولا يُعاد جدولته — الطابور
            // والجلسات والملفّات كلّها باقية، ويوقظه زرّ «استئناف» وحده.
            if (UploadQueue.isPaused()) {
                markPaused()
                return Result.success()
            }
            // قراءة طازجة في كلّ دورة: الحالة قد تتغيّر أثناء الرفع
            // (إلغاء من المشرف، أو جلسة استئناف حُفظت).
            val item = UploadQueue.peek() ?: break
            lastPercent = 0
            val file = File(item.localPath)
            if (!file.exists() || file.length() == 0L) {
                // الملفّ ضاع (مسح يدويّ/تخزين ممتلئ). لا يُزال بصمت: المشرف
                // كان قد وُعِد بإشعار عند الاكتمال، فالإزالة الصامتة تجعله
                // ينتظر ما لن يأتي. يُركن بسبب مقروء ويصله إشعار فشل.
                Log.w(TAG, "missing local file for ${item.id}")
                UploadQueue.update(item.id) {
                    it.copy(parked = true, lastError = MISSING_FILE_ERROR)
                }
                UploadQueue.setProgress(null)
                notifyUploadParked(item, MISSING_FILE_ERROR)
                continue
            }

            UploadQueue.setProgress(UploadProgress(item.id, item.title, 0))
            try {
                val url = uploadWithResume(item, file)
                // يُحفظ مسار ما رُفع فور نجاحه: به وحده يستطيع الإلغاء لاحقاً
                // حذف الملفّ من التخزين إن رُكن العنصر قبل إنشاء وثيقته.
                UploadQueue.update(item.id) { it.copy(uploadedPath = url.second) }
                if (UploadQueue.consumeCancelled(item.id) || UploadQueue.byId(item.id) == null) {
                    // ألغاه المشرف أثناء الرفع: لا يُنشر، ويُحذف ما رُفع
                    // كي لا يبقى ملفّ يتيم في التخزين.
                    Log.i(TAG, "cancelled during upload: ${item.id}")
                    runCatching { StorageService.deleteFileOrThrow(url.second) }
                    UploadQueue.remove(item.id)
                    UploadQueue.setProgress(null)
                    continue
                }
                val lessonId = AdminRepository.addLesson(
                    title = item.title,
                    categoryId = item.categoryId,
                    subcategoryId = item.subcategoryId,
                    // اسما القسمين: بلاهما يكتبهما الخادم فارغَين فيسقط سطر
                    // القسم من نسخة الدرس في «سلة المحذوفات».
                    categoryName = item.categoryName,
                    subcategoryName = item.subcategoryName,
                    audioUrl = url.first,
                    audioStoragePath = url.second,
                    addedBy = item.addedBy,
                    featured = item.featured,
                    featuredUntilMs = item.featuredUntilMs,
                    // ختم لحظة الإدراج: يحفظ الترتيب في التطبيق العام.
                    createdAtMs = item.queuedAtMs,
                    // مفتاح ثابت: إعادة المحاولة بعد ضياع الردّ لا تُنشئ درساً ثانياً.
                    clientKey = item.id,
                )
                // «النص المشروح» المرافق (إن أُرفق بالنموذج): يُنشر بعد إنشاء
                // الدرس مباشرة. فشله لا يُفشل الدرس — المشرف يضيفه يدوياً.
                if (item.hasTranscript && lessonId.isNotEmpty()) {
                    runCatching { publishTranscript(item, lessonId) }
                        .onFailure { Log.w(TAG, "transcript publish failed for $lessonId: $it") }
                }
                UploadQueue.remove(item.id)
                UploadQueue.setProgress(null)
                // إشعار الاكتمال: المشرف أغلق الشاشة غالباً، فبلا هذا لا
                // يعرف أنّ درسه وصل إلّا بالعودة إلى اللوحة.
                notifyUploadDone(item)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // إيقاف WorkManager (فقدان قيد الشبكة/انتهاء مهلة الخدمة
                // الأماميّة) ليس فشلاً للدرس: لا يُحسب من المحاولات ولا
                // يمسّ جلسة الاستئناف — نُعيد رميه احتراماً للتزامن البنيوي.
                UploadQueue.setProgress(null)
                throw cancel
            } catch (e: Exception) {
                // ⏸ الإيقاف يُلغي مهمّة Firebase فترمي استثناءً — وهو ليس
                // فشلاً: لا يُحسب من المحاولات، ولا تُمسح جلسة الاستئناف،
                // فيُكمل «استئناف» من البايت نفسه. الفحص **قبل** فحص الشبكة
                // كي لا يُعاد جدولة العامل فيستأنف رفعاً أوقفه المشرف.
                if (UploadQueue.isPaused()) {
                    UploadQueue.update(item.id) { it.copy(lastError = null) }
                    markPaused(item.title)
                    return Result.success()
                }
                if (isNetworkFailure(e)) {
                    // انقطاع شبكة (ولو وصل ملفوفاً في StorageException بعد
                    // نفاد مهلة إعادة المحاولة الداخلية): لا يُحسب من
                    // المحاولات أبداً — نُبقي الجلسة والملفّ ونطلب إعادة
                    // التشغيل، فيُستأنف مهما طال الانقطاع.
                    UploadQueue.update(item.id) { it.copy(lastError = null) }
                    UploadQueue.setProgress(
                        UploadProgress(item.id, item.title, lastPercent, waitingForNetwork = true),
                    )
                    return Result.retry()
                }
                val attempts = item.attempts + 1
                // السبب يُترجَم مرّة واحدة هنا: رسالة Firebase الخام
                // بالإنجليزية كانت تُحفظ ثم تُعرض للمشرف كما هي.
                val reason = e.arabicReason()
                Log.w(TAG, "upload failed (${attempts}) for ${item.id}: $e")
                if (attempts >= MAX_ATTEMPTS) {
                    // فشل مستمرّ غير شبكيّ (رفض صلاحية، أو صيغة ملفّ ترفضها
                    // قواعد التخزين) — يُركن صراحةً فيخرج من دور الرفع ويبقى
                    // معروضاً ليقرّر المشرف، وينتقل الطابور لما بعده بدل أن
                    // يدور عليه بلا نهاية.
                    UploadQueue.update(item.id) {
                        it.copy(
                            attempts = attempts,
                            lastError = reason,
                            parked = true,
                        )
                    }
                    // بلا إشعار هنا كان الدرس الميّت يبدو للمشرف «بانتظار
                    // الدور» إلى الأبد — الركن يخرجه من الدور فلا يُرفع أبداً.
                    notifyUploadParked(item, reason)
                } else {
                    // فشل غير شبكيّ ⇒ الجلسة نفسها قد تكون منتهية الصلاحية،
                    // فتُمسح كي تبدأ المحاولة التالية جلسة جديدة بدل الدوران
                    // على الفشل ذاته.
                    UploadQueue.update(item.id) {
                        it.copy(attempts = attempts, lastError = reason, sessionUri = null)
                    }
                    UploadQueue.setProgress(null)
                    return Result.retry()
                }
                UploadQueue.setProgress(null)
            }
        }

        UploadQueue.setProgress(null)
        return Result.success()
    }

    private var lastPercent: Int = 0

    /**
     * حالة «موقوف مؤقّتاً» للواجهة: تُبقي العنوان والنسبة الأخيرة ظاهرين
     * فيعرف المشرف من أين سيُستأنف بدل شريط فارغ يوحي بأنّ شيئاً ضاع.
     */
    private fun markPaused(title: String? = null) {
        val current = UploadQueue.progress.value
        val item = UploadQueue.peek()
        UploadQueue.setProgress(
            UploadProgress(
                id = current?.id ?: item?.id.orEmpty(),
                title = title ?: current?.title ?: item?.title.orEmpty(),
                percent = current?.percent ?: lastPercent,
                paused = true,
            ),
        )
    }

    /**
     * هل هذا فشل شبكيّ؟ Firebase يلفّ انقطاع الشبكة في StorageException
     * (ليست IOException)، فبلا هذا الفحص كان الانقطاع الطويل يُعامل
     * كفشل دائم ويُركن الدرس بعد 5 دورات.
     */
    private fun isNetworkFailure(e: Throwable): Boolean {
        if (e is IOException) return true
        if (e is StorageException &&
            e.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED
        ) {
            return true
        }
        var cause: Throwable? = e.cause
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is IOException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    /** رفع مع استئناف — يعيد (رابط التنزيل، مسار التخزين). */
    private suspend fun uploadWithResume(
        item: PendingUpload,
        file: File,
    ): Pair<String, String> {
        // اسم نظيف: يُنزع معرّف الطابور كاملاً لا حتى أوّل شرطة سفليّة
        // (المعرّف نفسه يحوي شرطات: up_<millis>_<rand>).
        val cleanName = file.name.removePrefix("${item.id}_")
        val storagePath = "lessons/${item.queuedAtMs}_$cleanName"
        val ref = FirebaseStorage.getInstance().reference.child(storagePath)
        // نوع المحتوى: [StorageService.mimeForExt] يعيد نوعاً صوتياً دائماً
        // لمسار الدروس — الاسم بلا امتداد (ملفّ وصل بالمشاركة من مزوّد لا
        // يعرض DISPLAY_NAME) لم يعد يُرفض من قواعد التخزين.
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
            UploadQueue.bindActiveTask(item.id) { task.cancel() }
            var sessionSaved = item.sessionUri != null
            task.addOnProgressListener { snap ->
                // نحفظ جلسة الرفع مرّة واحدة فور صدورها: بها وحدها يُستأنف
                // الرفع بعد موت العمليّة بدل إعادته من الصفر.
                if (!sessionSaved) {
                    snap.uploadSessionUri?.let { uri ->
                        sessionSaved = true
                        UploadQueue.update(item.id) { it.copy(sessionUri = uri.toString()) }
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
        UploadQueue.clearActiveTask(item.id)

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

    /**
     * 🔔 إشعار «اكتمل رفع: <العنوان>» على قناة تنبيهات الإدارة نفسها التي
     * ينشئها AdminApplication (admin_alerts).
     *
     * مستقلّ عن إشعار التقدّم: ذاك مُلازم (ongoing) ويختفي بانتهاء العامل،
     * فلولا هذا لم يبقَ للمشرف أثر يخبره بالاكتمال. ومعرّفه مشتقّ من معرّف
     * العنصر كي لا يمحو إشعارُ درسٍ إشعارَ الذي قبله.
     */
    /**
     * نشر «النص المشروح» المرافق لدرس الطابور: رفع صور الصفحات المحفوظة
     * محليّاً إلى مساحة النصوص ثم استدعاء upsertLessonTranscript، وحذف
     * النسخ المحليّة بعد النجاح.
     */
    private suspend fun publishTranscript(item: PendingUpload, lessonId: String) {
        val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
        val uploadedPaths = mutableListOf<String>()
        item.transcriptImagePaths.forEachIndexed { index, localPath ->
            val local = File(localPath)
            if (!local.exists()) return@forEachIndexed
            val remotePath = "lesson_transcripts/$lessonId/${item.queuedAtMs}_$index.jpg"
            val metadata = com.google.firebase.storage.StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build()
            storage.reference.child(remotePath)
                .putFile(android.net.Uri.fromFile(local), metadata)
                .await()
            uploadedPaths.add(remotePath)
        }
        TranscriptsRepository.upsert(
            lessonId = lessonId,
            text = item.transcriptText,
            bookTitle = item.transcriptBookTitle,
            sourceRef = item.transcriptSourceRef,
            imagePaths = uploadedPaths,
        )
        item.transcriptImagePaths.forEach { runCatching { File(it).delete() } }
    }

    private fun notifyUploadDone(item: PendingUpload) {
        val text = "اكتمل رفع: ${item.title.ifBlank { item.fileName }}"
        val remaining = UploadQueue.liveCount()
        notifyUpload(
            item = item,
            contentTitle = "اكتمل رفع الدرس",
            text = text,
            bigText = if (remaining > 0) "$text\nبقي $remaining في طابور الرفع." else text,
            idBase = DONE_NOTIFICATION_BASE,
        )
    }

    /**
     * 🔔 إشعار الفشل الدائم — نظير [notifyUploadDone] وضرورته أشدّ: العنصر
     * المركون يخرج من دور الرفع فلن يُرفع أبداً، وبلا هذا الإشعار يظنّ
     * المشرف أنّ درسه ما زال في الدور.
     */
    private fun notifyUploadParked(item: PendingUpload, reason: String?) {
        val text = "تعذّر رفع: ${item.title.ifBlank { item.fileName }} — " +
            "أعد المحاولة من طابور الرفع"
        notifyUpload(
            item = item,
            contentTitle = "تعذّر رفع الدرس",
            text = text,
            bigText = if (reason.isNullOrBlank()) text else "$text\nالسبب: $reason",
            idBase = FAILED_NOTIFICATION_BASE,
        )
    }

    /** بناء إشعار الإدارة وإرساله — مشترك بين الاكتمال والفشل الدائم. */
    private fun notifyUpload(
        item: PendingUpload,
        contentTitle: String,
        text: String,
        bigText: String,
        idBase: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(applicationContext, AdminChannels.ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            ?.let { intent ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        item.id.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(idBase + (item.id.hashCode() and 0xFFFF), builder.build())
        }
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

        /** سبب مقروء يُعرض للمشرف حين تختفي النسخة المحليّة للدرس. */
        private const val MISSING_FILE_ERROR = "ضاع الملفّ المحلّي"

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
            // APPEND_OR_REPLACE لا KEEP: مع KEEP كان درس يُضاف لحظة انتهاء
            // العامل لا يبدأ رفعه أبداً. تشغيل إضافي بطابور فارغ رخيص —
            // doWork يفحص ويخرج فوراً.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
