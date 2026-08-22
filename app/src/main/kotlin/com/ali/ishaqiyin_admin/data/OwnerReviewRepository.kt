package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** نتيجة فحص أمني خاصة بالمالك. المجموعة وقواعدها لا تُقرأ من المشرفين. */
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
        fun fromDoc(doc: DocumentSnapshot): SuspiciousLessonReview {
            val data = doc.dataMap()
            val rawSnapshot = data["lessonSnapshot"] ?: data["lesson"]

            @Suppress("UNCHECKED_CAST")
            val lesson = (rawSnapshot as? Map<String, Any?>) ?: emptyMap()

            fun text(key: String): String =
                str(data[key]).ifEmpty { str(lesson[key]) }

            fun reasonText(value: Any?): String {
                if (value is Map<*, *>) {
                    return str(value["message"] ?: value["label"] ?: value["code"])
                }
                return str(value)
            }

            val rawReasons = data["reasons"] ?: data["reasonCodes"]
            val reasons = (rawReasons as? Iterable<*>)
                ?.map(::reasonText)
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            return SuspiciousLessonReview(
                id = doc.id,
                lessonId = text("lessonId").ifEmpty { doc.id },
                title = text("title"),
                audioUrl = text("audioUrl"),
                categoryId = text("categoryId"),
                subcategoryId = text("subcategoryId"),
                addedBy = text("addedBy"),
                riskScore = (data["riskScore"] as? Number)?.toInt()
                    ?: (data["score"] as? Number)?.toInt() ?: 0,
                reasons = reasons,
                status = str(data["status"]).ifEmpty { "pending" },
                detectedAtMs = parseDateMs(data["detectedAt"] ?: data["createdAt"]),
            )
        }
    }
}

/** حصيلة الاعتماد الجماعي: كم مراجعة حُسمت، وكم منها لدرس لم يعد موجوداً. */
data class BulkVerifyResult(val verified: Int, val missingLessons: Int)

object OwnerReviewRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()

    fun watchPending(): Flow<List<SuspiciousLessonReview>> =
        // ترشيح خادميّ بدل جلب المجموعة كاملة ثمّ ترشيحها محليّاً.
        // `whereIn` لا `whereEqualTo("pending")`: isPending تشمل "flagged"
        // أيضاً، فقصرُها على "pending" كان يُسقط المراجعات المُعلَّمة صامتاً.
        // الفرز يبقى محليّاً فلا يلزم فهرس مركّب.
        db.collection("owner_lesson_reviews")
            .whereIn("status", listOf("pending", "flagged"))
            .querySnapshots().map { snapshot ->
            snapshot.documents
                .map { SuspiciousLessonReview.fromDoc(it) }
                .filter { it.isPending }
                .sortedWith(
                    compareByDescending<SuspiciousLessonReview> { it.riskScore }
                        .thenByDescending { it.detectedAtMs },
                )
        }

    /**
     * ⚠️ المفتاح الذي تعيده الدالة اسمه `suspicious`؛ قراءة `flagged` وحدها
     * كانت تُرجع صفراً دائماً فتقول الشاشة «لم تُكتشف نتائج» بعد كل فحص.
     */
    suspend fun scanAll(): Int {
        val result = functions.getHttpsCallable("scanSuspiciousLessons").call().await()
        val data = result.getData()
        if (data is Map<*, *>) {
            val value = data["suspicious"] ?: data["flagged"]
                ?: data["created"] ?: data["count"]
            return (value as? Number)?.toInt() ?: 0
        }
        return 0
    }

    /**
     * اعتماد جماعي لكل ما يعرضه المالك على الشاشة — نداء واحد لكل 400 عنصر
     * بدل نداء لكل درس. الحذف لا جماعيّ له بقصد: قرار لا رجعة فيه.
     * [onProgress] يتلقّى (المنجز، الإجمالي) لتحريك شريط التقدّم.
     */
    suspend fun bulkVerify(
        reviewIds: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkVerifyResult {
        val ids = reviewIds.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return BulkVerifyResult(0, 0)
        var verified = 0
        var missing = 0
        ids.chunked(400).forEach { chunk ->
            val result = functions.getHttpsCallable("bulkResolveSuspiciousLessons")
                .call(mapOf("action" to "verified", "reviewIds" to chunk))
                .await()
            val data = result.getData() as? Map<*, *>
            verified += (data?.get("verified") as? Number)?.toInt() ?: chunk.size
            missing += (data?.get("missingLessons") as? Number)?.toInt() ?: 0
            onProgress(verified, ids.size)
        }
        return BulkVerifyResult(verified, missing)
    }

    suspend fun resolve(review: SuspiciousLessonReview, action: String) {
        require(action == "verified" || action == "delete") { "إجراء غير معروف: $action" }
        functions.getHttpsCallable("resolveSuspiciousLesson").call(
            mapOf(
                "reviewId" to review.id,
                "lessonId" to review.lessonId,
                "action" to action,
            ),
        ).await()
    }
}
