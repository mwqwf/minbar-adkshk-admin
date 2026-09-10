package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AdminAlert(
    val id: String,
    val email: String, // '' = عامّ لكل المشرفين.
    val excludeEmail: String,
    val title: String,
    val body: String,
    val type: String, // submission | transcript | support | owner_code | ...
    val refId: String,
    val createdAtMs: Long,
    val readBy: List<String>,
) {
    fun isReadBy(email: String): Boolean = readBy.contains(email.lowercase())

    companion object {
        fun fromJson(o: JSONObject): AdminAlert {
            val read = o.optJSONArray("readBy") ?: JSONArray()
            return AdminAlert(
                id = o.optString("id"),
                email = o.optString("email"),
                excludeEmail = o.optString("excludeEmail"),
                title = o.optString("title"),
                body = o.optString("body"),
                type = o.optString("type"),
                refId = o.optString("refId"),
                createdAtMs = o.optLong("createdAtMs"),
                readBy = (0 until read.length()).map { read.optString(it) },
            )
        }
    }
}

/**
 * 🔔 تنبيهات المشرفين — على `minbar-api` (قرار 2026-09-10): الخادم يسجّلها عند
 * كل فعلٍ يعني المشرفين (مساهمة جديدة، نصّ مقترَح، رسالة مستمع، طلب اعتماد)
 * ويدفعها إلى أجهزتهم بـFCM. الترشيح (عامّ/خاصّ/استثناء صاحب الفعل) على الخادم.
 * القراءة استطلاعٌ كل نصف دقيقة ما دامت الشاشة تجمع.
 */
object AdminAlertsFeed {
    private const val POLL_MS = 30_000L

    /** التنبيه يُعرض يوماً واحداً — الشاشة تعتمد هذا الحدّ أيضاً. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val myEmail: String
        get() = AuthService.currentUser?.email.orEmpty().trim().lowercase()

    fun stream(isOwner: Boolean): Flow<List<AdminAlert>> = flow {
        while (true) {
            emit(
                runCatching {
                    val items = MinbarAdminApi.get("/admin/alerts").optJSONArray("items") ?: JSONArray()
                    (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                        .map(AdminAlert::fromJson)
                        .sortedByDescending(AdminAlert::createdAtMs)
                }.getOrDefault(emptyList()),
            )
            delay(POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun markRead(alert: AdminAlert) {
        if (alert.isReadBy(myEmail)) return
        scope.launch { runCatching { MinbarAdminApi.post("/admin/alerts/${alert.id}/read") } }
    }

    fun markAllRead(alerts: List<AdminAlert>, onResult: (Boolean) -> Unit = {}) {
        val ids = alerts.map { it.id }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            onResult(true)
            return
        }
        scope.launch {
            val ok = runCatching {
                MinbarAdminApi.post("/admin/alerts/read-all", JSONObject().put("ids", JSONArray(ids)))
            }.isSuccess
            onResult(ok)
        }
    }

    suspend fun delete(alert: AdminAlert) {
        MinbarAdminApi.delete("/admin/alerts/${alert.id}")
    }

    /** المالك يحذف أيّ تنبيه؛ والمشرف يحذف ما كان موجَّهاً إليه وحده. */
    fun canDelete(alert: AdminAlert, isOwner: Boolean): Boolean =
        isOwner || alert.email.isNotEmpty()
}
