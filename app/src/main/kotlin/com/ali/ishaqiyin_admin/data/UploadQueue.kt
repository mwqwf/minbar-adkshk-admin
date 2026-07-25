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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("seq", seq)
        put("title", title)
        put("categoryId", categoryId)
        put("subcategoryId", subcategoryId)
        put("sectionLabel", sectionLabel)
        put("featured", featured)
        put("addedBy", addedBy)
        put("localPath", localPath)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("queuedAtMs", queuedAtMs)
        put("sessionUri", sessionUri ?: JSONObject.NULL)
        put("attempts", attempts)
        put("lastError", lastError ?: JSONObject.NULL)
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
            addedBy = o.optString("addedBy"),
            localPath = o.optString("localPath"),
            fileName = o.optString("fileName"),
            sizeBytes = o.optLong("sizeBytes"),
            queuedAtMs = o.optLong("queuedAtMs"),
            sessionUri = o.optString("sessionUri").takeIf { it.isNotEmpty() && it != "null" },
            attempts = o.optInt("attempts"),
            lastError = o.optString("lastError").takeIf { it.isNotEmpty() && it != "null" },
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
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) { persist(load() + item) }
        item
    }

    /** يُدرج ملفاً مدموجاً جاهزاً (نُقل بالفعل إلى تخزين التطبيق). */
    fun enqueueLocalFile(
        file: File,
        fileName: String,
        title: String,
        categoryId: String,
        subcategoryId: String,
        sectionLabel: String,
        featured: Boolean,
        addedBy: String,
    ): PendingUpload {
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
            addedBy = addedBy,
            localPath = dest.absolutePath,
            fileName = fileName,
            sizeBytes = dest.length(),
            queuedAtMs = System.currentTimeMillis(),
        )
        synchronized(lock) { persist(load() + item) }
        return item
    }

    /** العنصر التالي للرفع (الأقدم إدراجاً) — أساس ضمان الترتيب. */
    fun peek(): PendingUpload? = synchronized(lock) { load().minByOrNull { it.seq } }

    fun update(item: PendingUpload) = synchronized(lock) {
        persist(load().map { if (it.id == item.id) item else it })
    }

    /** إزالة بعد نجاح الرفع — تحذف النسخة المحليّة أيضاً. */
    fun remove(id: String) = synchronized(lock) {
        val list = load()
        list.firstOrNull { it.id == id }?.let { runCatching { File(it.localPath).delete() } }
        persist(list.filterNot { it.id == id })
    }

    /** إلغاء يدويّ من المشرف. */
    fun cancel(id: String) {
        remove(id)
        if (_progress.value?.id == id) _progress.value = null
    }

    fun setProgress(p: UploadProgress?) {
        _progress.value = p
    }

    fun isEmpty(): Boolean = load().isEmpty()

    fun count(): Int = load().size
}
