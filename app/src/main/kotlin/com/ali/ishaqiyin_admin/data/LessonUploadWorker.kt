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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.ishaqiyin_admin.R
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
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
 * ⏸ إشارة «أُوقفت المهمّة مؤقّتاً».
 *
 * ⛔ مهمّة Firebase الموقوفة بـ`pause()` لا تُطلق نجاحاً ولا فشلاً، فبلا هذه
 * الإشارة يبقى العامل معلّقاً في `suspendCancellableCoroutine` إلى الأبد بعد
 * كلّ ضغطة «إيقاف مؤقّت». ليست `CancellationException` عمداً: الإيقاف قرار
 * مشرف لا إيقاف نظام، ومساراهما مختلفان.
 */
private class UploadPausedException : Exception("upload paused")

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

    /**
     * ⛔ لا `Result.failure()` في أيّ مسار: مع `APPEND` فإنّ فشل الأب يُلغي
     * كلّ أبنائه، فلا يبقى للطابور عمل مجدول واحد — والواجهة كانت لا تقرأ
     * `WorkInfo` فلا تملك ما تكتشف به ذلك، فتبقى تقول «بانتظار الدور» أبداً.
     * لذلك يُلَفّ جسد العمل كلّه، وأيّ عطل يهرب يعود بـ`retry` لا بـ`failure`.
     */
    override suspend fun doWork(): Result {
        return try {
            runQueue()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.e(TAG, "doWork crashed (attempt $runAttemptCount)", t)
            retryOrGiveUp("تعذّر تشغيل الرفع: ${t.arabicReason()}")
        }
    }

    /**
     * ⛔ **كلّ** انسحاب مؤقّت يمرّ من هنا: لا يبقى في `doWork` مسار واحد يعيد
     * `retry` بلا عدّاد.
     *
     * `runAttemptCount` عدّاد WorkManager للمحاولات المتتالية (يصفّره نجاحٌ أو
     * `REPLACE`)، فيغطّي المسارات التي لا عدّاد لها في العنصر نفسه: انهيار
     * `doWork`، وإيقاف النظام، وسباق الإيقاف/الاستئناف. وعند بلوغ السقف
     * يُركن رأس الطابور بإشعار صريح بدل الدوران الصامت إلى الأبد.
     */
    private fun retryOrGiveUp(reason: String): Result {
        if (runAttemptCount < MAX_RUN_ATTEMPTS) return Result.retry()
        Log.e(TAG, "giving up after $runAttemptCount starts: $reason")
        // ⛔ **لا يُركن هنا درسٌ أبداً**، وهذا مقصود:
        //
        // `runAttemptCount` **ليس عدّاد فشل** — إنّه عدّاد *مرّات البدء*:
        // `WorkerWrapper` يزيده عند كل انتقال ENQUEUED⇒RUNNING، وإيقافُ
        // النظام للعامل يعيده إلى ENQUEUED. فرفعٌ طويل أوقفه النظام اثنتي
        // عشرة مرّة (Doze، أو سقف dataSync، أو إسبات سامسونج) يبلغ السقف
        // وهو **سليم تماماً** — وكان يُركن هنا بإشعار «تعذّر رفع الدرس»
        // ويخرج من دور الرفع، بينما عدّاداته الحقيقيّة كلّها بعيدة عن
        // سقوفها. وزيادةً كان يُركن `peek()` — أي رأس الطابور، وقد لا
        // يكون هو الذي فشل أصلاً.
        //
        // العدّادات التي تعرف ما فشل ولماذا محفوظةٌ في العنصر نفسه:
        // `attempts` و`netFailures` و`interruptions` — وهي وحدها التي
        // تَركن. وهنا نكتفي بإنهاء العقدة بنجاح، والبانر يقول للمشرف
        // «⚠️ متوقّف — اضغط لإعادة التشغيل» لأنّ لا `WorkInfo` بعدها.
        //
        // ولا `failure` أبداً: فشل الأب مع APPEND يُلغي **كلّ** أبنائه فلا
        // يبقى للطابور عمل مجدول واحد.
        //
        // ⚠️ لكنّ الانتهاء هنا SUCCEEDED كان يترك الطابور **بلا عمل مجدول
        // واحد** وفيه دروس حيّة: يتجمّد الرفع حتى يتدخّل إنسان أو تعود الشبكة،
        // خلافاً لِما تَعِد به الورقة نصّاً («الرفع يكمل في الخلفية»). عقدة
        // جديدة بعدّاد بدء مصفَّر تُبقي الوعد قائماً.
        if (UploadQueue.peek() != null) kick(applicationContext)
        return Result.success()
    }

    private suspend fun runQueue(): Result {
        UploadQueue.init(applicationContext)
        ensureChannel()
        startForeground()

        // ما فشل في هذه التشغيلة بعينها: الحلقة تُدوّر إلى ما بعده بدل أن
        // ينسحب العامل. ⚠️ كان مسارا الفشل يخرجان بـ`return Result.retry()`
        // لا بـ`continue`، فدرس واحد ملفّه تالف يحبس عشرين درساً خلفه إلى
        // الأبد وكلّها تُعرض «بانتظار الدور» — نصّ صادق شكلاً كاذب معنى.
        val skipped = mutableSetOf<String>()
        // عنصر واحد يُمنح جلسة جديدة مرّة واحدة لكلّ تشغيلة بعد «جلسة ميّتة».
        val freshSession = mutableSetOf<String>()
        var needsRetry = false

        while (true) {
            // ⚠️ لم يكن يُفحص `isStopped` في أيّ موضع، فتمضي الشيفرة على عامل
            // ميت: كتابات تفضيلات ونداءات شبكة بلا عدّ ولا أثر محفوظ.
            if (isStopped) {
                val reason = stopReasonText()
                Log.w(TAG, "stopReason=${stopReasonCode()} ($reason) — انسحاب نظيف")
                UploadQueue.setProgress(null)
                return retryOrGiveUp(reason)
            }
            // ⏸ إيقاف مؤقّت: العامل ينسحب بنجاح ولا يُعاد جدولته — الطابور
            // والجلسات والملفّات كلّها باقية، ويوقظه زرّ «استئناف» وحده.
            if (UploadQueue.isPaused()) {
                markPaused()
                return Result.success()
            }
            // قراءة طازجة في كلّ دورة: الحالة قد تتغيّر أثناء الرفع
            // (إلغاء من المشرف، أو جلسة استئناف حُفظت).
            val item = UploadQueue.peek(skipped) ?: break
            // 🔒 حارس ملكيّة: `kickNow` بـREPLACE يُنشئ WorkSpec بمعرّف جديد،
            // فحارس Processor لا يمنع تشغيل عامل ثانٍ بينما الأوّل ينسحب
            // (و`pause()` غير متزامن). كاتبان على جلسة استئناف واحدة =
            // إزاحات متضاربة. ⛔ لا يبدأ عاملان على العنصر نفسه أبداً.
            if (UploadQueue.isActive(item.id)) {
                Log.w(TAG, "item ${item.id} already active — انسحاب لتفادي كاتبين")
                return retryOrGiveUp("تعارض تشغيلَي رفع")
            }
            lastPercent = item.lastPercent
            val file = File(item.localPath)
            if (!file.exists() || file.length() == 0L) {
                // الملفّ ضاع (مسح يدويّ/تخزين ممتلئ). لا يُزال بصمت: المشرف
                // كان قد وُعِد بإشعار عند الاكتمال، فالإزالة الصامتة تجعله
                // ينتظر ما لن يأتي. يُركن بسبب مقروء ويصله إشعار فشل.
                Log.w(TAG, "missing local file for ${item.id}")
                park(item, MISSING_FILE_ERROR)
                skipped += item.id
                continue
            }

            Log.i(TAG, "start id=${item.id} pct=${item.lastPercent}")
            // الحالة تُثبَّت على القرص **قبل** أوّل بايت: بها وحدها يعرف
            // التطبيق بعد إعادة الإقلاع أنّ هذا الدرس كان في الطريق.
            UploadQueue.markUploading(item.id, item.lastPercent)
            UploadQueue.setProgress(UploadProgress(item.id, item.title, item.lastPercent))
            try {
                val url = uploadWithResume(item, file)
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
                // 🔋 ننسحب بعد كلّ درس مكتمل ونُعيد الإدراج: خدمة dataSync
                // محكومة بستّ ساعات لكلّ 24، وحلقة تستنزف الطابور كلّه في
                // تشغيلة واحدة كانت تحرق الميزانيّة دفعةً واحدة ثمّ يعود كلّ
                // رفع بعدها محكوماً بعشر دقائق. ⚠️ الترتيب لا يتأثّر: الدور
                // محفوظ في `seq` وفي `peek` لا في سلسلة WorkManager.
                if (UploadQueue.peek(skipped) != null) {
                    kick(applicationContext)
                    return Result.success()
                }
            } catch (cancel: CancellationException) {
                // إيقاف WorkManager (فقدان قيد الشبكة/انتهاء مهلة الخدمة
                // الأماميّة/Doze) ليس فشلاً للدرس: لا يُحسب من المحاولات ولا
                // يمسّ جلسة الاستئناف — نُعيد رميه احتراماً للتزامن البنيوي.
                // ⚠️ لكنّه كان يمرّ بلا أثر محفوظ يقول «أُوقفت هنا»، فيُعرض
                // العنصر بعدها «بانتظار الدور» بشريط عند صفر.
                val reason = stopReasonText()
                Log.w(TAG, "stopReason=${stopReasonCode()} ($reason) id=${item.id} pct=$lastPercent")
                val stops = item.interruptions + 1
                UploadQueue.update(item.id) {
                    it.copy(
                        state = UploadState.INTERRUPTED,
                        interruptions = stops,
                        stoppedReason = reason,
                        lastPercent = lastPercent,
                        lastRunAtMs = System.currentTimeMillis(),
                    )
                }
                // ⛔ للانسحاب سقف أيضاً: كان `interruptions` يُزاد ولا يُفحص في
                // سطر واحد، فدرس يوقفه النظام في كلّ دورة (إسبات/مهلة الخدمة
                // الأماميّة) يدور أبداً بلا ركن ولا إشعار ولا مخرج للمشرف.
                if (stops >= MAX_INTERRUPTIONS) {
                    park(item, "$reason — توقّف $stops مرّة", item.attempts)
                }
                UploadQueue.setProgress(null)
                throw cancel
            } catch (e: Exception) {
                // إلغاء المشرف للعنصر الجاري يُلغي مهمّة Firebase فترمي
                // استثناءً عاماً: بلا هذا الفحص كان يُحسب محاولةً على عنصر
                // أزيل أصلاً ويُعاد العامل بتأخير backoff يعطّل بقية الطابور،
                // ويبقى العلم في `cancelled` إلى الأبد.
                if (UploadQueue.consumeCancelled(item.id) || UploadQueue.byId(item.id) == null) {
                    UploadQueue.setProgress(null)
                    continue
                }
                // ⏸ الإيقاف يوقف مهمّة Firebase فتصل إشارة [UploadPausedException]
                // — وهو ليس فشلاً: لا يُحسب من المحاولات، ولا تُمسح جلسة
                // الاستئناف، فيُكمل «استئناف» من البايت نفسه. الفحص **قبل**
                // فحص الشبكة كي لا يُعاد جدولة العامل فيستأنف رفعاً أوقفه المشرف.
                if (UploadQueue.isPaused()) {
                    UploadQueue.update(item.id) { it.copy(state = UploadState.QUEUED) }
                    markPaused(item.title)
                    return Result.success()
                }
                if (e is UploadPausedException) {
                    // أُعيد التشغيل قبل أن ننسحب: ننسحب بـretry فيبدأ من جديد
                    // بالجلسة نفسها (و«استئناف» يوقظه فوراً بـkickNow).
                    Log.i(TAG, "resumed while pausing — retry")
                    UploadQueue.setProgress(null)
                    return retryOrGiveUp("تعذّر استئناف الرفع")
                }
                when {
                    // ☠️ جلسة ميّتة: أنهاها الخادم أو مضت صلاحيّتها (404/410).
                    // ⛔ لا تُعدّ فشلاً شبكيّاً: رسالتها `IOException` صريح،
                    // وكان التصنيف الفضفاض يعيدها إلى الأبد على الجلسة ذاتها.
                    isDeadSession(e) && freshSession.add(item.id) -> {
                        Log.i(TAG, "session wiped: server terminated session (${e.message})")
                        UploadQueue.saveSession(item.id, null)
                        UploadQueue.setProgress(null)
                        continue
                    }
                    isNetworkFailure(e) -> {
                        // انقطاع شبكة: نُبقي الجلسة والملفّ. ⛔ وله عدّاد
                        // محفوظ بسقف — كان هذا الفرع يعيد `retry` بلا عدّ ولا
                        // ركن ولا إشعار: مسار بلا نهاية يبقي الدرس عالقاً أبداً.
                        val net = item.netFailures + 1
                        Log.w(TAG, "network failure ($net) for ${item.id}: $e")
                        if (net >= MAX_NET_FAILURES) {
                            park(item, "تعذّر الاتصال بعد $net محاولة")
                        } else {
                            // ⚠️ لا نمحو الأثر: كان `lastError = null` يُخفي
                            // سطر الخطأ **وزرّ إعادة المحاولة معاً**، فيبقى
                            // العنصر بلا مخرج غير حذفه وفقد نسخته الوحيدة.
                            UploadQueue.update(item.id) {
                                it.copy(
                                    netFailures = net,
                                    state = UploadState.WAITING,
                                    // ⚠️ لا يدّعي السببُ المحفوظ انقطاعاً:
                                    // `isNetworkFailure` يصنّف `SocketException`
                                    // و`SSLException` شبكيّاً بلا اشتراط انقطاع،
                                    // فكان النصّ يقول «بانتظار عودته» فوق واي‑فاي
                                    // سليم. ادّعاء الانقطاع صار للواجهة وحدها،
                                    // وهي تقرؤه من [NetworkMonitor.online] الحيّ.
                                    lastError = "تعذّر النقل — يعيد المحاولة (المحاولة $net)",
                                    lastPercent = lastPercent,
                                )
                            }
                            needsRetry = true
                        }
                        UploadQueue.setProgress(null)
                        skipped += item.id
                        continue
                    }
                    else -> {
                        val attempts = item.attempts + 1
                        // السبب يُترجَم مرّة واحدة هنا: رسالة Firebase الخام
                        // بالإنجليزية كانت تُحفظ ثم تُعرض للمشرف كما هي.
                        val reason = e.arabicReason()
                        Log.w(TAG, "upload failed ($attempts) for ${item.id}: $e")
                        if (attempts >= MAX_ATTEMPTS) {
                            // فشل مستمرّ غير شبكيّ (رفض صلاحية، أو صيغة ملفّ
                            // ترفضها قواعد التخزين) — يُركن صراحةً فيخرج من
                            // دور الرفع ويبقى معروضاً ليقرّر المشرف.
                            park(item, reason, attempts)
                        } else {
                            // ⛔ **الجلسة تبقى**: كان هذا الفرع يمسحها في كلّ
                            // فشل غير شبكيّ، وهو أوسع ممّا يبدو — `isNetworkFailure`
                            // لا يعدّ `ERROR_RETRY_LIMIT_EXCEEDED` انقطاعاً إلا
                            // والشبكة معلَنة ساقطة، بينما `NetworkMonitor` يعدّها
                            // قائمة بمجرّد إعلان `INTERNET` بلا `VALIDATED`.
                            // فشبكةٌ «متّصلة لا تنقل» — وهي بالضبط الحالة التي
                            // بُنيت عليها هذه المهمّة — كانت تسقط هنا فتُمحى
                            // نقطة الاستئناف ويبدأ الرفع من الصفر في كل دورة.
                            //
                            // وانتهاءُ الجلسة مُغطّى في موضعين آخرين لا ثالث
                            // لهما: [isDeadSession] (٤٠٤/٤١٠ أو رسالة الخادم
                            // الصريحة)، وفحص عمر الجلسة قبل المحاولة. فلا
                            // حاجة إلى محوٍ احتياطيّ يخسر كل ما رُفع.
                            //
                            // ويبقى مخرجٌ واحد: **المحاولة الأخيرة** تبدأ
                            // بجلسة جديدة، فإن كان العطل في الجلسة نفسها
                            // ولم يُكتشف، جُرِّب البديل مرّة قبل الركن.
                            val lastTry = attempts >= MAX_ATTEMPTS - 1
                            if (lastTry) {
                                Log.i(TAG, "session wiped: last attempt before parking ($reason)")
                            }
                            UploadQueue.update(item.id) {
                                it.copy(
                                    attempts = attempts,
                                    lastError = reason,
                                    sessionUri = if (lastTry) null else it.sessionUri,
                                    sessionSavedAtMs = if (lastTry) 0L else it.sessionSavedAtMs,
                                    lastPercent = lastPercent,
                                    state = UploadState.QUEUED,
                                )
                            }
                            needsRetry = true
                        }
                        UploadQueue.setProgress(null)
                        skipped += item.id
                        continue
                    }
                }
            } finally {
                // كانت تُمسح عند النجاح فقط؛ عند الفشل يبقى زوج إلغاء قديم
                // يشير إلى مهمّة ميتة.
                UploadQueue.clearActiveTask(item.id)
            }
        }

        UploadQueue.setProgress(null)
        // ⛔ `retry` فقط إن بقي عنصر فاشل غير مركون؛ وإلّا `success` نظيفة.
        return if (needsRetry) retryOrGiveUp("تعذّر إكمال الرفع") else Result.success()
    }

    /**
     * ⚠️ `@Volatile`: يُكتب من خيط المستمعين ويُقرأ من خيط العامل، فبلاها
     * تُحفظ في «انقطع عند س%» نسبةٌ أقدم من الحقيقيّة.
     */
    @Volatile
    private var lastPercent: Int = 0

    /**
     * ركن نهائيّ: يخرج العنصر من دور الرفع ويصل المشرفَ إشعارٌ صريح.
     * ⚠️ بلا الإشعار كان الدرس الميّت يبدو «بانتظار الدور» إلى الأبد.
     */
    private fun park(item: PendingUpload, reason: String, attempts: Int = item.attempts) {
        UploadQueue.update(item.id) {
            it.copy(
                attempts = attempts,
                lastError = reason,
                parked = true,
                state = UploadState.PARKED,
            )
        }
        UploadQueue.setProgress(null)
        notifyUploadParked(item, reason)
    }

    /**
     * ⚠️ فشل بدء الخدمة الأماميّة كان يُبتلع في `runCatching` بلا سطر واحد:
     * على Android 12+ يُرمى `ForegroundServiceStartNotAllowedException` حين
     * يبدأ العمل والتطبيق في الخلفية، وبلا خدمة أماميّة يخضع الرفع لحدّ
     * JobScheduler (عشر دقائق) فلا يكتمل ملفّ 100MB أبداً. ⛔ لا يُبتلع ثانيةً.
     */
    private suspend fun startForeground() {
        runCatching { setForeground(foregroundInfo(null, 0)) }
            .onSuccess { UploadQueue.setBackgroundBlocked(false) }
            .onFailure {
                Log.e(TAG, "setForeground failed — الرفع بلا حماية الخلفية", it)
                UploadQueue.setBackgroundBlocked(true)
            }
    }

    private var foregroundWatched = false

    /**
     * ⚠️ `setForegroundAsync` يبلّغ الفشل عبر الـFuture لا برميٍ، فـ
     * `runCatching` وحدها **لم تكن تلتقطه أصلاً**. نراقب أوّل نداء فقط.
     */
    private fun pushForeground(title: String, percent: Int) {
        val future = runCatching { setForegroundAsync(foregroundInfo(title, percent)) }
            .getOrElse {
                Log.e(TAG, "setForegroundAsync threw — الرفع بلا حماية الخلفية", it)
                UploadQueue.setBackgroundBlocked(true)
                return
            }
        if (foregroundWatched) return
        foregroundWatched = true
        runCatching {
            future.addListener(
                Runnable {
                    runCatching { future.get() }.onFailure {
                        Log.e(TAG, "setForegroundAsync failed — الرفع بلا حماية الخلفية", it)
                        UploadQueue.setBackgroundBlocked(true)
                    }
                },
                Executor { it.run() },
            )
        }
    }

    /** رمز سبب إيقاف العامل — متاح من API 31 فقط، وقبله «غير معروف». */
    private fun stopReasonCode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { stopReason }.getOrDefault(WorkInfo.STOP_REASON_UNKNOWN)
        } else {
            WorkInfo.STOP_REASON_UNKNOWN
        }

    /**
     * سبب الإيقاف بالعربية — يُحفظ في العنصر ويُعرض للمشرف.
     * ⚠️ كانت كلّ مُطلِقات الإيقاف (قيد الشبكة، مهلة dataSync، Doze، إسبات
     * سامسونج) تُنتج المشهد المرئيّ نفسه تماماً فيستحيل التشخيص.
     */
    private fun stopReasonText(): String = when (stopReasonCode()) {
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "انقطع الاتصال"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ->
            "انتهت مهلة الرفع في الخلفية — حدّ النظام اليوميّ"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
        WorkInfo.STOP_REASON_DEVICE_STATE,
        -> "أوقفه النظام لتوفير البطارية"
        WorkInfo.STOP_REASON_APP_STANDBY,
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
        -> "قيّد النظام التطبيق في الخلفية"
        WorkInfo.STOP_REASON_QUOTA -> "استنفد التطبيق حصّة التشغيل في الخلفية"
        WorkInfo.STOP_REASON_TIMEOUT -> "تجاوز الرفع مهلة النظام"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "أُعيد تشغيل الطابور"
        WorkInfo.STOP_REASON_USER -> "أوقفه المستخدم"
        else -> "أوقفه النظام"
    }

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
     * هل هذا فشل شبكيّ؟
     *
     * ⛔ تصنيف ضيّق عمداً. كان الفحص القديم يعيد `true` لكلّ `IOException`
     * وكلّ سبب `IOException` حتى عمق 8 — و`StorageException` نفسها ترث
     * `IOException`، ورسالة «الجلسة أنهاها الخادم» ‏`IOException` صريح،
     * وكذلك `ENOSPC` (تخزين ممتلئ) و`FileNotFoundException`. فكانت أعطال
     * محلّية دائمة تُصنَّف «انقطاع شبكة» فتدور في حلقة أبديّة بلا عدّاد.
     * و`ERROR_RETRY_LIMIT_EXCEEDED` لا يُعدّ شبكيّاً إلّا والشبكة مقطوعة فعلاً.
     */
    private fun isNetworkFailure(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (depth < CAUSE_DEPTH) {
            val current = cause ?: return false
            when (current) {
                is UnknownHostException,
                is SocketTimeoutException,
                is SocketException,
                is SSLException,
                -> return true
                is StorageException ->
                    if (current.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED &&
                        !NetworkMonitor.online.value
                    ) {
                        return true
                    }
                else -> Unit
            }
            cause = current.cause
            depth++
        }
        return false
    }

    /**
     * ☠️ جلسة الرفع القابلة للاستئناف ماتت على الخادم (أُنهيت أو مضت
     * صلاحيّتها): العلاج مسحُها وبدءُ جلسة جديدة، لا إعادة المحاولة عليها.
     * ⛔ كانت تُصنَّف «انقطاع شبكة» فلا يُمسح `sessionUri` أبداً ⇒ دوران أبديّ.
     */
    private fun isDeadSession(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (depth < CAUSE_DEPTH) {
            val current = cause ?: return false
            val message = current.message.orEmpty()
            if (message.contains("terminated the upload session", ignoreCase = true) ||
                message.contains("resumable session", ignoreCase = true)
            ) {
                return true
            }
            if (current is StorageException &&
                (current.httpResultCode == 404 || current.httpResultCode == 410)
            ) {
                return true
            }
            cause = current.cause
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

        // ⏳ جلسة أقدم من يوم تُهمَل **قبل** المحاولة لا بعد فشلها: جلسات GCS
        // القابلة للاستئناف تنتهي صلاحيّتها، وعنصر بقي في الطابور عشرة أيّام
        // كان يدور على جلسة ميتة يقيناً بلا مخرج.
        var session = item.sessionUri
        if (session != null &&
            System.currentTimeMillis() - item.sessionSavedAtMs > UploadQueue.SESSION_MAX_AGE_MS
        ) {
            Log.i(TAG, "session wiped: older than 24h id=${item.id}")
            UploadQueue.saveSession(item.id, null)
            session = null
        }
        val resuming = session != null
        var loggedResume = false
        val source = Uri.fromFile(file)

        suspendCancellableCoroutine<Unit> { cont ->
            val existing = session?.let(Uri::parse)
            val task = if (existing != null) {
                ref.putFile(source, metadata, existing)
            } else {
                ref.putFile(source, metadata)
            }
            // مقبضان لا مقبض واحد: الإيقاف غير المقصود يُوقف ولا يهدم،
            // والإلغاء الصريح من المشرف وحده هو الذي يهدم الجلسة.
            UploadQueue.bindActiveTask(
                id = item.id,
                pause = { task.pause() },
                cancel = { task.cancel() },
            )
            /**
             * تُلتقط الجلسة من **كلّ** مخرج للمهمّة: نبضة تقدّم، أو إيقاف، أو
             * فشل، أو إلغاء العامل.
             *
             * ⚠️ قراءة `task.snapshot` فور `putFile` لا تنفع: الجلسة القابلة
             * للاستئناف لا تصدر إلّا بعد رحلة شبكة، فالقيمة `null` يقيناً
             * حينها. والثغرة الحقيقيّة — انقطاع بين إنشاء الجلسة وأوّل نبضة —
             * يغلقها التقاطُها في مستمعَي الفشل والإلغاء، فلا يبقى مخرج واحد
             * يفلت منه عنوانُ الجلسة.
             */
            fun keepSession(uploaded: Uri?) {
                val text = uploaded?.toString() ?: return
                if (text == session) return
                session = text
                UploadQueue.saveSession(item.id, text)
            }
            // ⚠️ بلا Executor يعمل المستمع على **الخيط الرئيسي**، وفيه
            // `commit()` (fsync حاجب) وقراءة الطابور كاملاً وإعادة كتابته مع
            // نصوص «النص المشروح» — تجميد إطارات وخطر ANR. خيط واحد يحفظ
            // ترتيب النبضات ويُبقي الواجهة حرّة.
            task.addOnProgressListener(listenerExecutor) { snap ->
                keepSession(snap.uploadSessionUri)
                if (snap.totalByteCount > 0) {
                    val pct = (snap.bytesTransferred * 100 / snap.totalByteCount).toInt()
                    if (resuming && !loggedResume) {
                        loggedResume = true
                        Log.i(TAG, "resume from pct=$pct")
                    }
                    if (pct != lastPercent) {
                        lastPercent = pct
                        UploadQueue.setProgress(UploadProgress(item.id, item.title, pct))
                        UploadQueue.heartbeat(item.id, pct)
                        pushForeground(item.title, pct)
                    }
                }
            }
            // ⛔ `endTransfer()` من المستمعين وحدهم: العلم هو حارس `kickNow`،
            // وهو يعني «سكتت المهمّة فعلاً» لا «انسحب العامل» — وبين الأمرين
            // نافذة يبقى فيها النقل جارياً (`pause()` غير متزامن).
            task.addOnPausedListener(listenerExecutor) { snap ->
                keepSession(snap.uploadSessionUri)
                UploadQueue.endTransfer()
                Log.i(TAG, "pause() kept session=$session")
                if (cont.isActive) cont.resumeWithException(UploadPausedException())
            }
            task.addOnSuccessListener(listenerExecutor) {
                UploadQueue.endTransfer()
                cont.resume(Unit)
            }
            task.addOnFailureListener(listenerExecutor) {
                // الفشل قد يقع بين إنشاء الجلسة وأوّل نبضة — تُلتقط هنا فلا
                // يعود الرفع من الصفر لمجرّد أنّ النبضة الأولى لم تصل.
                runCatching { keepSession(task.snapshot.uploadSessionUri) }
                UploadQueue.endTransfer()
                cont.resumeWithException(it)
            }
            // ⛔ `pause()` لا `cancel()`: `cancel()` يرسل
            // `ResumableUploadCancelRequest` إلى الخادم فيمحو الجلسة، فيبقى
            // `sessionUri` على القرص عنوانَ جلسة مقتولة ويعود الرفع من الصفر
            // في كلّ انقطاع. هذا هو أصل «يبقى عالقاً ويوهمك أنّ الرفع جارٍ».
            // والجلسة تُلتقط قبل الإيقاف: `pause()` غير متزامن وقد يموت العامل
            // قبل أن يصل مستمع الإيقاف، فلا يبقى للجلسة كاتب آخر.
            cont.invokeOnCancellation {
                runCatching { keepSession(task.snapshot.uploadSessionUri) }
                runCatching { task.pause() }
            }
        }

        // ✅ وصلت البايتات كاملةً: يُسجَّل مسار التخزين **قبل** `downloadUrl`
        // — نداء شبكيّ مستقلّ خارج حماية الاستئناف كان فشله يعيد دورة رفع
        // كاملة، ويترك عند الإلغاء ملفّاً حتى 100MB يتيماً بلا وثيقة تشير إليه.
        UploadQueue.update(item.id) { it.copy(uploadedPath = storagePath) }
        // ⚠️ لا نمسح الربط هنا: كتلة `finally` في دورة العامل تمسحه بعد
        // إنشاء الوثيقة، وبقاء العنصر «نشطاً» حتى ذلك الحين هو ما يمنع
        // [UploadQueue.cancel] من حذف الصوت أثناء `addLesson` (درس حيّ
        // برابط ميت).
        return downloadUrlWithRetry(ref) to storagePath
    }

    /**
     * `downloadUrl` نداء رخيص لا يستحقّ إعادة دورة رفع كاملة إن تعثّر لحظةَ
     * اكتمال الرفع (وهي لحظة انقطاع شائعة) — ثلاث محاولات قصيرة تكفي.
     */
    private suspend fun downloadUrlWithRetry(ref: StorageReference): String {
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                return ref.downloadUrl.await().toString()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "downloadUrl retry $attempt: $e")
                if (attempt < 3) delay(1_500L * attempt)
            }
        }
        throw last ?: IllegalStateException("downloadUrl failed")
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

        /**
         * سقف فشل الشبكة المتتالي قبل الركن. ⛔ لا يجوز أن يبقى في `doWork`
         * مسار واحد بلا عدّاد: كان فرع الشبكة يعيد `retry` أبداً بلا ركن ولا
         * إشعار ولا سطر خطأ، فيعلق الدرس حرفيّاً إلى ما لا نهاية.
         */
        private const val MAX_NET_FAILURES = 20

        /**
         * سقف المحاولات المتتالية للتشغيلة نفسها (عدّاد WorkManager: يصفّره
         * نجاحٌ أو `REPLACE`). به تُحدّ المساراتُ التي لا عدّاد لها في العنصر:
         * انهيار `doWork`، وإيقاف النظام، وسباق الإيقاف/الاستئناف.
         */
        private const val MAX_RUN_ATTEMPTS = 12

        /** سقف مرّات إيقاف النظام للعنصر الواحد قبل ركنه بإشعار. */
        private const val MAX_INTERRUPTIONS = 15

        /** عمق سلسلة الأسباب المفحوصة في تصنيف الأعطال. */
        private const val CAUSE_DEPTH = 8

        /** سبب مقروء يُعرض للمشرف حين تختفي النسخة المحليّة للدرس. */
        private const val MISSING_FILE_ERROR = "ضاع الملفّ المحلّي"

        /**
         * خيط واحد لمستمعي مهمّة الرفع — انظر تعليق `addOnProgressListener`.
         * واحد لا مجمّع: النبضات تُحفظ بالترتيب، والكتابة نفسها متسلسلة.
         */
        private val listenerExecutor: Executor =
            Executors.newSingleThreadExecutor { r -> Thread(r, "minbar-upload-io") }

        const val WORK_NAME = "lesson_upload_queue"

        private fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<LessonUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                // خطّي لا أُسّي: التأخير الأُسّي (20ث × 2^ن) كان يبلغ سقف
                // WorkManager (خمس ساعات) بعد نحو عشر محاولات ويبقى هناك،
                // فيصير الرفع يحاول مرّة كلّ خمس ساعات. قيد «متّصل» هو
                // الحارس الحقيقيّ فلا حاجة لتأخير طويل أصلاً.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()

        /**
         * يوقظ الطابور للإضافة العاديّة: يبدأ فوراً إن كان هناك اتصال.
         *
         * APPEND_OR_REPLACE لا KEEP: مع KEEP كان درس يُضاف لحظة انتهاء العامل
         * لا يبدأ رفعه أبداً. ولا يقاطع رفعاً جارياً — وهذا بالضبط ما تحتاجه
         * إضافة خمسين ملفّاً من مجلّد دفعةً واحدة.
         */
        fun kick(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * ⚡ إيقاظ فوريّ لكلّ تدخّل بشريّ صريح ولعودة الشبكة.
         *
         * ⚠️ دلالة APPEND_OR_REPLACE أنّ الاستبدال لا يقع إلّا إن كانت السلسلة
         * القائمة CANCELLED أو FAILED؛ وسلسلتنا لا تفشل أبداً، فكلّ ضغطة
         * «استئناف» أو «إعادة المحاولة» كانت تُلحَق ابناً **يبقى BLOCKED** خلف
         * عقدة نائمة في backoff قد تنام خمس ساعات — تأكيد بصريّ كاذب ولا شيء
         * يقع. REPLACE يلغي العقدة العالقة ويصفّر `runAttemptCount` فيبدأ الآن.
         * ⚠️ الترتيب لا يتأثّر: الدور محفوظ في `seq` وفي `peek` لا في السلسلة.
         *
         * ⛔ الحارس في الدالّة نفسها لا في كلّ مُنادٍ: REPLACE يُنشئ WorkSpec
         * بمعرّف **جديد**، فحارس `Processor` لا يمنع تشغيل عامل ثانٍ بينما
         * الأوّل ينسحب — و`pause()` غير متزامن، فقد تبقى بايتات الأوّل تُنقل
         * حين يبدأ الثاني على **جلسة الاستئناف نفسها**: كاتبان بإزاحات
         * متضاربة. فوق رفع جارٍ نكتفي بالإلحاق فيبدأ بعد انتهائه.
         */
        fun kickNow(context: Context) {
            // ⛔ حارسان لا واحد: تدفّق `WorkInfo` متأخّر — قيمته `false` في
            // عمليّة جديدة قبل أوّل إصدار — فيمرّ REPLACE فوق رفع جارٍ فعلاً.
            // [UploadQueue.isTransferring] حقيقةُ العمليّة نفسها: يبقى قائماً
            // حتى يصل مستمع النجاح/الإيقاف/الفشل.
            if (UploadWorkWatcher.isRunning() || UploadQueue.isTransferring()) {
                kick(context)
                return
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request())
        }
    }
}
