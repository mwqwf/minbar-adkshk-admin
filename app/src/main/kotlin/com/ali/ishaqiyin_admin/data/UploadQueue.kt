package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val attempts: Int = 0,
    val lastError: String? = null,
    /**
     * رُكن بعد فشل دائم: يبقى معروضاً للمشرف ليقرّر، لكنّه **يخرج من دور
     * الرفع** — بلا هذا كان العامل يعيد رفعه بلا توقّف حين لا يبقى غيره.
     */
    val parked: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("seq", seq)
        put("title", title)
        put("categoryId", categoryId)
        put("subcategoryId", subcategoryId)
        put("sectionLabel", sectionLabel)
        put("featured", featured)
        put("featuredUntilMs", featuredUntilMs ?: JSONObject.NULL)
        put("addedBy", addedBy)
        put("localPath", localPath)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("queuedAtMs", queuedAtMs)
        put("sessionUri", sessionUri ?: JSONObject.NULL)
        put("attempts", attempts)
        put("lastError", lastError ?: JSONObject.NULL)
        put("parked", parked)
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingUpload(
            id = o.optString("id"),
            seq = o.optLong("seq"),
            title = o.optString("title"),
            categoryId = o.optString("categoryId"),
            subcategoryId = o.optString("subcategoryId"),
            sectionLabel = o.optString("sectionLabel"),
            featured = o.optBoolean("featured"),
            featuredUntilMs = o.optLong("featuredUntilMs").takeIf { it > 0L },
            addedBy = o.optString("addedBy"),
            localPath = o.optString("localPath"),
            fileName = o.optString("fileName"),
            sizeBytes = o.optLong("sizeBytes"),
            queuedAtMs = o.optLong("queuedAtMs"),
            sessionUri = o.optString("sessionUri").takeIf { it.isNotEmpty() && it != "null" },
            attempts = o.optInt("attempts"),
            lastError = o.optString("lastError").takeIf { it.isNotEmpty() && it != "null" },
            parked = o.optBoolean("parked"),
        )
    }
}

/** تقدّم الرفع الجاري الآن (للمؤشّر الحيّ). */
data class UploadProgress(
    val id: String,
    val title: String,
    val percent: Int,
    val waitingForNetwork: Boolean = false,
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
)

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
    private const val SEQ_KEY = "lesson_upload_seq_v1"
    private const val DIR = "upload_queue"

    private val _items = MutableStateFlow<List<PendingUpload>>(emptyList())
    val items: StateFlow<List<PendingUpload>> = _items

    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress

    /** تأكيد الإضافة الأخيرة — تعرضه الواجهة لحظات ثمّ تمسحه. */
    private val _justEnqueued = MutableStateFlow<JustEnqueued?>(null)
    val justEnqueued: StateFlow<JustEnqueued?> = _justEnqueued

    private val lock = Any()

    fun init(context: Context) {
        AppPrefs.init(context)
        _items.value = load()
    }

    private fun prefs() = AppPrefs.context
        .getSharedPreferences("minbar_admin_prefs", Context.MODE_PRIVATE)

    private fun load(): List<PendingUpload> = runCatching {
        val raw = prefs().getString(PREFS_KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length())
            .map { PendingUpload.fromJson(arr.getJSONObject(it)) }
            .sortedBy { it.seq }
    }.getOrDefault(emptyList())

    private fun persist(list: List<PendingUpload>) {
        val arr = JSONArray()
        list.sortedBy { it.seq }.forEach { arr.put(it.toJson()) }
        prefs().edit().putString(PREFS_KEY, arr.toString()).apply()
        _items.value = list.sortedBy { it.seq }
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

    /** تُنادى من الواجهة بعد عرض التأكيد لحظات. */
    fun clearJustEnqueued(id: String) {
        if (_justEnqueued.value?.id == id) _justEnqueued.value = null
    }

    /** مجلّد النسخ الدائم (files لا cache — النظام لا يمسحه تحت الضغط). */
    private fun queueDir(): File =
        File(AppPrefs.context.filesDir, DIR).apply { mkdirs() }

    /**
     * ينسخ الملفّ المختار إلى تخزين التطبيق ثم يُدرجه في الطابور.
     * يعيد العنصر المُدرَج، أو يرمي إن تعذّرت قراءة الملفّ.
     */
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
    ): PendingUpload = withContext(Dispatchers.IO) {
        val id = "up_${System.currentTimeMillis()}_${(0..9999).random()}"
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
            sectionLabel = sectionLabel,
            featured = featured,
            featuredUntilMs = featuredUntilMs,
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) { persist(load() + item) }
        // بعد الحفظ: الترتيب صار نهائياً فيصحّ إعلان الموقع «س من ص».
        markJustEnqueued(item)
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
    ): PendingUpload = withContext(Dispatchers.IO) {
        val id = "up_${System.currentTimeMillis()}_${(0..9999).random()}"
        val dest = File(queueDir(), "${id}_${file.name}")
        file.copyTo(dest, overwrite = true)
        runCatching { file.delete() }
        val item = PendingUpload(
            id = id,
            seq = nextSeq(),
            title = title.trim(),
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            sectionLabel = sectionLabel,
            featured = featured,
            featuredUntilMs = featuredUntilMs,
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) { persist(load() + item) }
        // بعد الحفظ: الترتيب صار نهائياً فيصحّ إعلان الموقع «س من ص».
        markJustEnqueued(item)
        item
    }

    /**
     * العنصر التالي للرفع: أقدم عنصر **غير مركون** — أساس ضمان الترتيب.
     * المركون يبقى في القائمة للعرض وإعادة المحاولة اليدويّة فقط.
     */
    fun peek(): PendingUpload? = synchronized(lock) {
        load().filterNot { it.parked }.minByOrNull { it.seq }
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
     * إعادة المحاولة يدويّاً لعنصر مركون — تُمسح جلسة الرفع أيضاً: جلسة
     * منتهية الصلاحية كانت تُفشل كلّ محاولة جديدة بلا نهاية.
     */
    fun unpark(id: String) = update(id) {
        it.copy(parked = false, attempts = 0, lastError = null, sessionUri = null)
    }

    /** إزالة بعد نجاح الرفع — تحذف النسخة المحليّة أيضاً. */
    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.firstOrNull { it.id == id }?.let { runCatching { File(it.localPath).delete() } }
        persist(list.filterNot { it.id == id })
        // تأكيد إضافة لعنصر لم يعد موجوداً يُربك: يُمسح مع خروجه.
        clearJustEnqueued(id)
    }

    /**
     * مُلغى في هذه الجلسة — يفحصه العامل بعد اكتمال الرفع وقبل إنشاء
     * الدرس، فلا يُنشر ما ألغاه المشرف أثناء رفعه.
     */
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** يوقف النقل الجاري فعليّاً (يضبطه العامل عند بدء كلّ عنصر). */
    @Volatile
    private var activeCancel: Pair<String, () -> Unit>? = null

    fun bindActiveTask(id: String, cancelTask: () -> Unit) {
        activeCancel = id to cancelTask
    }

    fun clearActiveTask(id: String) {
        if (activeCancel?.first == id) activeCancel = null
    }

    fun isCancelled(id: String): Boolean = cancelled.contains(id)

    fun consumeCancelled(id: String): Boolean = cancelled.remove(id)

    /**
     * إلغاء يدويّ من المشرف — يوقف النقل الجاري إن كان هذا العنصر قيد
     * الرفع، ويمنع نشره حتى لو كان الرفع قد اكتمل لتوّه.
     */
    fun cancel(id: String) {
        cancelled.add(id)
        activeCancel?.let { (activeId, stop) -> if (activeId == id) runCatching { stop() } }
        remove(id)
        if (_progress.value?.id == id) _progress.value = null
    }

    fun setProgress(p: UploadProgress?) {
        _progress.value = p
    }

    fun isEmpty(): Boolean = load().isEmpty()

    fun count(): Int = load().size
}
