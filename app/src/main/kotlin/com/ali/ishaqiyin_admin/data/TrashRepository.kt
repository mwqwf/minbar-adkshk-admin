package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** درس في سلة المحذوفات — قابل للاستعادة حتى انقضاء مهلته. */
data class TrashedLesson(
    val id: String,
    val title: String,
    val categoryName: String,
    val subcategoryName: String,
    /**
     * معرّفا القسمين كما في الوثيقة المحذوفة — تُحلّ بهما الأسماء محليّاً
     * للدروس القديمة التي كُتبت بلا `categoryName/subcategoryName`.
     */
    val categoryId: String,
    val subcategoryId: String,
    val audioUrl: String,
    val hasTranscript: Boolean,
    val deletedBy: String,
    val deletedAtMs: Long,
    val purgeAfterMs: Long,
) {
    val daysLeft: Int
        get() = (((purgeAfterMs - System.currentTimeMillis()) / 86_400_000L) + 1)
            .coerceAtLeast(0L).toInt()

    companion object {
        fun fromDoc(doc: DocumentSnapshot): TrashedLesson {
            val d = doc.dataMap()
            @Suppress("UNCHECKED_CAST")
            val lesson = (d["lesson"] as? Map<String, Any?>).orEmpty()
            // وثائق قديمة قد تلفّ حقولها في data — نفكّها كما يفعل الخادم.
            @Suppress("UNCHECKED_CAST")
            val unwrapped = (lesson["data"] as? Map<String, Any?>)?.let { it + lesson } ?: lesson
            return TrashedLesson(
                id = doc.id,
                title = str(unwrapped["title"]).ifEmpty { str(unwrapped["name"]) },
                categoryName = str(unwrapped["categoryName"]),
                subcategoryName = str(unwrapped["subcategoryName"]),
                categoryId = str(unwrapped["categoryId"]),
                subcategoryId = str(unwrapped["subcategoryId"]).ifEmpty {
                    str((unwrapped["subcategory"] as? Map<*, *>)?.get("_id"))
                },
                audioUrl = str(unwrapped["audioUrl"]),
                hasTranscript = d["transcript"] is Map<*, *>,
                deletedBy = str(d["deletedBy"]),
                deletedAtMs = (d["deletedAtMs"] as? Number)?.toLong() ?: 0L,
                purgeAfterMs = (d["purgeAfterMs"] as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * 🗑️ سلة المحذوفات: الحذف في اللوحة صار نقلاً إلى هنا (30 يوماً)، ومن هنا
 * يستعيد المشرف الدرس بنقرة (بوثيقته ونصّه المشروح وملفه الصوتي كما كانت)
 * أو يحذفه نهائياً. ما تجاوز مهلته يُنظَّف تلقائياً كل ليلة.
 */
object TrashRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()
    const val COLLECTION = "deleted_lessons"

    // فكّ الترميز والفرز خارج الخيط الرئيسي: وثيقة كل درس محذوف تحمل نسخته
    // كاملةً بنصّه المشروح، وكانت تُفكّ في سياق الجامع (Main) فتُقطّع الحركة.
    fun watchAll(): Flow<List<TrashedLesson>> =
        db.collection(COLLECTION).querySnapshots().map { snap ->
            snap.documents
                .map { TrashedLesson.fromDoc(it) }
                .sortedByDescending { it.deletedAtMs }
        }.flowOn(Dispatchers.Default)

    /**
     * عدد ما في السلة — استعلام **عدّ خادميّ** خفيف لا يجلب الوثائق.
     *
     * كان مستمع لقطات على المجموعة كاملة يقرأ كلّ درس محذوف (بوثيقته ونصّه)
     * لعرض رقم واحد على بطاقة اللوحة. تدفّق بقيمة واحدة: يُنفَّذ عند فتح
     * الشاشة، ويُعاد تنفيذه عند العودة إليها لأنّ التدفّق يُنشأ من جديد.
     */
    fun watchCount(): Flow<Int> = flow {
        emit(
            runCatching {
                db.collection(COLLECTION).count().get(AggregateSource.SERVER).await().count.toInt()
            }.getOrDefault(0),
        )
    }

    suspend fun restore(item: TrashedLesson) {
        functions.getHttpsCallable("restoreDeletedLesson")
            .call(mapOf("lessonId" to item.id)).await()
    }

    suspend fun purge(item: TrashedLesson) {
        functions.getHttpsCallable("purgeDeletedLesson")
            .call(mapOf("lessonId" to item.id)).await()
    }

    /** تفريغ السلة كاملةً — الخادم يقصرها على المالك حصراً. */
    suspend fun emptyAll(): Int {
        val result = functions.getHttpsCallable("emptyTrash").call().await()
        return ((result.data as? Map<*, *>)?.get("purged") as? Number)?.toInt() ?: 0
    }
}
