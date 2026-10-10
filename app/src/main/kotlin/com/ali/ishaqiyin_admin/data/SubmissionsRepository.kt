package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

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
    val transcriptText: String = "",
    val transcriptBookTitle: String = "",
    val transcriptSourceRef: String = "",
    val transcriptImagePaths: List<String> = emptyList(),
    val rightsConfirmed: Boolean = false,
    val termsAccepted: Boolean = false,
    val contentPolicyVersion: String = "",
) {
    val isPending: Boolean get() = status == "pending"
    val hasTranscript: Boolean
        get() = transcriptText.isNotEmpty() || transcriptImagePaths.isNotEmpty()

    companion object {
        /** صفّ `submissions` من minbar-api (snake_case) → النموذج. `uid` = معرّف الجهاز. */
        fun fromRow(row: JSONObject): LessonSubmission {
            val keys = runCatching { JSONArray(row.optString("transcript_image_keys_json", "[]")) }
                .getOrDefault(JSONArray())
            return LessonSubmission(
                id = row.optString("id"),
                uid = row.optString("device_id"),
                submitterName = row.optString("submitter_name"),
                title = row.optString("title"),
                categoryId = row.optString("category_id"),
                categoryName = row.optString("category_name"),
                subcategoryId = row.optString("subcategory_id"),
                subcategoryName = row.optString("subcategory_name"),
                note = row.optString("note"),
                audioUrl = row.optString("audio_url"),
                storagePath = row.optString("audio_key"),
                fileName = row.optString("file_name"),
                fileSize = row.optInt("file_size"),
                fcmToken = row.optString("fcm_token"),
                status = row.optString("status").ifEmpty { "pending" },
                rejectReason = row.optString("reject_reason"),
                createdAtMs = row.optLong("created_at_ms"),
                transcriptText = row.optString("transcript_text"),
                transcriptBookTitle = row.optString("transcript_book_title"),
                transcriptSourceRef = row.optString("transcript_source_ref"),
                transcriptImagePaths = (0 until keys.length()).map { keys.optString(it) }.filter { it.isNotEmpty() },
                rightsConfirmed = row.optInt("rights_confirmed") == 1,
                termsAccepted = row.optInt("rights_confirmed") == 1,
                contentPolicyVersion = row.optString("policy_version"),
            )
        }
    }
}

data class BulkSubmissionResult(val done: Int, val failed: Int)

/**
 * مراجعة «شارك درساً» — على `minbar-api` (قرار 2026-09-10). الموافقة تُنشئ
 * درساً معلّقاً من ملفّ المساهمة وتشغّل خطّ الترميز، وتُخطر صاحبها بـFCM من
 * الخادم. القوائم استطلاعٌ كل نصف دقيقة بدل مستمعي Firestore.
 */
object SubmissionsRepository {
    private const val POLL_MS = 30_000L
    private const val DECIDED_PAGE = 50
    /** سقف الخادم لـ`limit` في `/admin/submissions`. */
    private const val MAX_LIMIT = 500
    private val decidedLimit = kotlinx.coroutines.flow.MutableStateFlow(DECIDED_PAGE)
    val hasMoreDecided = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * نبضة «أعد الجلب الآن»: بعد كل حسمٍ أو «تحميل المزيد». كانت القائمة لا
     * تتغيّر حتى الاستطلاع التالي (نصف دقيقة)، فتبقى المساهمة المحسومة معلّقةً
     * بأزرارها ويضغطها المشرف ثانيةً فيتلقّى «بُتّ فيها من قبل».
     */
    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun refresh() {
        refreshTick.value += 1
    }

    fun loadMoreDecided() {
        decidedLimit.value += DECIDED_PAGE
        refresh()
    }

    /** آخر عدد معلَّق رآه الجلب — ليتّسع الحدّ له فلا يُقصّ المعلَّق. */
    @Volatile
    private var lastPendingCount = 0

    private suspend fun fetchPage(limit: Int): List<LessonSubmission> {
        val items = MinbarAdminApi.get("/admin/submissions?status=all&limit=$limit").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map(LessonSubmission::fromRow)
    }

    private suspend fun fetchAll(limit: Int): List<LessonSubmission> {
        // ⛔ الحدّ عند الخادم يشمل المعلَّق (يأتي أوّلاً): كان `limit + 1` وحده،
        // فإن زاد المعلَّق على خمسين اختفى ما بعدها بلا زرّ «المزيد» — مساهماتٌ
        // لا يراها أحد. فالحدّ = المعلَّق المعروف + نافذة المحسوم، ويُعاد الجلب
        // بالسقف إن امتلأت الصفحة معلَّقاً.
        var request = (lastPendingCount + limit + 1).coerceAtMost(MAX_LIMIT)
        var list = fetchPage(request)
        if (list.size >= request && request < MAX_LIMIT && list.count { !it.isPending } <= limit) {
            request = MAX_LIMIT
            list = fetchPage(request)
        }
        val pending = list.filter { it.isPending }
        lastPendingCount = pending.size
        val decided = list.filter { !it.isPending }
        hasMoreDecided.value = decided.size > limit
        return (pending + decided.take(limit))
            .sortedWith(compareByDescending<LessonSubmission> { it.isPending }.thenByDescending { it.createdAtMs })
    }

    fun watchAll(): Flow<List<LessonSubmission>> = flow {
        while (true) {
            val tick = refreshTick.value
            // ⚠️ الفشل لا يُرسل قائمةً فارغة: كانت انقطاعةٌ عابرة تمحو الشاشة
            // فتقول «لا مساهمات» والمعلَّق قائم. تبقى آخر قائمة ويُعاد في الدورة التالية.
            runCatching { fetchAll(decidedLimit.value) }.onSuccess { emit(it) }
            withTimeoutOrNull(POLL_MS) { refreshTick.first { it != tick } }
        }
    }.flowOn(Dispatchers.IO)

    fun watchPendingCount(): Flow<Int> = flow {
        while (true) {
            emit(runCatching { MinbarAdminApi.get("/admin/community/counts").optInt("pendingSubmissions", 0) }.getOrDefault(0))
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun approveAndPublish(
        s: LessonSubmission,
        editedTitle: String? = null,
        editedCategoryId: String? = null,
        editedCategoryName: String? = null,
        editedSubcategoryId: String? = null,
        editedSubcategoryName: String? = null,
        refreshAfter: Boolean = true,
    ) {
        val title = (editedTitle ?: s.title).trim()
        val categoryId = editedCategoryId ?: s.categoryId
        val subcategoryId = editedSubcategoryId ?: s.subcategoryId
        val edited = title != s.title.trim() || categoryId != s.categoryId || subcategoryId != s.subcategoryId
        require(title.isNotEmpty() && categoryId.isNotEmpty() && subcategoryId.isNotEmpty()) {
            "العنوان والقسمان مطلوبان قبل الموافقة."
        }
        MinbarAdminApi.post(
            "/admin/submissions/${s.id}/approve",
            JSONObject()
                .put("title", title)
                .put("categoryId", categoryId)
                .put("categoryName", editedCategoryName ?: s.categoryName)
                .put("subcategoryId", subcategoryId)
                .put("subcategoryName", editedSubcategoryName ?: s.subcategoryName)
                .put("edited", edited),
        )
        if (refreshAfter) refresh()
    }

    suspend fun reject(s: LessonSubmission, reason: String, refreshAfter: Boolean = true) {
        MinbarAdminApi.post("/admin/submissions/${s.id}/reject", JSONObject().put("reason", reason.trim()))
        if (refreshAfter) refresh()
    }

    suspend fun deleteDecided(s: LessonSubmission) {
        if (s.isPending) return
        MinbarAdminApi.delete("/admin/submissions/${s.id}")
        refresh()
    }

    suspend fun bulkApprove(
        items: List<LessonSubmission>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkSubmissionResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        targets.forEachIndexed { index, s ->
            runCatching { approveAndPublish(s, refreshAfter = false) }.onSuccess { done++ }.onFailure { failed++ }
            onProgress(index + 1, targets.size)
        }
        // جلبٌ واحد بعد الدفعة لا جلبٌ لكلّ عنصر.
        refresh()
        return BulkSubmissionResult(done, failed)
    }

    suspend fun bulkReject(
        items: List<LessonSubmission>,
        reason: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkSubmissionResult {
        val targets = items.filter { it.isPending }
        var done = 0
        var failed = 0
        targets.forEachIndexed { index, s ->
            runCatching { reject(s, reason, refreshAfter = false) }.onSuccess { done++ }.onFailure { failed++ }
            onProgress(index + 1, targets.size)
        }
        // جلبٌ واحد بعد الدفعة لا جلبٌ لكلّ عنصر.
        refresh()
        return BulkSubmissionResult(done, failed)
    }
}
