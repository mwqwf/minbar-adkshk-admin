package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.ali.ishaqiyin_admin.core.MinbarAdminApi

/** اقتراح «نص مشروح» من مستمع (نص المتن/صور صفحاته) بانتظار قرار المشرفين. */
data class TranscriptSubmission(
    val id: String,
    val uid: String,
    val submitterName: String,
    val lessonId: String,
    val lessonTitle: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val note: String,
    val imagePaths: List<String>,
    val status: String, // pending | approved | approved_edited | rejected
    val rejectReason: String,
    val createdAtMs: Long,
) {
    val isPending: Boolean get() = status == "pending"

    companion object {
        fun fromRow(row: org.json.JSONObject): TranscriptSubmission {
            val keys = runCatching { org.json.JSONArray(row.optString("image_keys_json", "[]")) }.getOrDefault(org.json.JSONArray())
            return TranscriptSubmission(
                id = row.optString("id"),
                uid = row.optString("device_id"),
                submitterName = row.optString("submitter_name"),
                lessonId = row.optString("lesson_id"),
                lessonTitle = row.optString("lesson_title"),
                text = row.optString("text"),
                bookTitle = row.optString("book_title"),
                sourceRef = row.optString("source_ref"),
                note = row.optString("note"),
                imagePaths = (0 until keys.length()).map { keys.optString(it) }.filter { it.isNotEmpty() },
                status = row.optString("status").ifEmpty { "pending" },
                rejectReason = row.optString("reject_reason"),
                createdAtMs = row.optLong("created_at_ms"),
            )
        }

    }
}

data class BulkTranscriptResult(
    val done: Int,
    val failed: Int,
    val skippedDuplicates: Int = 0,
    /** دروس اعتُمد لها نصّ فعلاً — وحدها تستحق تحذير «سيُستبدل». */
    val approvedLessonIds: Set<String> = emptySet(),
)

/** صورة معتمدة ضمن النص المشروح (مسار التخزين + رابط العرض العام). */
data class TranscriptImage(val path: String, val url: String)

/** النص المشروح المعتمد لدرس (وثيقة lesson_transcripts/{lessonId}). */
data class LessonTranscript(
    val lessonId: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val images: List<TranscriptImage>,
    val contributorName: String,
    val updatedBy: String,
) {
}

object TranscriptsRepository {
    /** صفّ `transcripts` من minbar-api (snake_case) → النموذج. */
    private fun fromRow(row: org.json.JSONObject): LessonTranscript {
        val images = runCatching { org.json.JSONArray(row.optString("images_json", "[]")) }.getOrDefault(org.json.JSONArray())
        return LessonTranscript(
            lessonId = row.optString("lesson_id"),
            text = row.optString("text"),
            bookTitle = row.optString("book_title"),
            sourceRef = row.optString("source_ref"),
            images = (0 until images.length()).mapNotNull { i ->
                val o = images.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isEmpty()) null else TranscriptImage(o.optString("path"), url)
            },
            contributorName = row.optString("contributor_name"),
            updatedBy = "",
        )
    }

    const val COLLECTION = "transcript_submissions"
    const val TRANSCRIPTS = "lesson_transcripts"
    const val MAX_IMAGES = 4

    // ─── كاشات المستودع ──────────────────────────────────────────────
    // شاشة المساهمات كانت تقرأ وثيقة نص لكل اقتراح معروض عند كل تمرير،
    // وتُعيد الكرّة كاملة عند كل عودة للشاشة (الحماية كانت في الواجهة
    // فتموت معها). الكاش هنا في المستودع فيَبقى عبر الشاشات، **ويُبطَل
    // فوراً** بعد كل اعتماد/حفظ/حذف كي لا يُحيي قيمة بائتة.
    private const val TRANSCRIPT_TTL_MS = 10 * 60 * 1000L
    private const val URL_TTL_MS = 24 * 60 * 60 * 1000L
    private const val URL_CACHE_MAX = 500

    private val transcriptCache = ConcurrentHashMap<String, Pair<Long, LessonTranscript?>>()
    private val presenceCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val urlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * إبطال كاش درس بعينه. [known] يضع الجواب المؤكَّد فوراً بدل تركه
     * لقراءة لاحقة قد تصادف كاش Firestore المحلّي البائت.
     */
    fun invalidateTranscript(lessonId: String, known: Boolean? = null) {
        if (lessonId.isEmpty()) return
        transcriptCache.remove(lessonId)
        if (known == null) {
            presenceCache.remove(lessonId)
        } else {
            presenceCache[lessonId] = System.currentTimeMillis() to known
        }
    }

    /** إسقاط روابط صور اقتراح حُسم (لم تعد تُعرض، ومسارها قد يُحذف). */
    fun forgetImageUrls(paths: List<String>) {
        paths.forEach { urlCache.remove(it) }
    }

    /** حجم صفحة المحسوم — والزيادة عبر [loadMoreDecided] (نمط سلة المحذوفات). */
    private const val DECIDED_PAGE = 50
    private const val POLL_MS = 30_000L
    private val decidedLimit = kotlinx.coroutines.flow.MutableStateFlow(DECIDED_PAGE)
    val hasMoreDecided = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun loadMoreDecided() {
        decidedLimit.value += DECIDED_PAGE
    }

    private fun org.json.JSONArray?.rows(): List<org.json.JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun fetchAll(limit: Int): List<TranscriptSubmission> {
        val list = MinbarAdminApi.get("/admin/transcript-submissions?status=all").optJSONArray("items").rows()
            .map(TranscriptSubmission::fromRow)
        val decided = list.filter { !it.isPending }
        hasMoreDecided.value = decided.size > limit
        return (list.filter { it.isPending } + decided.take(limit))
            .sortedWith(compareByDescending<TranscriptSubmission> { it.isPending }.thenByDescending { it.createdAtMs })
    }

    fun watchAll(): Flow<List<TranscriptSubmission>> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(runCatching { fetchAll(decidedLimit.value) }.getOrDefault(emptyList()))
            kotlinx.coroutines.delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun watchPendingCount(): Flow<Int> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(runCatching { MinbarAdminApi.get("/admin/community/counts").optInt("pendingTranscripts", 0) }.getOrDefault(0))
            kotlinx.coroutines.delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun fetchTranscript(lessonId: String): LessonTranscript? {
        if (lessonId.isEmpty()) return null
        val now = System.currentTimeMillis()
        transcriptCache[lessonId]?.let { (at, value) ->
            if (now - at < TRANSCRIPT_TTL_MS) return value
        }
        val transcript = try {
            fromRow(MinbarAdminApi.get("/admin/transcripts/$lessonId"))
        } catch (e: MinbarAdminApi.ApiException) {
            if (e.code == 404) null else throw e
        }
        transcriptCache[lessonId] = System.currentTimeMillis() to transcript
        presenceCache[lessonId] = System.currentTimeMillis() to (transcript != null)
        return transcript
    }

    /**
     * هل لهذا الدرس نصّ معتمد؟ — الجواب الذي تحتاجه شاشة المراجعة فعلاً.
     * يقرأ كاش Firestore المحلّي أوّلاً (كحيلة الدخول السريع في AuthService)
     * ولا ينزل للخادم إلا عند غيابه، ثم يحفظ الجواب ١٠ دقائق.
     */
    suspend fun hasTranscript(lessonId: String): Boolean {
        if (lessonId.isEmpty()) return false
        val now = System.currentTimeMillis()
        presenceCache[lessonId]?.let { (at, value) ->
            if (now - at < TRANSCRIPT_TTL_MS) return value
        }
        val exists = fetchTranscript(lessonId) != null
        presenceCache[lessonId] = System.currentTimeMillis() to exists
        return exists
    }

    /**
     * رابط عرض صورة اقتراح معلّق (قواعد التخزين تسمح للمشرفين بقراءتها).
     * الروابط تُخزَّن يوماً كاملاً وبسقف ٥٠٠ مدخل، فلا يتكرّر طلب `downloadUrl`
     * لكل صورة عند كل زيارة للشاشة.
     */
    suspend fun submissionImageUrl(path: String): String {
        // صور R2 (images/…) تُقرأ عبر minbar-api مباشرة بلا رابط موقَّع.
        if (path.startsWith("images/") || path.startsWith("user/")) return MinbarAdminApi.mediaUrl(path)
        if (path.isEmpty()) return ""
        val now = System.currentTimeMillis()
        urlCache[path]?.let { (at, url) ->
            if (now - at < URL_TTL_MS) return url
        }
        // ما بقي مسارٌ قديم من Firebase — لا رابط له بعد إطفاء التخزين.
        return ""
    }

    /** تنظيف المنتهي أوّلاً، ثم الأقدم إن تجاوزت الخريطة سقفها. */
    private fun pruneUrlCache() {
        val now = System.currentTimeMillis()
        urlCache.entries.removeAll { now - it.value.first >= URL_TTL_MS }
        if (urlCache.size < URL_CACHE_MAX) return
        urlCache.entries
            .sortedBy { it.value.first }
            .take(urlCache.size - URL_CACHE_MAX + 1)
            .forEach { urlCache.remove(it.key) }
    }

    suspend fun approve(
        s: TranscriptSubmission,
        editedText: String? = null,
        editedBookTitle: String? = null,
        editedSourceRef: String? = null,
        keepImages: Boolean = true,
    ) {
        val payload = org.json.JSONObject().put("keepImages", keepImages)
        if (editedText != null) payload.put("text", editedText.trim())
        if (editedBookTitle != null) payload.put("bookTitle", editedBookTitle.trim())
        if (editedSourceRef != null) payload.put("sourceRef", editedSourceRef.trim())
        MinbarAdminApi.post("/admin/transcript-submissions/${s.id}/approve", payload)
        invalidateTranscript(s.lessonId, known = true)
        forgetImageUrls(s.imagePaths)
    }

    suspend fun reject(s: TranscriptSubmission, reason: String) {
        MinbarAdminApi.post("/admin/transcript-submissions/${s.id}/reject", org.json.JSONObject().put("reason", reason.trim()))
        forgetImageUrls(s.imagePaths)
    }

    suspend fun deleteDecided(s: TranscriptSubmission) {
        if (s.isPending) return
        MinbarAdminApi.delete("/admin/transcript-submissions/${s.id}")
        forgetImageUrls(s.imagePaths)
    }

    suspend fun bulkApprove(
        items: List<TranscriptSubmission>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkTranscriptResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        var skipped = 0
        val handledLessons = mutableSetOf<String>()
        val approved = mutableSetOf<String>()
        targets.forEachIndexed { index, s ->
            if (!handledLessons.add(s.lessonId)) {
                skipped++
            } else {
                runCatching { approve(s) }
                    .onSuccess {
                        done++
                        approved += s.lessonId
                    }
                    .onFailure { failed++ }
            }
            onProgress(index + 1, targets.size)
        }
        return BulkTranscriptResult(done, failed, skipped, approved)
    }

    /** رفض جماعي بسبب واحد يصل كل المساهمين المعنيّين. */
    suspend fun bulkReject(
        items: List<TranscriptSubmission>,
        reason: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkTranscriptResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        targets.forEachIndexed { index, s ->
            runCatching { reject(s, reason) }
                .onSuccess { done++ }
                .onFailure { failed++ }
            onProgress(index + 1, targets.size)
        }
        return BulkTranscriptResult(done, failed)
    }

    /**
     * رفع صورة صفحة كتاب من الجهاز إلى مجلد النص المعتمد للدرس (يرجع
     * مسار التخزين). تُستدعى قبل [upsert] الذي يتحقق ويولّد الروابط.
     * القراءة والرفع خارج الخيط الرئيسي: صورة صفحة كاملة كانت تُقرأ في
     * الذاكرة والواجهة مجمَّدة، فيصل التجميد حدّ ANR على الصور الكبيرة.
     */
    suspend fun uploadTranscriptImage(
        context: Context,
        lessonId: String,
        uri: Uri,
    ): String = withContext(Dispatchers.IO) {
        val name = "${System.currentTimeMillis()}_page.jpg"
        val path = "$TRANSCRIPTS/$lessonId/$name"
        // الحجم من واصف الملف قبل القراءة: رفض الصورة الضخمة برسالتها بدل
        // انهيار الذاكرة أثناء تحميلها كاملةً لمجرّد قياسها.
        val declared = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        require(declared < 0 || declared <= 10L * 1024 * 1024) { "حجم الصورة يتجاوز 10MB." }
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذّرت قراءة الصورة.")
        require(raw.size <= 10 * 1024 * 1024) { "حجم الصورة يتجاوز 10MB." }
        // ضغط صورة الصفحة قبل الرفع (2400px/85 — نمط ChatUploader): النص يبقى
        // مقروءاً تماماً والحجم ينخفض أضعافاً. عند تعذّر الضغط تُرفع كما هي.
        val bytes = com.ali.ishaqiyin_admin.util.ImageCompressor.compressTranscriptImage(raw)
        val contentType = if (bytes !== raw) "image/jpeg" else (context.contentResolver.getType(uri) ?: "image/jpeg")
        val key = "images/$path"
        val temp = java.io.File(context.cacheDir, "tx_${System.currentTimeMillis()}.jpg")
        temp.writeBytes(bytes)
        try {
            MinbarAdminApi.upload("/admin/upload/$key", temp, contentType)
        } finally {
            temp.delete()
        }
        key
    }

    /** حفظ النص المشروح مباشرة (إضافة أو تعديلاً) — يكتب عبر الخادم. */
    suspend fun upsert(
        lessonId: String,
        text: String,
        bookTitle: String,
        sourceRef: String,
        imagePaths: List<String>,
    ) {
        val images = org.json.JSONArray()
        imagePaths.forEach { key ->
            images.put(org.json.JSONObject().put("path", key).put("url", MinbarAdminApi.mediaUrl(key)))
        }
        MinbarAdminApi.put(
            "/admin/transcripts/$lessonId",
            org.json.JSONObject()
                .put("text", text.trim())
                .put("bookTitle", bookTitle.trim())
                .put("sourceRef", sourceRef.trim())
                .put("imagesJson", images),
        )
        invalidateTranscript(lessonId, known = true)
    }

    /** حذف النص المشروح للدرس نهائياً (الوثيقة + صور مجلدها). */
    suspend fun remove(lessonId: String) {
        MinbarAdminApi.delete("/admin/transcripts/$lessonId")
        invalidateTranscript(lessonId, known = false)
    }

    /** استخراج النص من صورة صفحة (OCR عربي عبر الخادم — Cloud Vision). */
    suspend fun extractText(storagePath: String): String {
        // OCR كان على Cloud Vision (مدفوع) عبر Functions — غير متاح بعد الاستغناء
        // عن Firebase. يُعرض كخطأ مفهوم حتى يُستبدل بمحرّك مجاني.
        throw IllegalStateException(
            "استخراج النص من الصورة غير متاح حالياً بعد الانتقال عن Firebase — اكتب النص يدوياً.",
        )
    }
}
