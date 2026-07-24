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

object OwnerReviewRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()

    fun watchPending(): Flow<List<SuspiciousLessonReview>> =
        db.collection("owner_lesson_reviews").querySnapshots().map { snapshot ->
            snapshot.documents
                .map { SuspiciousLessonReview.fromDoc(it) }
                .filter { it.isPending }
                .sortedWith(
                    compareByDescending<SuspiciousLessonReview> { it.riskScore }
                        .thenByDescending { it.detectedAtMs },
                )
        }

    suspend fun scanAll(): Int {
        val result = functions.getHttpsCallable("scanSuspiciousLessons").call().await()
        val data = result.getData()
        if (data is Map<*, *>) {
            val value = data["flagged"] ?: data["created"] ?: data["count"]
            return (value as? Number)?.toInt() ?: 0
        }
        return 0
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
