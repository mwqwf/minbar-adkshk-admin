package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

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

    fun watchAll(): Flow<List<TranscriptSubmission>> =
        db.collection(COLLECTION).querySnapshots().map { snap ->
            snap.documents
                .map { TranscriptSubmission.fromDoc(it) }
                .sortedWith(
                    compareByDescending<TranscriptSubmission> { it.isPending }
                        .thenByDescending { it.createdAtMs },
                )
        }

    fun watchPendingCount(): Flow<Int> =
        db.collection(COLLECTION).whereEqualTo("status", "pending")
            .querySnapshots().map { it.size() }

    /** النص المعتمد الحالي لدرس — للمقارنة أثناء المراجعة وللمحرر المباشر. */
    suspend fun fetchTranscript(lessonId: String): LessonTranscript? {
        val doc = db.collection(TRANSCRIPTS).document(lessonId).get().await()
        return if (doc.exists()) LessonTranscript.fromDoc(doc) else null
    }

    /** رابط عرض صورة اقتراح معلّق (قواعد التخزين تسمح للمشرفين بقراءتها). */
    suspend fun submissionImageUrl(path: String): String =
        storage.reference.child(path).downloadUrl.await().toString()

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
    }

    suspend fun reject(s: TranscriptSubmission, reason: String) {
        functions.getHttpsCallable("rejectTranscriptSubmission").call(
            mapOf("submissionId" to s.id, "reason" to reason.trim()),
        ).await()
    }

    suspend fun deleteDecided(s: TranscriptSubmission) {
        if (s.isPending) return
        functions.getHttpsCallable("deleteTranscriptSubmission")
            .call(mapOf("submissionId" to s.id)).await()
    }

    /**
     * رفع صورة صفحة كتاب من الجهاز إلى مجلد النص المعتمد للدرس (يرجع
     * مسار التخزين). تُستدعى قبل [upsert] الذي يتحقق ويولّد الروابط.
     */
    suspend fun uploadTranscriptImage(context: Context, lessonId: String, uri: Uri): String {
        val name = "${System.currentTimeMillis()}_page.jpg"
        val path = "$TRANSCRIPTS/$lessonId/$name"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذّرت قراءة الصورة.")
        require(bytes.size <= 10 * 1024 * 1024) { "حجم الصورة يتجاوز 10MB." }
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType(context.contentResolver.getType(uri) ?: "image/jpeg")
            .build()
        storage.reference.child(path).putBytes(bytes, metadata).await()
        return path
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
    }

    /** حذف النص المشروح للدرس نهائياً (الوثيقة + صور مجلدها). */
    suspend fun remove(lessonId: String) {
        functions.getHttpsCallable("upsertLessonTranscript").call(
            mapOf("lessonId" to lessonId, "remove" to true),
        ).await()
    }

    /** استخراج النص من صورة صفحة (OCR عربي عبر الخادم — Cloud Vision). */
    suspend fun extractText(storagePath: String): String {
        val result = functions.getHttpsCallable("extractImageText")
            .call(mapOf("storagePath" to storagePath)).await()
        val map = result.data as? Map<*, *> ?: return ""
        return str(map["text"])
    }
}
