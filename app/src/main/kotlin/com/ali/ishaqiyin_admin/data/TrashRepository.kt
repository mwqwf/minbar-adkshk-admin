package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class TrashedLesson(
    val id: String,
    val title: String,
    val categoryName: String,
    val subcategoryName: String,
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

    /** أيّامٌ منذ الحذف — الخادم لا يُتلف السلة تلقائياً، فلا عدّ تنازليّ يوهم بمهلة. */
    val daysAgo: Int
        get() = ((System.currentTimeMillis() - deletedAtMs) / 86_400_000L).coerceAtLeast(0L).toInt()
}

/**
 * 🗑️ السلة في `minbar-api`: الحذف ناعمٌ في D1 (`deleted_at_ms`)، والاستعادة
 * تُرجع الصفّ، والإتلاف يحذفه ويحذف صوته من R2 إن لم يشاركه درس آخر.
 * البثّ الحيّ صار استطلاعاً كل ثلث دقيقة ما دامت الشاشة مفتوحة.
 */
object TrashRepository {
    private const val POLL_MS = 20_000L
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    private suspend fun fetchAll(): List<TrashedLesson> {
        val json = MinbarAdminApi.get("/admin/trash")
        // الأقسام الحيّة لا تشمل المحذوفة: درسٌ حُذف مع قسمه كان يظهر بلا اسم قسم،
        // فتُكمَل الأسماء من أقسام السلة نفسها.
        fun org.json.JSONArray?.names(): Map<String, String> =
            if (this == null) emptyMap()
            else (0 until length()).mapNotNull { optJSONObject(it) }.associate { it.optString("id") to it.optString("name") }
        val categories = json.optJSONArray("categories").names() +
            runCatching { AdminRepository.fetchCategories() }.getOrDefault(emptyList())
                .associateBy({ it.id }, { it.name })
        val subcategories = json.optJSONArray("subcategories").names() +
            runCatching { AdminRepository.fetchSubcategories() }.getOrDefault(emptyList())
                .associateBy({ it.id }, { it.name })
        val transcripts = runCatching {
            val index = MinbarAdminApi.get("/v1/transcripts", auth = false).optJSONArray("items")
            (0 until (index?.length() ?: 0)).mapNotNull { index?.optJSONObject(it)?.optString("lessonId") }.toSet()
        }.getOrDefault(emptySet())
        val array = json.optJSONArray("lessons") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { row ->
            val deletedAt = row.optLong("deleted_at_ms", 0L)
            TrashedLesson(
                id = row.optString("id"),
                title = row.optString("title"),
                categoryName = categories[row.optString("category_id")].orEmpty(),
                subcategoryName = subcategories[row.optString("subcategory_id")].orEmpty(),
                categoryId = row.optString("category_id"),
                subcategoryId = row.optString("subcategory_id"),
                audioUrl = row.optString("audio_url"),
                hasTranscript = row.optString("id") in transcripts,
                deletedBy = "",
                deletedAtMs = deletedAt,
                purgeAfterMs = deletedAt + RETENTION_MS,
            )
        }.sortedByDescending { it.deletedAtMs }
    }

    fun watchAll(): Flow<List<TrashedLesson>> = flow {
        while (true) {
            // ⚠️ بلا `runCatching` كان أيّ خطأ شبكة أثناء الاستطلاع يُرمى داخل
            // `collectAsState` في الشاشة فينهار التطبيق (بخلاف بقيّة المستودعات).
            // عند الفشل تبقى آخر قائمة معروضة: إرسال قائمة فارغة كان يُظهر
            // «السلة فارغة» كاذبةً مع كلّ انقطاع عابر.
            runCatching { fetchAll() }.onSuccess { emit(it) }
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun watchCount(): Flow<Int> = flow {
        emit(runCatching { fetchAll().size }.getOrDefault(0))
    }

    /** يعيد عدد ما استُعيد (الدرس وما أُرجع معه من أقسامه المحذوفة). */
    suspend fun restore(item: TrashedLesson): Int =
        MinbarAdminApi.post("/admin/lessons/${item.id}/restore").optInt("restored", 1)

    suspend fun purge(item: TrashedLesson) {
        MinbarAdminApi.post("/admin/lessons/${item.id}/purge")
    }

    suspend fun emptyAll(): Int =
        MinbarAdminApi.post("/admin/trash/empty").optInt("purged", 0)
}
