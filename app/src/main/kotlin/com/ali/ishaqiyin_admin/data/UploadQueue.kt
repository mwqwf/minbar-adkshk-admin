package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** وسم موحّد لكلّ سطور سجلّ الرفع — يُتابَع بـ`adb logcat -s LessonUpload:V`. */
private const val LOG_TAG = "LessonUpload"

/**
 * حالة العنصر **محفوظة على القرص**.
 *
 * ⚠️ كان `PendingUpload` بلا حقل حالة أصلاً، فبعد موت العمليّة أثناء رفع
 * عند 43% لا يبقى على القرص شاهد واحد بأنّ الدرس كان في الطريق: تسقط
 * الواجهة إلى «بانتظار الدور» بشريط عند صفر — أربع إشارات متّسقة تقول
 * «يعمل» ولا واحدة مشتقّة من عمل فعليّ. ⛔ لا يجوز أن يعود نصّ حالة لا
 * يُشتقّ من هنا أو من `WorkInfo` أو من نبضة تقدّم عمرها أقلّ من 30 ثانية.
 */
object UploadState {
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val WAITING = "waiting"
    const val INTERRUPTED = "interrupted"
    const val PARKED = "parked"
}

/**
 * درس بانتظار الرفع. الملفّ **منسوخ إلى تخزين التطبيق الخاصّ** لا مُشاراً
 * إليه بـcontent Uri: صلاحية الـUri القادم من منتقي النظام تنتهي بإعادة
 * تشغيل العمليّة، فلولا النسخ لضاع الدرس عند استئناف الرفع بعد ساعات.
 */
data class PendingUpload(
    val id: String,
    /** ترتيب صارم: يُرفع الأصغر أوّلاً مهما كانت إعادات المحاولة. */
    val seq: Long,
    val title: String,
    val categoryId: String,
    val subcategoryId: String,
    /** اسما القسمين وقت الإضافة — يُرسَلان مع الدرس فيبقيان في وثيقته. */
    val categoryName: String = "",
    val subcategoryName: String = "",
    val sectionLabel: String,
    val featured: Boolean,
    /** نهاية مدّة التمييز إن ميّزه المشرف عند الإضافة (null = دائم). */
    val featuredUntilMs: Long? = null,
    val addedBy: String,
    val localPath: String,
    val fileName: String,
    val sizeBytes: Long,
    /**
     * لحظة ضغط المشرف على «رفع» — تُرسَل إلى الخادم كـcreatedAt فيبقى
     * ترتيب الدروس في التطبيق العام مطابقاً لترتيب الإضافة لا لترتيب
     * اكتمال الرفع.
     */
    val queuedAtMs: Long,
    /** جلسة الرفع القابلة للاستئناف من Firebase — تُبقي ما رُفع فعلاً. */
    val sessionUri: String? = null,
    /**
     * لحظة حفظ [sessionUri]. ⚠️ جلسات GCS القابلة للاستئناف تنتهي صلاحيّتها
     * (نحو أسبوع)، وكانت الجلسة تُحفظ نصّاً مجرّداً بلا ختم زمنيّ: عنصر بقي
     * في الطابور عشرة أيّام كان يدور على جلسة ميّتة يقيناً بلا مخرج.
     */
    val sessionSavedAtMs: Long = 0L,
    val attempts: Int = 0,
    /**
     * عدد مرّات فشل الشبكة المتتالية. ⛔ كان مسار «فشل الشبكة» يعيد
     * `Result.retry()` بلا أيّ عدّاد ولا ركن ولا إشعار — مسار بلا نهاية
     * يبقي الدرس عالقاً إلى الأبد والواجهة تقول «بانتظار الإنترنت».
     */
    val netFailures: Int = 0,
    val lastError: String? = null,
    /** حالة الرفع المحفوظة — انظر [UploadState]. */
    val state: String = UploadState.QUEUED,
    /** آخر نبضة تقدّم محفوظة (مقنَّنة) — بها يُكشف الانقطاع بعد موت العمليّة. */
    val lastHeartbeatMs: Long = 0L,
    /** آخر نسبة محفوظة — تُعرض في «انقطع أثناء الرفع عند س%». */
    val lastPercent: Int = 0,
    /** عدد المرّات التي أوقف فيها النظام الرفع (قيد شبكة/مهلة/بطارية). */
    val interruptions: Int = 0,
    /** سبب آخر إيقاف بالعربية — من `getStopReason()`. */
    val stoppedReason: String? = null,
    /** آخر لحظة عمل فيها الرفع فعلاً — بها تقول الواجهة «لم يعمل منذ س». */
    val lastRunAtMs: Long = 0L,
    /**
     * بصمة العمليّة التي كتبت الحالة.
     *
     * ⚠️ كانت مهلة سماح (90 ثانية) هي وحدها ما يكشف الانقطاع، فمن أوقف
     * التطبيق قسراً ثمّ أعاد فتحه خلال ثوانٍ — وهو نصّ اختبار القبول (ب) —
     * يجد `state = uploading` كما هو ولا أثر واحد للانقطاع. البصمة تتغيّر مع
     * كلّ عمليّة، فكلّ «يُرفع» كتبته عمليّة ماتت يُكشف **فوراً** بلا انتظار.
     */
    val runToken: String = "",
    /**
     * رُكن بعد فشل دائم: يبقى معروضاً للمشرف ليقرّر، لكنّه **يخرج من دور
     * الرفع** — بلا هذا كان العامل يعيد رفعه بلا توقّف حين لا يبقى غيره.
     */
    val parked: Boolean = false,
    /**
     * مسار الملفّ الصوتي في التخزين بعد نجاح رفعه (قبل إنشاء الوثيقة).
     * بلا هذا كان إلغاء عنصر **مركون نجح رفع صوته** يترك ملفاً حتى 100MB
     * يتيماً في التخزين: لا وثيقة تشير إليه ولا واجهة ولا دالّة تمسحه.
     */
    val uploadedPath: String? = null,
    // «النص المشروح» الاختياري المرافق: يُنشر بعد إنشاء الدرس مباشرة.
    val transcriptText: String = "",
    val transcriptBookTitle: String = "",
    val transcriptSourceRef: String = "",
    /** نسخ محليّة لصور صفحات الكتاب (في تخزين التطبيق مع ملفّ الصوت). */
    val transcriptImagePaths: List<String> = emptyList(),
) {
    val hasTranscript: Boolean
        get() = transcriptText.trim().length >= 10 || transcriptImagePaths.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("seq", seq)
        put("title", title)
        put("categoryId", categoryId)
        put("subcategoryId", subcategoryId)
        put("categoryName", categoryName)
        put("subcategoryName", subcategoryName)
        put("sectionLabel", sectionLabel)
        put("featured", featured)
        put("featuredUntilMs", featuredUntilMs ?: JSONObject.NULL)
        put("addedBy", addedBy)
        put("localPath", localPath)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("queuedAtMs", queuedAtMs)
        put("sessionUri", sessionUri ?: JSONObject.NULL)
        put("sessionSavedAtMs", sessionSavedAtMs)
        put("attempts", attempts)
        put("netFailures", netFailures)
        put("lastError", lastError ?: JSONObject.NULL)
        put("state", state)
        put("lastHeartbeatMs", lastHeartbeatMs)
        put("lastPercent", lastPercent)
        put("interruptions", interruptions)
        put("stoppedReason", stoppedReason ?: JSONObject.NULL)
        put("lastRunAtMs", lastRunAtMs)
        put("runToken", runToken)
        put("parked", parked)
        put("uploadedPath", uploadedPath ?: JSONObject.NULL)
        put("transcriptText", transcriptText)
        put("transcriptBookTitle", transcriptBookTitle)
        put("transcriptSourceRef", transcriptSourceRef)
        put("transcriptImagePaths", org.json.JSONArray(transcriptImagePaths))
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingUpload(
            id = o.optString("id"),
            seq = o.optLong("seq"),
            title = o.optString("title"),
            categoryId = o.optString("categoryId"),
            subcategoryId = o.optString("subcategoryId"),
            categoryName = o.optString("categoryName"),
            subcategoryName = o.optString("subcategoryName"),
            sectionLabel = o.optString("sectionLabel"),
            featured = o.optBoolean("featured"),
            featuredUntilMs = o.optLong("featuredUntilMs").takeIf { it > 0L },
            addedBy = o.optString("addedBy"),
            localPath = o.optString("localPath"),
            fileName = o.optString("fileName"),
            sizeBytes = o.optLong("sizeBytes"),
            queuedAtMs = o.optLong("queuedAtMs"),
            sessionUri = o.optString("sessionUri").takeIf { it.isNotEmpty() && it != "null" },
            sessionSavedAtMs = o.optLong("sessionSavedAtMs"),
            attempts = o.optInt("attempts"),
            netFailures = o.optInt("netFailures"),
            lastError = o.optString("lastError").takeIf { it.isNotEmpty() && it != "null" },
            state = o.optString("state").takeIf { it.isNotEmpty() && it != "null" }
                ?: UploadState.QUEUED,
            lastHeartbeatMs = o.optLong("lastHeartbeatMs"),
            lastPercent = o.optInt("lastPercent"),
            interruptions = o.optInt("interruptions"),
            stoppedReason = o.optString("stoppedReason").takeIf { it.isNotEmpty() && it != "null" },
            lastRunAtMs = o.optLong("lastRunAtMs"),
            runToken = o.optString("runToken"),
            parked = o.optBoolean("parked"),
            uploadedPath = o.optString("uploadedPath").takeIf { it.isNotEmpty() && it != "null" },
            transcriptText = o.optString("transcriptText"),
            transcriptBookTitle = o.optString("transcriptBookTitle"),
            transcriptSourceRef = o.optString("transcriptSourceRef"),
            transcriptImagePaths = o.optJSONArray("transcriptImagePaths")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
        )
    }
}

/**
 * عنصر يحتاج انتباه المشرف: مركون، أو فشل بسبب غير شبكيّ.
 *
 * ⚠️ «بانتظار الشبكة» مستثنى عمداً رغم أنّ نصّه محفوظ في [PendingUpload.lastError]:
 * الأثر محفوظ ليبقى للانقطاع علامة مرئيّة في البطاقة، لكنّه ليس فشلاً دائماً —
 * وعدّه في «مهام اليوم» كان سيصرخ «درس تعذّر رفعه» عند كلّ انقطاع دقيقتين.
 *
 * ⚠️ و«انقطع أثناء الرفع» مستثنى لعلّته نفسها: الانقطاع يقع في **كلّ** إقلاع
 * أو إيقاف قسريّ ويُستأنف وحده، فكان يُنذَر عنه مرّتين معاً — في البانر بنصّه
 * الخاصّ، وتحته صفّ «درس متعثّر» — ويُعدّ في «مهام اليوم» مهمّةً عاجلة، لحدث
 * لا يحتاج قراراً من أحد.
 */
val PendingUpload.needsAttention: Boolean
    get() = parked ||
        (
            lastError != null &&
                state != UploadState.WAITING &&
                state != UploadState.INTERRUPTED
            )

/** تقدّم الرفع الجاري الآن (للمؤشّر الحيّ). */
data class UploadProgress(
    val id: String,
    val title: String,
    val percent: Int,
    /** أوقفه المشرف مؤقّتاً — يُستأنف من البايت نفسه بجلسة الرفع المحفوظة. */
    val paused: Boolean = false,
    /**
     * ختم النبضة. ⚠️ بلاه كانت النسبة تتجمّد عشر دقائق أثناء إعادة المحاولة
     * الداخليّة لـFirebase (لا تُطلق نبضات تقدّم) والواجهة تقول «جارٍ الرفع…
     * 43%» بلا أيّ نقل يجري. ⛔ لا يُعرض «جارٍ الرفع» لنبضة أقدم من 30 ثانية.
     */
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * آخر درس دخل الطابور للتوّ — تأكيد فوريّ **لكلّ إضافة** لا للأولى فقط.
 * `atMs` يتغيّر مع كلّ إضافة حتى لو تطابق العنوان، فتُعيد الواجهة إظهار
 * التأكيد بدل أن تراه بلا تغيير فتظنّ أنّ شيئاً لم يحدث.
 */
data class JustEnqueued(
    val id: String,
    val title: String,
    val fileName: String,
    /** موقعه في دور الرفع (1 = التالي). */
    val position: Int,
    val total: Int,
    val atMs: Long,
    /**
     * عدد ما أُدرج في هذه الدفعة (1 = إضافة مفردة). رفع مجلّد كامل يُدرج
     * عشرين درساً في حلقة واحدة، وإعلان كلّ واحد على حدة كان يُومض التأكيد
     * عشرين مرّة وينتهي عند آخر ملفّ وحده — فلا يرى المشرف أنّ الدفعة كلّها
     * دخلت. انظر [UploadQueue.markBatchEnqueued].
     */
    val batchCount: Int = 1,
)

/**
 * تقدّم إدراج دفعة (رفع مجلّد كامل) — حالة **مشتركة خارج التركيب**، فتصمد
 * لإعادة إنشاء النشاط: عدّادٌ بـ`remember` كان يختفي مع أوّل تدوير للشاشة
 * والدفعة ماضية في الخلفية، فيبقى المشرف بلا مؤشّر واحد.
 */
data class BatchProgress(val prepared: Int, val total: Int)

/**
 * 📤 طابور رفع الدروس — يعمل دون اتصال ويستأنف تلقائياً.
 *
 * الضمانات:
 *  • **لا انتظار**: ضغط «رفع» يُدرج الدرس ويُفرِغ النموذج فوراً، فيستطيع
 *    المشرف تعبئة درس آخر بينما يُرفع الأوّل.
 *  • **دون تغطية**: الملفّ يُنسخ محليّاً والبيانات تُحفظ، فيبدأ الرفع وحده
 *    فور عودة الإنترنت بلا أيّ تدخّل.
 *  • **استئناف لا إعادة**: تُحفظ جلسة الرفع، فالانقطاع يُكمل من حيث توقّف
 *    ولا يعيد من الصفر.
 *  • **ترتيب مضمون**: رفع تسلسليّ بالدور، وزمن الإضافة يُختم لحظة الإدراج،
 *    فأوّل درس أُضيف هو أوّل درس يظهر في التطبيق العام.
 */
object UploadQueue {
    private const val PREFS_KEY = "lesson_upload_queue_v1"

    /**
     * مدوّنة الطابور في ملفّ تفضيلات مستقلّ: مدوّنتها تضمّ نصوص «النص
     * المشروح» كاملةً، وبقاؤها في ملفّ التفضيلات العامّ كان يعيد كتابتها
     * كلّها مع كلّ تبديل تفضيل صغير (كتم الدردشة، سرعة الصوت).
     */
    private const val QUEUE_FILE = "minbar_upload_queue"
    private const val SEQ_KEY = "lesson_upload_seq_v1"
    private const val PAUSED_KEY = "lesson_upload_paused_v1"
    private const val DIR = "upload_queue"

    /** أدنى فاصل بين نبضتين محفوظتين على القرص. */
    private const val HEARTBEAT_WRITE_MS = 10_000L

    /**
     * أقصى عمر لجلسة رفع محفوظة: جلسات GCS القابلة للاستئناف تنتهي صلاحيّتها،
     * فتُهمَل **قبل** المحاولة لا بعد فشلها.
     */
    const val SESSION_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * بصمة هذه العمليّة — تتغيّر مع كلّ إقلاع للتطبيق (العامل والواجهة في
     * العمليّة نفسها). بها يُعرف أنّ «يُرفع» المحفوظ كتبته عمليّة ماتت.
     */
    private val PROCESS_TOKEN =
        "${android.os.Process.myPid()}_${System.currentTimeMillis()}"

    private val _items = MutableStateFlow<List<PendingUpload>>(emptyList())
    val items: StateFlow<List<PendingUpload>> = _items

    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress

    /** تأكيد الإضافة الأخيرة — تعرضه الواجهة لحظات ثمّ تمسحه. */
    private val _justEnqueued = MutableStateFlow<JustEnqueued?>(null)
    val justEnqueued: StateFlow<JustEnqueued?> = _justEnqueued

    private val lock = Any()

    /**
     * نطاق خلفيّ صغير للتنظيف الذي لا يصحّ حجز خيط الواجهة به (حذف ملفّ
     * صوتيّ يتيم من التخزين بعد إلغاء عنصر نجح رفعه ولم تُنشأ وثيقته).
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ⏸ إيقاف مؤقّت للطابور كلّه — **لا إلغاء**: النقل الجاري يُوقف، لكنّ
     * جلسة الرفع والملفّ المحلّي يبقيان، فالاستئناف يُكمل من البايت الذي
     * وقف عنده لا من الصفر. محفوظ على القرص كي يصمد لإعادة تشغيل العمليّة:
     * بلا ذلك كان WorkManager يوقظ العامل بعد قتل التطبيق فيستأنف رفعاً
     * أوقفه المشرف عمداً (وهو غالباً أوقفه لأنّه على بيانات الجوّال).
     */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    /**
     * منع النظام بدء الخدمة الأماميّة للرفع (قيود Android 12+ أو حجب
     * الإشعارات). ⚠️ كان فشل `setForeground` يُبتلع في `runCatching` بلا
     * سطر واحد، فيعود الرفع مهمّة خلفيّة محكومة بعشر دقائق: ملفّ 100MB لا
     * يكتمل أبداً، وكلّ دورة تُقتل في منتصفها بلا إشارة للمشرف.
     */
    private val _backgroundBlocked = MutableStateFlow(false)
    val backgroundBlocked: StateFlow<Boolean> = _backgroundBlocked

    fun setBackgroundBlocked(blocked: Boolean) {
        _backgroundBlocked.value = blocked
    }

    fun init(context: Context) {
        AppPrefs.init(context)
        migrateQueueFile()
        // التحميل متزامن عمداً: `enqueue` قبل اكتمال قراءة القرص كان سيكتب
        // فوق طابور محفوظ فتضيع دروس معلَّقة.
        _items.value = load()
        _paused.value = prefs().getBoolean(PAUSED_KEY, false)
        recoverInterrupted()
        sweepOrphans()
    }

    /**
     * 🛟 استرداد بعد الانهيار: كلّ عنصر بقي بحالة «يُرفع» بلا نبضة حديثة
     * كانت عمليّته قد ماتت (إطفاء الهاتف، force-stop، قتل تحت ضغط الذاكرة).
     *
     * ⚠️ بلا هذه الخطوة لم يكن على القرص شاهد واحد على الانقطاع، فيُعرض
     * العنصر «بانتظار الدور» — وهو النصّ نفسه بحرفه في ثلاث حالات متناقضة.
     * ⛔ لا تُحذف: هي وحدها ما يجعل معيار القبول (ب) قابلاً للتحقّق.
     */
    private fun recoverInterrupted() {
        // ⛔ الكشف ببصمة العمليّة لا بمهلة سماح: المهلة كانت تُسقط سيناريو
        // القبول (ب) نفسه — إيقاف قسريّ ثمّ فتح فوريّ خلال ثوانٍ.
        fun died(item: PendingUpload) =
            item.state == UploadState.UPLOADING && item.runToken != PROCESS_TOKEN
        if (_items.value.none(::died)) return
        synchronized(lock) {
            persist(
                load().map {
                    if (died(it)) {
                        Log.w(LOG_TAG, "recovered interrupted item=${it.id} pct=${it.lastPercent}")
                        it.copy(state = UploadState.INTERRUPTED)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /**
     * 🧹 كنس الملفّات اليتيمة في مجلّد الطابور: انهيار العمليّة بين نسخ
     * الملفّ والحفظ (أو فشل حذف مبتلَع في `runCatching`) كان يترك نسخاً
     * حتى 100MB لا يشير إليها أيّ عنصر ولا تُحذف أبداً. يُكنس ما لا يشير
     * إليه عنصر معلَّق **وعمره يتجاوز يوماً** — الهامش يحمي نسخة يجري
     * نسخها الآن في `enqueue` قبل أن تُحفظ في القائمة.
     */
    private fun sweepOrphans() {
        cleanupScope.launch {
            runCatching {
                val referenced = _items.value
                    .flatMap { it.transcriptImagePaths + it.localPath }
                    .toSet()
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                queueDir().listFiles()?.forEach { f ->
                    if (f.isFile && f.absolutePath !in referenced && f.lastModified() < cutoff) {
                        runCatching { f.delete() }
                    }
                }
            }
        }
    }

    fun isPaused(): Boolean = _paused.value

    /**
     * يوقف النقل الجاري فوراً — والعامل يخرج عند الدورة التالية.
     *
     * ⛔ `pause()` لا `cancel()`: فككتُ `UploadTask` من الـSDK فوجدتُ أنّ
     * `cancel()` يرسل `ResumableUploadCancelRequest` إلى الخادم فيمحو الجلسة
     * القابلة للاستئناف — فيبقى `sessionUri` محفوظاً على القرص وهو عنوان
     * جلسة مقتولة، ويعود الرفع من البايت صفر رغم وعد الواجهة «يُستأنف من
     * حيث توقّف». لا يجوز أن يعود `cancel()` إلى أيّ مسار إيقاف غير مقصود.
     */
    fun pause() {
        if (_paused.value) return
        _paused.value = true
        // ⛔ `commit()` لا `apply()`: الراية معلَن غرضها الصمود لقتل العملية،
        // و`apply()` كتابة مؤجَّلة — قتلٌ مباشر بعد ضغط «إيقاف مؤقّت» كان
        // يفقدها فيستأنف الرفع على بيانات الجوّال. تماماً كما فعلت [saveSession].
        prefs().edit().putBoolean(PAUSED_KEY, true).commit()
        activeTask?.let { runCatching { it.pause() } }
    }

    /** يرفع الإيقاف — على المُنادي أن يوقظ العامل بعده. */
    fun resume() {
        if (!_paused.value) return
        _paused.value = false
        // رفع الإيقاف يُثبَّت متزامناً أيضاً كي لا تبقى راية إيقافٍ مرفوعة
        // على القرص بعد قتل العملية فيمتنع الرفع بلا سبب ظاهر.
        prefs().edit().putBoolean(PAUSED_KEY, false).commit()
        // ⚠️ كانت تُبقي لقطة التقدّم وتقلب راية الإيقاف وحدها، فيقفز الشريط
        // فوراً إلى «جارٍ الرفع… 43%» ويتجمّد هناك أبداً — قبل أن يُنشئ
        // WorkManager شيئاً. ⛔ لا تُعرض نسبة إلّا من نبضة حقيقيّة.
        _progress.value = null
    }

    /**
     * عودة الشبكة تمحو أثر الانتظار فوراً.
     *
     * ⚠️ كان علم «بانتظار الإنترنت» لا يمحوه إلا بدء عامل فعليّ، فيبقى
     * ساعات بعد عودة الواي‑فاي: رسالة تحوّل اللوم إلى الشبكة فيمتنع المشرف
     * عن التدخّل بينما لا شيء يجري.
     */
    fun clearNetworkWait() {
        _progress.value = null
        // ⚠️ تُنادى من خيط ConnectivityManager المشترك، وجسدها قراءة الطابور
        // كاملاً وتحليله وإعادة كتابته — لا يجوز أن يقع ذلك خارج IO.
        cleanupScope.launch {
            synchronized(lock) {
                val list = load()
                if (list.any { it.state == UploadState.WAITING }) {
                    persist(
                        list.map {
                            if (it.state == UploadState.WAITING) {
                                // العدّاد **متتالٍ**: عودة الشبكة تنهي السلسلة،
                                // وبلا تصفيره تتراكم انقطاعات شهور متفرّقة حتى
                                // تبلغ السقف فيُركن درس لا عيب فيه.
                                it.copy(
                                    state = UploadState.QUEUED,
                                    lastError = null,
                                    netFailures = 0,
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
            }
        }
    }

    private fun prefs() = AppPrefs.context
        .getSharedPreferences("minbar_admin_prefs", Context.MODE_PRIVATE)

    private fun queuePrefs() = AppPrefs.context
        .getSharedPreferences(QUEUE_FILE, Context.MODE_PRIVATE)

    /** ترحيل لمرّة واحدة: طابور محفوظ بنسخة سابقة داخل التفضيلات العامّة. */
    private fun migrateQueueFile() {
        runCatching {
            val old = prefs().getString(PREFS_KEY, null)
            if (old != null) {
                if (queuePrefs().getString(PREFS_KEY, null) == null) {
                    queuePrefs().edit().putString(PREFS_KEY, old).apply()
                }
                prefs().edit().remove(PREFS_KEY).apply()
            }
        }
    }

    private fun load(): List<PendingUpload> = runCatching {
        val raw = queuePrefs().getString(PREFS_KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length())
            .map { PendingUpload.fromJson(arr.getJSONObject(it)) }
            .sortedBy { it.seq }
    }.getOrDefault(emptyList())

    private fun persist(list: List<PendingUpload>, sync: Boolean = false) {
        val arr = JSONArray()
        val ordered = list.sortedBy { it.seq }
        ordered.forEach { arr.put(it.toJson()) }
        val editor = queuePrefs().edit().putString(PREFS_KEY, arr.toString())
        // ⚠️ `apply()` كتابة غير متزامنة: قتل مفاجئ للعمليّة بعدها مباشرة كان
        // يفقد عنوان الجلسة — وهو القيمة الوحيدة التي يقتل فقدانُها الاستئناف.
        if (sync) editor.commit() else editor.apply()
        _items.value = ordered
    }

    private fun nextSeq(): Long = synchronized(lock) {
        val next = prefs().getLong(SEQ_KEY, 0L) + 1
        prefs().edit().putLong(SEQ_KEY, next).apply()
        next
    }

    /**
     * موقع العنصر في دور الرفع (1-based) من الحالة الحيّة في الذاكرة —
     * بلا قراءة قرص، فيصلح للاستدعاء من خيط الواجهة. يعيد 0 إن لم يعد
     * موجوداً (رُفع أو أُلغي).
     */
    fun positionOf(id: String): Int =
        _items.value.indexOfFirst { it.id == id }.let { if (it < 0) 0 else it + 1 }

    /** عدد ما في الطابور الآن من الحالة الحيّة (بلا قراءة قرص). */
    fun liveCount(): Int = _items.value.size

    /** يُعلن دخول عنصر جديد فوراً — بعد الحفظ كي يكون الترتيب نهائياً. */
    private fun markJustEnqueued(item: PendingUpload) {
        val all = _items.value
        val index = all.indexOfFirst { it.id == item.id }
        _justEnqueued.value = JustEnqueued(
            id = item.id,
            title = item.title,
            fileName = item.fileName,
            position = if (index < 0) all.size else index + 1,
            total = all.size,
            atMs = System.currentTimeMillis(),
        )
    }

    /**
     * إعلان **واحد** لدفعة كاملة (رفع مجلّد): يُنادى بعد آخر إدراج وقبل
     * إيقاظ العامل، فيرى المشرف «أُضيف ن درساً» مرّة واحدة بدل ن ومضات
     * متتابعة ينتهي أثرها عند آخر ملفّ وحده.
     */
    fun markBatchEnqueued(batch: List<PendingUpload>) {
        val first = batch.firstOrNull() ?: return
        if (batch.size == 1) {
            markJustEnqueued(first)
            return
        }
        val all = _items.value
        val index = all.indexOfFirst { it.id == first.id }
        _justEnqueued.value = JustEnqueued(
            id = first.id,
            title = first.title,
            fileName = first.fileName,
            position = if (index < 0) 1 else index + 1,
            total = all.size,
            atMs = System.currentTimeMillis(),
            batchCount = batch.size,
        )
    }

    /** تُنادى من الواجهة بعد عرض التأكيد لحظات. */
    fun clearJustEnqueued(id: String) {
        if (_justEnqueued.value?.id == id) _justEnqueued.value = null
    }

    /**
     * 🛡️ «دفعة قيد الإدراج الآن» — علم **خارج التركيب** يصمد لإعادة إنشاء
     * النشاط.
     *
     * ⚠️ حارسٌ داخل الشاشة (`remember`) لا يكفي: تدوير الشاشة أثناء نسخ
     * خمسين ملفّاً يُعيد بناءها بحارس صفريّ بينما الحلقة الأولى ماضية في
     * `NonCancellable`، فضغطة ثانية كانت تُشغّل حلقة ثانية بلقطتَي مقارنة
     * متداخلتين ⇒ دروس مكرَّرة و`seq` متشابك ⇒ ينكسر ضمان «الترتيب = ترتيب
     * الإضافة».
     */
    private val batchRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress

    /** يحجز الإدراج الجماعيّ لمُنادٍ واحد؛ `false` تعني أنّ دفعة تعمل الآن. */
    fun beginBatch(total: Int): Boolean {
        if (!batchRunning.compareAndSet(false, true)) return false
        _batchProgress.value = BatchProgress(prepared = 0, total = total)
        return true
    }

    fun updateBatchProgress(prepared: Int) {
        val current = _batchProgress.value ?: return
        _batchProgress.value = current.copy(prepared = prepared)
    }

    /** ⛔ يُنادى داخل `NonCancellable`: خارجه يبقى الحجز أبداً بعد المغادرة. */
    fun endBatch() {
        _batchProgress.value = null
        batchRunning.set(false)
    }

    /**
     * كتابة **واحدة** لدفعة كاملة (تُنادى بعد إدراج كلّه بـ`persistNow = false`).
     *
     * ⚠️ كلّ [enqueue] كان ينتهي بـ`persist(load() + item)`: تحليل المدوّنة
     * كاملةً وتسلسلها وكتابتها ن مرّة — والمدوّنة تضمّ نصوص «النص المشروح».
     * خمسون ملفّاً = خمسون دورة على مدوّنة متنامية وخمسون إصداراً للتدفّق.
     */
    fun persistBatch(batch: List<PendingUpload>) {
        if (batch.isEmpty()) return
        synchronized(lock) { persist(load() + batch) }
    }

    /**
     * معرّف فريد. ⚠️ العدّاد ليس زينة: كان المعرّف `الوقت + عشوائيّ من
     * 10000`، وحلقة إدراج عشرين ملفّاً صغيراً تقع كلّها في الملّي ثانية
     * نفسها — فاحتمال تصادم معرّفين ~2%، وثمنه أنّ `remove` يحذف درسين
     * و`update` يكتب على الاثنين.
     */
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)

    private fun newId(): String =
        "up_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}_${(0..9999).random()}"

    /** مجلّد النسخ الدائم (files لا cache — النظام لا يمسحه تحت الضغط). */
    private fun queueDir(): File =
        File(AppPrefs.context.filesDir, DIR).apply { mkdirs() }

    /**
     * ينسخ الملفّ المختار إلى تخزين التطبيق ثم يُدرجه في الطابور.
     * يعيد العنصر المُدرَج، أو يرمي إن تعذّرت قراءة الملفّ.
     */
    /** ينسخ صور «النص المشروح» المرافقة إلى تخزين التطبيق (تصمد كالصوت). */
    private fun Context.copyTranscriptImages(id: String, uris: List<Uri>): List<String> =
        uris.take(4).mapIndexedNotNull { index, uri ->
            runCatching {
                val dest = File(queueDir(), "${id}_transcript_$index.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: error("unreadable")
                dest.absolutePath
            }.getOrNull()
        }

    suspend fun enqueue(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        title: String,
        categoryId: String,
        subcategoryId: String,
        sectionLabel: String,
        featured: Boolean,
        featuredUntilMs: Long?,
        addedBy: String,
        categoryName: String = "",
        subcategoryName: String = "",
        transcriptText: String = "",
        transcriptBookTitle: String = "",
        transcriptSourceRef: String = "",
        transcriptImages: List<Uri> = emptyList(),
        /**
         * `false` في الإدراج الجماعيّ وحده: الدفعة تُعلَن مرّة واحدة في
         * نهايتها بـ[markBatchEnqueued].
         */
        announce: Boolean = true,
        /**
         * `false` في الإدراج الجماعيّ وحده: العنصر يُنسخ ويُبنى بلا كتابة
         * المدوّنة، والمُنادي يكتبها **مرّة واحدة** بـ[persistBatch] قبل
         * [markBatchEnqueued] — انظر علّة O(ن²) هناك.
         */
        persistNow: Boolean = true,
    ): PendingUpload = withContext(Dispatchers.IO) {
        val id = newId()
        val safeName = fileName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(120)
        val dest = File(queueDir(), "${id}_$safeName")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: error("تعذّرت قراءة الملفّ المحدَّد.")

        val item = PendingUpload(
            id = id,
            seq = nextSeq(),
            title = title.trim(),
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            categoryName = categoryName.trim(),
            subcategoryName = subcategoryName.trim(),
            sectionLabel = sectionLabel,
            featured = featured,
            featuredUntilMs = featuredUntilMs,
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
            transcriptText = transcriptText.trim(),
            transcriptBookTitle = transcriptBookTitle.trim(),
            transcriptSourceRef = transcriptSourceRef.trim(),
            transcriptImagePaths = context.copyTranscriptImages(id, transcriptImages),
        )
        if (persistNow) synchronized(lock) { persist(load() + item) }
        // بعد الحفظ: الترتيب صار نهائياً فيصحّ إعلان الموقع «س من ص».
        if (announce) markJustEnqueued(item)
        item
    }

    /**
     * يُدرج ملفاً مدموجاً جاهزاً. `suspend` على IO: نسخ ملفّ مدموج قد يبلغ
     * مئات الميغابايتات كان يجمّد خيط الواجهة لحظةَ الوعد بأنّه «لا يحجز».
     */
    suspend fun enqueueLocalFile(
        file: File,
        fileName: String,
        title: String,
        categoryId: String,
        subcategoryId: String,
        sectionLabel: String,
        featured: Boolean,
        featuredUntilMs: Long?,
        addedBy: String,
        context: Context? = null,
        categoryName: String = "",
        subcategoryName: String = "",
        transcriptText: String = "",
        transcriptBookTitle: String = "",
        transcriptSourceRef: String = "",
        transcriptImages: List<Uri> = emptyList(),
    ): PendingUpload = withContext(Dispatchers.IO) {
        val id = newId()
        val dest = File(queueDir(), "${id}_${file.name}")
        file.copyTo(dest, overwrite = true)
        runCatching { file.delete() }
        val item = PendingUpload(
            id = id,
            seq = nextSeq(),
            title = title.trim(),
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            categoryName = categoryName.trim(),
            subcategoryName = subcategoryName.trim(),
            sectionLabel = sectionLabel,
            featured = featured,
            featuredUntilMs = featuredUntilMs,
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
            transcriptText = transcriptText.trim(),
            transcriptBookTitle = transcriptBookTitle.trim(),
            transcriptSourceRef = transcriptSourceRef.trim(),
            transcriptImagePaths = context?.copyTranscriptImages(id, transcriptImages)
                .orEmpty(),
        )
        synchronized(lock) { persist(load() + item) }
        // بعد الحفظ: الترتيب صار نهائياً فيصحّ إعلان الموقع «س من ص».
        markJustEnqueued(item)
        item
    }

    /**
     * العنصر التالي للرفع: أقدم عنصر **غير مركون** — أساس ضمان الترتيب،
     * وهو وحده ما يحفظ الدور (لا سلسلة WorkManager). ⛔ عليه تُبنى ميزة رفع
     * المجلّد لاحقاً: ن نداءات `enqueueLocalFile` ثمّ إيقاظ واحد، بلا لمس
     * الجدولة وبلا «عامل لكلّ درس» الذي يكسر الترتيب.
     *
     * [skip] = ما فشل في هذه التشغيلة بعينها؛ به تُدوّر الحلقة إلى ما بعده
     * بدل أن ينسحب العامل. ⚠️ درس واحد ملفّه تالف كان يحبس عشرين درساً خلفه
     * إلى الأبد وكلّها تُعرض «بانتظار الدور».
     */
    fun peek(skip: Set<String> = emptySet()): PendingUpload? = synchronized(lock) {
        load().filterNot { it.parked || it.id in skip }.minByOrNull { it.seq }
    }

    fun byId(id: String): PendingUpload? = synchronized(lock) {
        load().firstOrNull { it.id == id }
    }

    /**
     * تحديث بالدمج على أحدث نسخة مخزَّنة، لا بالكتابة فوقها.
     * ⚠️ الكتابة بلقطة قديمة كانت تمحو `sessionUri` الذي حفظه مستمع
     * التقدّم أثناء الرفع، فيضيع الاستئناف ويعود الرفع من الصفر.
     */
    fun update(id: String, transform: (PendingUpload) -> PendingUpload) = synchronized(lock) {
        persist(load().map { if (it.id == id) transform(it) else it })
    }

    /**
     * حفظ جلسة الرفع **بكتابة متزامنة** — هي وحدها ما يجعل الاستئناف ممكناً،
     * فلا تُترك لطابور `apply()` المؤجَّل. تُنادى مرّة واحدة لكلّ عنصر.
     */
    fun saveSession(id: String, uri: String?) = synchronized(lock) {
        val now = if (uri == null) 0L else System.currentTimeMillis()
        persist(
            load().map {
                if (it.id == id) it.copy(sessionUri = uri, sessionSavedAtMs = now) else it
            },
            sync = true,
        )
    }

    // تقنين النبضة المحفوظة: الكتابة مع كلّ نسبة كانت ستعيد كتابة مدوّنة
    // الطابور (بنصوص «النص المشروح» كاملةً) مئة مرّة للملفّ الواحد.
    // ⚠️ @Volatile: يُقرآن ويُكتبان من خيط مستمعات الرفع ومن خيوط أخرى، وبلا
    // حاجز ذاكرة قد يرى خيطٌ قيمةً بائتة فتنطلق نبضة قرصيّة في كلّ تقدّم
    // (أو تُحبس كلّها) — وهي العلّة نفسها التي عُلِّم بها `lastPercent`.
    @Volatile private var beatAtMs = 0L
    @Volatile private var beatPercent = -1

    /** بداية نقل عنصر: تُثبَّت الحالة «يُرفع» على القرص قبل أوّل بايت. */
    fun markUploading(id: String, percent: Int) {
        beatAtMs = System.currentTimeMillis()
        beatPercent = percent
        update(id) {
            it.copy(
                state = UploadState.UPLOADING,
                lastHeartbeatMs = beatAtMs,
                lastPercent = percent,
                lastRunAtMs = beatAtMs,
                runToken = PROCESS_TOKEN,
            )
        }
    }

    /** نبضة تقدّم محفوظة — مقنَّنة بـ5% أو 10 ثوانٍ أيّهما أبعد. */
    fun heartbeat(id: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (percent - beatPercent < 5 && now - beatAtMs < HEARTBEAT_WRITE_MS) return
        beatAtMs = now
        beatPercent = percent
        update(id) {
            it.copy(
                state = UploadState.UPLOADING,
                lastHeartbeatMs = now,
                lastPercent = percent,
                lastRunAtMs = now,
                runToken = PROCESS_TOKEN,
            )
        }
    }

    /**
     * إعادة المحاولة يدويّاً: يُرفع الركن وتُصفَّر العدّادات.
     *
     * ⛔ الجلسة **لا تُمسح** ما دامت صالحة. كانت تُمسح دائماً، فبعد أن صار
     * زرّ «إعادة المحاولة» يظهر على عنصر منتظِر للشبكة صارت ضغطةٌ واحدة على
     * زرٍّ وُضِع للإنقاذ تهدم نقطة الاستئناف وتُعيد الرفع من 0% — وهو بعينه
     * العطل الذي تقوم عليه هذه المهمّة. الجلسة الميّتة لها علاجها: فرع
     * «جلسة ميّتة» في العامل، وإهمال ما تجاوز [SESSION_MAX_AGE_MS] قبل
     * المحاولة — وهو ما يُطبَّق هنا أيضاً.
     */
    fun unpark(id: String, then: () -> Unit = {}) = unparkAll(listOf(id), then)

    /**
     * إعادة محاولة **جماعيّة بمعاملة واحدة** وعلى خيط IO.
     *
     * ⚠️ كانت الضغطة الواحدة تنادي [update] لكلّ مركون، و[update] تقرأ مدوّنة
     * الطابور كاملةً (بنصوص «النص المشروح» فيها) وتحلّلها وتعيد كتابتها —
     * ⇒ O(ن²) قراءة/كتابة قرص على **خيط الواجهة** بضغطة زرّ، وخطر ANR على
     * طابور كبير. قراءة واحدة وكتابة واحدة، خارج خيط الواجهة، والواجهة
     * تُحدَّث وحدها من [items].
     *
     * [then] يُنفَّذ **بعد** الحفظ: إيقاظ العامل قبله كان قد يبدأ رفعاً لا يرى
     * فيه العنصرُ المركون قد فُكّ ركنه بعد.
     */
    fun unparkAll(ids: List<String>, then: () -> Unit = {}) {
        if (ids.isEmpty()) {
            then()
            return
        }
        val target = ids.toSet()
        cleanupScope.launch {
            synchronized(lock) {
                persist(load().map { if (it.id in target) it.revived() else it })
            }
            then()
        }
    }

    private fun PendingUpload.revived(): PendingUpload {
        val expired = sessionUri != null &&
            System.currentTimeMillis() - sessionSavedAtMs > SESSION_MAX_AGE_MS
        if (expired) Log.i(LOG_TAG, "session wiped: expired on manual retry id=$id")
        return copy(
            parked = false,
            attempts = 0,
            netFailures = 0,
            // ⚠️ `interruptions` أيضاً: بلا تصفيره يعود العنصر المركون عند
            // سقف الانسحابات فيُركن ثانيةً عند أوّل إيقاف — زرّ إنعاش لا
            // يُنعش شيئاً.
            interruptions = 0,
            lastError = null,
            sessionUri = if (expired) null else sessionUri,
            sessionSavedAtMs = if (expired) 0L else sessionSavedAtMs,
            state = UploadState.QUEUED,
            stoppedReason = null,
        )
    }

    /**
     * إزالة بعد نجاح الرفع — تحذف النسخة المحليّة **وصور النص المشروح** معها.
     *
     * ⚠️ صور الصفحات كانت تُحذف في العامل بعد نجاح نشر النص وحده، فكلّ مسار
     * آخر (إلغاء المشرف من ورقة الطابور، إلغاء أثناء الرفع، أو فشل نشر النص
     * الذي يُبتلع في `runCatching`) كان يترك نسخها في `filesDir/upload_queue`
     * إلى الأبد — خلافاً لما يَعِد به حوار التأكيد نصّاً.
     */
    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.firstOrNull { it.id == id }?.let { item ->
            // حذف الملفات في الخلفية: `cancel` يصل من نقرات الواجهة، وحذف
            // نسخة صوت تبلغ ~100MB على الخيط الرئيسي يجمّد الإطارات.
            cleanupScope.launch {
                runCatching { File(item.localPath).delete() }
                item.transcriptImagePaths.forEach { path -> runCatching { File(path).delete() } }
            }
        }
        persist(list.filterNot { it.id == id })
        // تأكيد إضافة لعنصر لم يعد موجوداً يُربك: يُمسح مع خروجه.
        clearJustEnqueued(id)
    }

    /**
     * مُلغى في هذه الجلسة — يفحصه العامل بعد اكتمال الرفع وقبل إنشاء
     * الدرس، فلا يُنشر ما ألغاه المشرف أثناء رفعه.
     */
    private val cancelled = java.util.Collections.synchronizedMap(
        linkedMapOf<String, Long>(),
    )

    /**
     * ⚠️ المجموعة كانت تنمو بلا حدّ: [cancelNow] يضيف دائماً، والمُزيل الوحيد
     * [consumeCancelled] لا يُنادى إلّا إن بلغ العامل ذلك العنصر بالذات — فكلّ
     * إلغاء لعنصر لم يبدأ رفعه يترك معرّفاً خالداً في ذاكرة العملية.
     * الراية لا تلزم إلّا حتى يفحصها العامل عقب اكتمال رفعٍ جارٍ، فمهلةٌ
     * سخيّة وسقفٌ للعدد يكفيان تماماً.
     */
    private const val CANCEL_FLAG_TTL_MS = 60L * 60 * 1000
    private const val CANCEL_FLAG_MAX = 200

    private fun pruneCancelled() {
        synchronized(cancelled) {
            val cutoff = System.currentTimeMillis() - CANCEL_FLAG_TTL_MS
            val iterator = cancelled.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) iterator.remove()
            }
            // LinkedHashMap بترتيب الإدخال: الأقدم أوّلاً.
            while (cancelled.size > CANCEL_FLAG_MAX) {
                val oldest = cancelled.keys.firstOrNull() ?: break
                cancelled.remove(oldest)
            }
        }
    }

    /**
     * مقبضا النقل الجاري (يضبطهما العامل عند بدء كلّ عنصر).
     *
     * ⛔ مقبضان لا مقبض واحد: كان `activeCancel` الوحيد يجعل **كلّ** إيقاف
     * — زرّ «إيقاف مؤقّت»، وإيقاف WorkManager عند فقدان الشبكة أو انتهاء
     * مهلة الخدمة الأماميّة — يستدعي `cancel()` فيهدم الجلسة على الخادم.
     * `pause` للإيقاف غير المقصود، و`cancel` لإلغاء المشرف الصريح وحده.
     */
    private class ActiveTask(
        val id: String,
        val pause: () -> Unit,
        val cancel: () -> Unit,
    )

    @Volatile
    private var activeTask: ActiveTask? = null

    /**
     * نقلٌ لم يصل مخرجُه بعد **في هذه العمليّة**.
     *
     * ⚠️ حارس [LessonUploadWorker.kickNow] كان تدفّق `WorkInfo` وحده، وقيمته
     * `false` في عمليّة جديدة قبل أوّل إصدار منه — فيقع REPLACE فوق رفع جارٍ
     * فعلاً: كاتبان على جلسة استئناف واحدة. و[activeTask] لا يسدّها لأنّه
     * يُمسح في `finally` قبل أن يصل مستمع الإيقاف. هذا العلم يبقى قائماً حتى
     * يصل المستمع (نجاح/إيقاف/فشل) فعلاً — أي حتى تسكت المهمّة حقّاً.
     */
    @Volatile
    private var transferring = false

    /** هل ما زال في هذه العمليّة نقلٌ لم يصل مخرجُه؟ */
    fun isTransferring(): Boolean = transferring

    /** يُنادى من مستمعي المهمّة وحدهم — لا من انسحاب العامل. */
    fun endTransfer() {
        transferring = false
    }

    fun bindActiveTask(id: String, pause: () -> Unit, cancel: () -> Unit) {
        transferring = true
        activeTask = ActiveTask(id, pause, cancel)
    }

    fun clearActiveTask(id: String) {
        if (activeTask?.id == id) activeTask = null
    }

    /** هل هذا العنصر هو الذي يُنقل الآن فعلاً؟ */
    fun isActive(id: String): Boolean = activeTask?.id == id

    fun isCancelled(id: String): Boolean = cancelled.containsKey(id)

    fun consumeCancelled(id: String): Boolean = cancelled.remove(id) != null

    /**
     * إلغاء يدويّ من المشرف — يوقف النقل الجاري إن كان هذا العنصر قيد
     * الرفع، ويمنع نشره حتى لو كان الرفع قد اكتمل لتوّه.
     *
     * وإن كان صوته قد رُفع فعلاً (عنصر مركون فشل إنشاء وثيقته) يُحذف الملفّ
     * من التخزين في الخلفية: بلا ذلك يبقى ملفّ حتى 100MB بلا وثيقة تشير
     * إليه ولا واجهة تعرضه ولا دالّة تنظّفه.
     */
    fun cancel(id: String) {
        // ⚠️ الجسد كلّه قرصيّ: `byId` تقرأ المدوّنة وتحلّلها، و`saveSession`
        // تكتبها بـ`commit()` (fsync حاجب)، و`remove` تقرأها وتكتبها ثانيةً —
        // وكان ذلك يقع كلّه على خيط الواجهة من حوار التأكيد.
        cleanupScope.launch { cancelNow(id) }
    }

    private fun cancelNow(id: String) {
        cancelled[id] = System.currentTimeMillis()
        pruneCancelled()
        // حذف اليتيم من التخزين يخصّ العناصر **المركونة** فقط: العنصر النشط
        // يتكفّل العامل بحذف ما رفعه بعد فحص الإلغاء — وحذفه هنا كان يسابق
        // إنشاء الوثيقة فيحذف صوت درس يُنشر فعلاً (درس حيّ برابط ميت).
        val running = isActive(id)
        val orphanPath = if (running) {
            null
        } else {
            byId(id)?.uploadedPath?.takeIf { it.isNotEmpty() }
        }
        // ⛔ هنا وحده يصحّ هدم الجلسة على الخادم: إلغاء صريح من المشرف.
        // وتُمسح من القرص في المعاملة نفسها فلا تبقى جلسة ميّتة توهم بالاستئناف.
        if (running) {
            Log.i(LOG_TAG, "session wiped: admin cancel id=$id")
            saveSession(id, null)
            activeTask?.let { runCatching { it.cancel() } }
        }
        remove(id)
        if (_progress.value?.id == id) _progress.value = null
        if (orphanPath != null) {
            cleanupScope.launch { runCatching { StorageService.deleteFileOrThrow(orphanPath) } }
        }
    }

    /**
     * إلغاء كلّ ما في الطابور — لكلّ عنصر منطق [cancel] نفسه بلا استثناء،
     * وكلّه في مهمّة خلفيّة واحدة: كان يكرّر عمل القرص كاملاً لكلّ عنصر على
     * خيط الواجهة.
     */
    fun cancelAll() {
        val ids = _items.value.map { it.id }
        cleanupScope.launch { ids.forEach { cancelNow(it) } }
    }

    fun setProgress(p: UploadProgress?) {
        _progress.value = p
    }

    // من مرآة الذاكرة لا من القرص: كانت كلّ نبضة تقدّم تعيد قراءة التفضيلات
    // وتحليل JSON الطابور كاملاً (حتى ~100 مرّة لكل ملف).
    fun isEmpty(): Boolean = _items.value.isEmpty()

    fun count(): Int = _items.value.size
}
