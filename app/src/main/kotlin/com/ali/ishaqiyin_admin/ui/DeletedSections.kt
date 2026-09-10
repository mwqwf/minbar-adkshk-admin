package com.ali.ishaqiyin_admin.ui

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/** قسمٌ (رئيسي أو فرعي) في السلة — يُستعاد أو يُتلَف نهائياً. */
data class TrashedSection(
    /** معرّف مركّب للعرض (النوع + معرّف القسم). */
    val entryId: String,
    /** "category" أو "subcategory". */
    val kind: String,
    val docId: String,
    val name: String,
    val parentCategoryId: String,
    val deletedBy: String,
    val deletedAtMs: Long,
    val purgeAfterMs: Long,
) {
    val isCategory: Boolean get() = kind == "category"

    val daysLeft: Int
        get() = (((purgeAfterMs - System.currentTimeMillis()) / 86_400_000L) + 1)
            .coerceAtLeast(0L).toInt()
}

/**
 * 🗑️ أقسام السلة — على `minbar-api` (قرار 2026-09-10). الحذف ناعمٌ في D1
 * ومتسلسل (القسم يجرّ فرعياته ودروسها)، والاستعادة تُرجع الصفّ نفسه.
 */
object DeletedSectionsRepository {
    private const val POLL_MS = 20_000L
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    private fun JSONArray?.rows(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun fetchAll(): List<TrashedSection> {
        val json = MinbarAdminApi.get("/admin/trash")
        val categories = json.optJSONArray("categories").rows()
        val subcategories = json.optJSONArray("subcategories").rows()
        val out = categories.map { row ->
            TrashedSection(
                entryId = "category_" + row.optString("id"), kind = "category",
                docId = row.optString("id"), name = row.optString("name"), parentCategoryId = "",
                deletedBy = "", deletedAtMs = row.optLong("deleted_at_ms"),
                purgeAfterMs = row.optLong("deleted_at_ms") + RETENTION_MS,
            )
        } + subcategories.map { row ->
            TrashedSection(
                entryId = "subcategory_" + row.optString("id"), kind = "subcategory",
                docId = row.optString("id"), name = row.optString("name"),
                parentCategoryId = row.optString("category_id"),
                deletedBy = "", deletedAtMs = row.optLong("deleted_at_ms"),
                purgeAfterMs = row.optLong("deleted_at_ms") + RETENTION_MS,
            )
        }
        return out.sortedByDescending { it.deletedAtMs }
    }

    fun watchAll(): Flow<List<TrashedSection>> = flow {
        while (true) {
            emit(runCatching { fetchAll() }.getOrDefault(emptyList()))
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun table(item: TrashedSection) =
        if (item.kind == "category") "categories" else "subcategories"

    suspend fun restore(item: TrashedSection) {
        MinbarAdminApi.post("/admin/${table(item)}/${item.docId}/restore")
    }

    suspend fun purge(item: TrashedSection) {
        MinbarAdminApi.post("/admin/${table(item)}/${item.docId}/purge")
    }
}
