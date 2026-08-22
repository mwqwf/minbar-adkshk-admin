package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * حالة عمل الطابور كما يراها WorkManager نفسه.
 *
 * [nextRunAtMs] = 0 يعني «لا موعد مجدول معروف».
 */
data class UploadWorkState(
    val state: WorkInfo.State? = null,
    val nextRunAtMs: Long = 0L,
    /**
     * وصلت أوّل قراءة فعليّة من WorkManager.
     *
     * ⚠️ بلاها تبدأ الحالة `null` قبل أن يُصدر التدفّق شيئاً، فتعرض الواجهة
     * «⚠️ متوقّف» لحظةً في كلّ فتح — كذبة قصيرة لكنّها مرئيّة.
     */
    val loaded: Boolean = false,
)

/**
 * 🔎 مصدر الحقيقة الوحيد لحالة جدولة الرفع.
 *
 * ⚠️ لم يكن في المشروع كلّه استدعاء واحد لقراءة `WorkInfo` — الإدراج فقط —
 * فكانت الواجهة عاجزة **بنيوياً** عن الصدق: تسقط إلى «بانتظار الدور» في
 * ثلاث حالات متناقضة تماماً (عمل يجري، وعمل نائم في backoff، ولا عمل مجدول
 * إطلاقاً). ⛔ لا يجوز أن يعود نصّ حالة لا يُشتقّ من هنا أو من نبضة تقدّم
 * عمرها أقلّ من 30 ثانية.
 */
object UploadWorkWatcher {
    private val _state = MutableStateFlow(UploadWorkState())
    val state: StateFlow<UploadWorkState> = _state

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        if (started) return
        started = true
        val manager = WorkManager.getInstance(context.applicationContext)
        scope.launch {
            // ⚠️ `runCatching` لمرّة واحدة كانت تجعل المراقب يموت صامتاً عند
            // أوّل استثناء من التدفّق و`started` يبقى `true` — فتتجمّد الحالة
            // إلى نهاية العمليّة وتعمى معها كلّ نصوص الواجهة وحارسُ الإيقاظ.
            while (isActive) {
                runCatching {
                    manager.getWorkInfosForUniqueWorkFlow(LessonUploadWorker.WORK_NAME)
                        .collect { infos ->
                            // العقدة الحيّة وحدها تمثّل الطابور؛ والحالات النهائيّة
                            // تُهمَل عمداً: «نجح العمل» لا يعني أنّ الطابور فرغ.
                            val live = infos.firstOrNull { !it.state.isFinished }
                            _state.value = UploadWorkState(
                                state = live?.state,
                                // ⚠️ WorkManager يعيد Long.MAX_VALUE حين لا
                                // يكون هناك موعد مجدول — لا «بعد ملايين السنين».
                                nextRunAtMs = live?.nextScheduleTimeMillis
                                    ?.takeIf { it != Long.MAX_VALUE } ?: 0L,
                                loaded = true,
                            )
                        }
                }.onFailure { Log.w("LessonUpload", "work watcher failed — إعادة تشغيل", it) }
                delay(5_000)
            }
        }
    }

    /** هل يجري الآن عمل رفع فعليّ؟ (يمنع إيقاظاً يقاطع رفعاً قائماً). */
    fun isRunning(): Boolean = _state.value.state == WorkInfo.State.RUNNING
}
