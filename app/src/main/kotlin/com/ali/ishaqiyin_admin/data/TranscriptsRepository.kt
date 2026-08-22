package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
        fun fromDoc(doc: DocumentSnapshot): TranscriptSubmission {
            val d = doc.dataMap()
            return TranscriptSubmission(
                id = doc.id,
                uid = str(d["uid"]),
                submitterName = str(d["submitterName"]),
                lessonId = str(d["lessonId"]),
                lessonTitle = str(d["lessonTitle"]),
                text = str(d["text"]),
                bookTitle = str(d["bookTitle"]),
                sourceRef = str(d["sourceRef"]),
                note = str(d["note"]),
                imagePaths = (d["imagePaths"] as? List<*>)
                    .orEmpty().map { str(it) }.filter { it.isNotEmpty() },
                status = str(d["status"]).ifEmpty { "pending" },
                rejectReason = str(d["rejectReason"]),
                createdAtMs = parseDateMs(d["createdAt"]),
            )
        }
    }
}

/**
 * حصيلة قرار جماعي على اقتراحات النصوص: كم اعتُمد/رُفض، وكم أخفق، وكم
 * اقتراحاً تُخطّي لأن درسه سبق أن حُسم في الدفعة نفسها (اعتماد نصّين لدرس
 * واحد يمحو أوّلهما بلا رجعة — فالتخطّي هنا حماية لا نقص).
 */
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
    companion object {
        fun fromDoc(doc: DocumentSnapshot): LessonTranscript {
            val d = doc.dataMap()
            return LessonTranscript(
                lessonId = doc.id,
                text = str(d["text"]),
                bookTitle = str(d["bookTitle"]),
                sourceRef = str(d["sourceRef"]),
                images = (d["images"] as? List<*>).orEmpty().mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    val path = str(m["path"])
                    val url = str(m["url"])
                    if (url.isEmpty()) null else TranscriptImage(path, url)
                },
                contributorName = str(d["contributorName"]),
                updatedBy = str(d["updatedBy"]),
            )
        }
    }
}

/**
 * 📖 «النص المشروح»: المتن/المقطع الأصلي الذي تشرحه الصوتية.
 * - اقتراحات المستمعين تُراجَع هنا (اعتماد/تعديل/رفض) بنفس دورة «شارك درساً».
 * - والمشرف يضيف أو يعدّل النص مباشرة من شاشة إدارة الدروس.
 * الكتابة الفعلية كلها عبر Cloud Functions (تحقّق + تدقيق + روابط صور).
 */
object TranscriptsRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()
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

    // Firestore يسلّم اللقطة على الخيط الرئيسي؛ التحليل والفرز يجريان هنا
    // بعيداً عنه كي لا يتقطّع التمرير عند كل تحديث للمجموعة.
    fun watchAll(): Flow<List<TranscriptSubmission>> =
        db.collection(COLLECTION).querySnapshots().map { snap ->
            snap.documents
                .map { TranscriptSubmission.fromDoc(it) }
                .sortedWith(
                    compareByDescending<TranscriptSubmission> { it.isPending }
                        .thenByDescending { it.createdAtMs },
                )
        }.flowOn(Dispatchers.Default)

    fun watchPendingCount(): Flow<Int> =
        db.collection(COLLECTION).whereEqualTo("status", "pending")
            .querySnapshots().map { it.size() }
            .flowOn(Dispatchers.Default)

    /**
     * النص المعتمد الحالي لدرس — للمقارنة أثناء المراجعة وللمحرر المباشر.
     * بكاش عمره ١٠ دقائق يُبطَل فور أيّ اعتماد/حفظ/حذف لهذا الدرس.
     */
    suspend fun fetchTranscript(lessonId: String): LessonTranscript? {
        if (lessonId.isEmpty()) return null
        val now = System.currentTimeMillis()
        transcriptCache[lessonId]?.let { (at, value) ->
            if (now - at < TRANSCRIPT_TTL_MS) return value
        }
        val doc = db.collection(TRANSCRIPTS).document(lessonId).get().await()
        val transcript = if (doc.exists()) LessonTranscript.fromDoc(doc) else null
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
        val ref = db.collection(TRANSCRIPTS).document(lessonId)
        val cached = runCatching { ref.get(Source.CACHE).await() }.getOrNull()
        val exists = if (cached != null && cached.exists()) {
            true
        } else {
            ref.get().await().exists()
        }
        presenceCache[lessonId] = System.currentTimeMillis() to exists
        return exists
    }

    /**
     * رابط عرض صورة اقتراح معلّق (قواعد التخزين تسمح للمشرفين بقراءتها).
     * الروابط تُخزَّن يوماً كاملاً وبسقف ٥٠٠ مدخل، فلا يتكرّر طلب `downloadUrl`
     * لكل صورة عند كل زيارة للشاشة.
     */
    suspend fun submissionImageUrl(path: String): String {
        if (path.isEmpty()) return ""
        val now = System.currentTimeMillis()
        urlCache[path]?.let { (at, url) ->
            if (now - at < URL_TTL_MS) return url
        }
        val url = storage.reference.child(path).downloadUrl.await().toString()
        pruneUrlCache()
        urlCache[path] = System.currentTimeMillis() to url
        return url
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
        val payload = mutableMapOf<String, Any>(
            "submissionId" to s.id,
            "keepImages" to keepImages,
        )
        if (editedText != null) payload["text"] = editedText.trim()
        if (editedBookTitle != null) payload["bookTitle"] = editedBookTitle.trim()
        if (editedSourceRef != null) payload["sourceRef"] = editedSourceRef.trim()
        functions.getHttpsCallable("approveTranscriptSubmission").call(payload).await()
        // صار للدرس نصّ معتمد قطعاً — نثبّت الجواب فوراً كي يحذّر أيّ اقتراح
        // آخر لنفس الدرس بدل أن يمحوه المشرف وهو يظنّه أوّل نص.
        invalidateTranscript(s.lessonId, known = true)
        forgetImageUrls(s.imagePaths)
    }

    suspend fun reject(s: TranscriptSubmission, reason: String) {
        functions.getHttpsCallable("rejectTranscriptSubmission").call(
            mapOf("submissionId" to s.id, "reason" to reason.trim()),
        ).await()
        forgetImageUrls(s.imagePaths)
    }

    suspend fun deleteDecided(s: TranscriptSubmission) {
        if (s.isPending) return
        functions.getHttpsCallable("deleteTranscriptSubmission")
            .call(mapOf("submissionId" to s.id)).await()
        forgetImageUrls(s.imagePaths)
    }

    /**
     * اعتماد جماعي لاقتراحات نصوص محدَّدة. [onProgress] يتلقّى (المنجز،
     * الإجمالي) لتحريك شريط التقدّم كما في «اعتماد الكل» عند المالك.
     * درسٌ حُسم في الدفعة لا يُعتمد له اقتراح ثانٍ — لأن الاعتماد يستبدل
     * الوثيقة كاملة ويحذف صور النص السابق.
     */
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
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذّرت قراءة الصورة.")
        require(bytes.size <= 10 * 1024 * 1024) { "حجم الصورة يتجاوز 10MB." }
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType(context.contentResolver.getType(uri) ?: "image/jpeg")
            .build()
        storage.reference.child(path).putBytes(bytes, metadata).await()
        path
    }

    /** حفظ النص المشروح مباشرة (إضافة أو تعديلاً) — يكتب عبر الخادم. */
    suspend fun upsert(
        lessonId: String,
        text: String,
        bookTitle: String,
        sourceRef: String,
        imagePaths: List<String>,
    ) {
        functions.getHttpsCallable("upsertLessonTranscript").call(
            mapOf(
                "lessonId" to lessonId,
                "text" to text.trim(),
                "bookTitle" to bookTitle.trim(),
                "sourceRef" to sourceRef.trim(),
                "imagePaths" to imagePaths,
            ),
        ).await()
        invalidateTranscript(lessonId, known = true)
    }

    /** حذف النص المشروح للدرس نهائياً (الوثيقة + صور مجلدها). */
    suspend fun remove(lessonId: String) {
        functions.getHttpsCallable("upsertLessonTranscript").call(
            mapOf("lessonId" to lessonId, "remove" to true),
        ).await()
        invalidateTranscript(lessonId, known = false)
    }

    /** استخراج النص من صورة صفحة (OCR عربي عبر الخادم — Cloud Vision). */
    suspend fun extractText(storagePath: String): String {
        val result = functions.getHttpsCallable("extractImageText")
            .call(mapOf("storagePath" to storagePath)).await()
        val map = result.data as? Map<*, *> ?: return ""
        return str(map["text"])
    }
}
