package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** طلب نشر من مستمع (تطبيق منبر العام) بانتظار قرار المشرفين. */
data class LessonSubmission(
    val id: String,
    val uid: String,
    val submitterName: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val subcategoryId: String,
    val subcategoryName: String,
    val note: String,
    val audioUrl: String,
    val storagePath: String,
    val fileName: String,
    val fileSize: Int,
    val fcmToken: String,
    val status: String, // pending | approved | approved_edited | rejected
    val rejectReason: String,
    val createdAtMs: Long,
    // ⚠️ نصّ مشروح مرفق بالمساهمة: الدالّة السحابيّة تنشره في
    // `lesson_transcripts` فور الاعتماد، فلا بدّ أن يراه المشرف **قبل**
    // ضغط «موافقة» — التطبيق يَعِد المساهم صراحةً بعرضه على المشرفين.
    val transcriptText: String = "",
    val transcriptBookTitle: String = "",
    val transcriptSourceRef: String = "",
    val transcriptImagePaths: List<String> = emptyList(),
    // ما أقرّ به المرسِل فعلاً — الإقرار اختياري في التطبيق، فتمييز
    // المُقِرّ من غيره هو كلّ فائدته.
    val rightsConfirmed: Boolean = false,
    val termsAccepted: Boolean = false,
    val contentPolicyVersion: String = "",
) {
    val isPending: Boolean get() = status == "pending"

    /** هل تحمل المساهمة نصّاً مشروحاً سيُنشر تلقائياً عند الاعتماد؟ */
    val hasTranscript: Boolean
        get() = transcriptText.isNotEmpty() || transcriptImagePaths.isNotEmpty()

    companion object {
        fun fromDoc(doc: DocumentSnapshot): LessonSubmission {
            val d = doc.dataMap()
            return LessonSubmission(
                id = doc.id,
                uid = str(d["uid"]),
                submitterName = str(d["submitterName"]),
                title = str(d["title"]),
                categoryId = str(d["categoryId"]),
                categoryName = str(d["categoryName"]),
                subcategoryId = str(d["subcategoryId"]),
                subcategoryName = str(d["subcategoryName"]),
                note = str(d["note"]),
                audioUrl = str(d["audioUrl"]),
                storagePath = str(d["storagePath"]),
                fileName = str(d["fileName"]),
                fileSize = int(d["fileSize"]),
                fcmToken = str(d["fcmToken"]),
                status = str(d["status"]).ifEmpty { "pending" },
                rejectReason = str(d["rejectReason"]),
                createdAtMs = parseDateMs(d["createdAt"]),
                transcriptText = str(d["transcriptText"]),
                transcriptBookTitle = str(d["transcriptBookTitle"]),
                transcriptSourceRef = str(d["transcriptSourceRef"]),
                transcriptImagePaths = (d["transcriptImagePaths"] as? List<*>)
                    .orEmpty().map { str(it) }.filter { it.isNotEmpty() },
                rightsConfirmed = d["rightsConfirmed"] == true,
                termsAccepted = d["termsAccepted"] == true,
                contentPolicyVersion = str(d["contentPolicyVersion"]),
            )
        }
    }
}

/** حصيلة قرار جماعي على مساهمات صوتية: كم نُفِّذ وكم أخفق. */
data class BulkSubmissionResult(val done: Int, val failed: Int)

/**
 * 🗳️ مراجعة مساهمات المستمعين: يوافق المشرف كما هي، أو يعدّل
 * (العنوان/الأقسام) ثم ينشر، أو يرفض بسبب يصل المساهم إشعاراً.
 * النشر الفعلي يتمّ في دالة سحابية واحدة (معاملة خادمية) فتُمنع الموافقات
 * المتزامنة من إنشاء درسين مكرّرين.
 */
object SubmissionsRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()
    const val COLLECTION = "lesson_submissions"

    /**
     * بثّ مباشر لكل الطلبات (المعلّقة أولاً ثم الأحدث قراراً).
     *
     * ⚠️ `flowOn(Default)` ليس ترفاً: Firestore يسلّم اللقطة على **الخيط
     * الرئيسي**، وهذه المجموعة تحمل حقولاً نصّية كبيرة (نصّ مشروح يبلغ عشرين
     * ألف حرف)، فتحليلها وفرزها كانا يقعان على خيط الواجهة عند كل انبعاث.
     * نظيرتها في `TranscriptsRepository` تحمله أصلاً — وسقط هنا وحده.
     */
    fun watchAll(): Flow<List<LessonSubmission>> =
        db.collection(COLLECTION).querySnapshots().map { snap ->
            snap.documents
                .map { LessonSubmission.fromDoc(it) }
                .sortedWith(
                    compareByDescending<LessonSubmission> { it.isPending }
                        .thenByDescending { it.createdAtMs },
                )
        }.flowOn(Dispatchers.Default)

    /** عدد الطلبات المعلّقة (شارة اللوحة). */
    fun watchPendingCount(): Flow<Int> =
        db.collection(COLLECTION).whereEqualTo("status", "pending")
            .querySnapshots().map { it.size() }
            .flowOn(Dispatchers.Default)

    /**
     * الموافقة والنشر. مرِّر عنواناً/قسمين معدَّلين ليُنشر بالتعديل
     * (status=approved_edited)، أو اتركها كما في الطلب (status=approved).
     */
    suspend fun approveAndPublish(
        s: LessonSubmission,
        editedTitle: String? = null,
        editedCategoryId: String? = null,
        editedCategoryName: String? = null,
        editedSubcategoryId: String? = null,
        editedSubcategoryName: String? = null,
    ) {
        val title = (editedTitle ?: s.title).trim()
        val categoryId = editedCategoryId ?: s.categoryId
        val subcategoryId = editedSubcategoryId ?: s.subcategoryId
        val edited = title != s.title.trim() ||
            categoryId != s.categoryId ||
            subcategoryId != s.subcategoryId

        require(title.isNotEmpty() && categoryId.isNotEmpty() && subcategoryId.isNotEmpty()) {
            "العنوان والقسمان مطلوبان قبل الموافقة."
        }
        functions.getHttpsCallable("approveSubmission").call(
            mapOf(
                "submissionId" to s.id,
                "title" to title,
                "categoryId" to categoryId,
                "categoryName" to (editedCategoryName ?: s.categoryName),
                "subcategoryId" to subcategoryId,
                "subcategoryName" to (editedSubcategoryName ?: s.subcategoryName),
                "edited" to edited,
            ),
        ).await()
    }

    /** الرفض بسبب (يصل المساهم نصّاً في الإشعار وشاشة «مساهماتي»). */
    suspend fun reject(s: LessonSubmission, reason: String) {
        functions.getHttpsCallable("rejectSubmission").call(
            mapOf("submissionId" to s.id, "reason" to reason.trim()),
        ).await()
    }

    /**
     * حذف طلب نهائياً (بعد قرار قديم) — يحذف ملف الصوت أيضاً إن كان
     * الطلب مرفوضاً (الملف غير مستعمل في أيّ درس منشور).
     */
    suspend fun deleteDecided(s: LessonSubmission) {
        if (s.isPending) return
        functions.getHttpsCallable("deleteSubmission")
            .call(mapOf("submissionId" to s.id)).await()
    }

    /**
     * نشر جماعي للمساهمات المحدَّدة كما هي. [onProgress] يتلقّى (المنجز،
     * الإجمالي) لتحريك شريط التقدّم كما في «اعتماد الكل» عند المالك.
     * كلّ مساهمة نداء مستقلّ فلا يُسقط فشلُ واحدة البقيّةَ.
     */
    suspend fun bulkApprove(
        items: List<LessonSubmission>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkSubmissionResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        targets.forEachIndexed { index, s ->
            runCatching { approveAndPublish(s) }
                .onSuccess { done++ }
                .onFailure { failed++ }
            onProgress(index + 1, targets.size)
        }
        return BulkSubmissionResult(done, failed)
    }

    /** رفض جماعي بسبب واحد يصل كل المساهمين المعنيّين. */
    suspend fun bulkReject(
        items: List<LessonSubmission>,
        reason: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkSubmissionResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        targets.forEachIndexed { index, s ->
            runCatching { reject(s, reason) }
                .onSuccess { done++ }
                .onFailure { failed++ }
            onProgress(index + 1, targets.size)
        }
        return BulkSubmissionResult(done, failed)
    }
}
