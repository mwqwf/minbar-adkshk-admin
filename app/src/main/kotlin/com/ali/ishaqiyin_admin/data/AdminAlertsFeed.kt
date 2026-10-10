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
import kotlinx.coroutines.withContext
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
    private const val READ_ALL_CHUNK = 90

    /** التنبيه يُعرض يوماً واحداً — الشاشة تعتمد هذا الحدّ أيضاً. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val myEmail: String
        get() = AuthService.currentUser?.email.orEmpty().trim().lowercase()

    /**
     * غير المقروء عندي وحده — الخادم يعيد تنبيهات اليوم كلّها مع `readBy`.
     *
     * ⚠️ كان التدفّق يبثّ المقروء أيضاً فتعدّه شارة الجرس في اللوحة (`alerts.size`)
     * ولا تنطفئ بعد القراءة طوال يومٍ كامل، خلافاً لعقد الشاشة («المقروء يختفي
     * عن صاحبه» — اللقطة تُبقيه معروضاً خلال الزيارة وحدها).
     * ⚠️ وفشل الشبكة كان يبثّ قائمةً فارغة: تُطفأ الشارة وتعلن الشاشة «لا
     * تنبيهات — كلّ شيء تحت السيطرة» وهي لم تعرف شيئاً. الفشل الآن لا يبثّ،
     * فتبقى آخر قائمة صحيحة (أو مؤشّر التحميل) حتى تنجح الجولة التالية.
     */
    fun stream(isOwner: Boolean): Flow<List<AdminAlert>> = flow {
        while (true) {
            val fetched = runCatching {
                val me = myEmail
                val items = MinbarAdminApi.get("/admin/alerts").optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .map(AdminAlert::fromJson)
                    .filter { !it.isReadBy(me) }
                    .sortedByDescending(AdminAlert::createdAtMs)
            }.getOrNull()
            if (fetched != null) emit(fetched)
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
            // ⛔ الخادم يقصّ القائمة عند 90 معرّفاً (حدّ متغيّرات D1)، فما زاد كان
            // يبقى غير مقروء بصمت رغم رسالة النجاح — دفعاتٌ من 90.
            val ok = ids.chunked(READ_ALL_CHUNK).all { part ->
                runCatching {
                    MinbarAdminApi.post("/admin/alerts/read-all", JSONObject().put("ids", JSONArray(part)))
                }.isSuccess
            }
            // ⚠️ النتيجة على الخيط الرئيسيّ: المستدعي يكتب حالة Compose ويعرض رسالة.
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    suspend fun delete(alert: AdminAlert) {
        MinbarAdminApi.delete("/admin/alerts/${alert.id}")
    }

    /** المالك يحذف أيّ تنبيه؛ والمشرف يحذف ما كان موجَّهاً إليه وحده. */
    fun canDelete(alert: AdminAlert, isOwner: Boolean): Boolean =
        isOwner || alert.email.isNotEmpty()
}
