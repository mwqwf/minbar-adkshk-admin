package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

data class SuspiciousLessonReview(
    val id: String,
    val lessonId: String,
    val title: String,
    val audioUrl: String,
    val categoryId: String,
    val subcategoryId: String,
    val addedBy: String,
    val riskScore: Int,
    val reasons: List<String>,
    val status: String,
    val detectedAtMs: Long,
) {
    val isPending: Boolean get() = status == "pending" || status == "flagged"

    companion object {
        fun fromJson(o: JSONObject): SuspiciousLessonReview {
            val reasons = o.optJSONArray("reasons") ?: JSONArray()
            return SuspiciousLessonReview(
                id = o.optString("id"),
                lessonId = o.optString("lessonId"),
                title = o.optString("title"),
                audioUrl = o.optString("audioUrl"),
                categoryId = o.optString("categoryId"),
                subcategoryId = o.optString("subcategoryId"),
                addedBy = o.optString("addedBy"),
                riskScore = o.optInt("riskScore"),
                reasons = (0 until reasons.length()).map { reasons.optString(it) }.filter { it.isNotEmpty() },
                status = o.optString("status").ifEmpty { "pending" },
                detectedAtMs = o.optLong("detectedAtMs"),
            )
        }
    }
}

data class BulkVerifyResult(val verified: Int, val missingLessons: Int)

/**
 * 🕵️ فحص الشبهات — على `minbar-api` (قرار 2026-09-10).
 *
 * القواعد صارت أدقّ ممّا كانت: هويّة المحتوى (SHA-256) تكشف الصوت المكرَّر
 * قطعاً لا ظنّاً، والفحص يسأل التخزين نفسه عن وجود الملفّ. النتائج تُخزَّن في
 * D1 ويُغلَق تلقائياً ما زالت أسبابه.
 */
object OwnerReviewRepository {
    private const val POLL_MS = 60_000L

    /**
     * نبضة «أعد الجلب الآن» بعد كل قرار أو فحص: كانت الشاشة تُبقي الدرس المحسوم
     * بأزراره دقيقةً كاملة (دورة الاستطلاع)، ونتائج «فحص شامل» لا تظهر حتى حينها.
     */
    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun refresh() {
        refreshTick.value += 1
    }

    fun watchPending(): Flow<List<SuspiciousLessonReview>> = flow {
        while (true) {
            val tick = refreshTick.value
            // الفشل العابر لا يُرسل قائمة فارغة («لا شبهات») والشبهات قائمة.
            runCatching {
                val items = MinbarAdminApi.get("/admin/suspicions").optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .map(SuspiciousLessonReview::fromJson)
            }.onSuccess { emit(it) }
            kotlinx.coroutines.withTimeoutOrNull(POLL_MS) {
                refreshTick.first { it != tick }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** يعيد الفحص كاملاً ويُرجع عدد الدروس المشبوهة. */
    suspend fun scanAll(): Int =
        MinbarAdminApi.post("/admin/suspicions/scan").optInt("suspicious", 0).also { refresh() }

    suspend fun bulkVerify(
        reviewIds: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkVerifyResult {
        val ids = reviewIds.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return BulkVerifyResult(0, 0)
        var verified = 0
        var missing = 0
        ids.chunked(200).forEach { chunk ->
            val result = MinbarAdminApi.post(
                "/admin/suspicions/resolve",
                JSONObject().put("action", "verified").put("ids", JSONArray(chunk)),
            )
            verified += result.optInt("verified", chunk.size)
            missing += result.optInt("missingLessons", 0)
            onProgress(verified, ids.size)
        }
        refresh()
        return BulkVerifyResult(verified, missing)
    }

    suspend fun resolve(review: SuspiciousLessonReview, action: String) {
        require(action == "verified" || action == "delete") { "إجراء غير معروف: $action" }
        MinbarAdminApi.post(
            "/admin/suspicions/resolve",
            JSONObject().put("action", action).put("ids", JSONArray(listOf(review.id))),
        )
        refresh()
    }
}
