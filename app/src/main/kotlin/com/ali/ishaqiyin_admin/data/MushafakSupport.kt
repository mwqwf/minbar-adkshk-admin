package com.ali.ishaqiyin_admin.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 📬 **رسائلُ مستخدمي «مصحفك» تُقرأ من لوحة منبر** (أمر المالك 2026-09-10).
 *
 * **لماذا هنا لا في تطبيق مصحفك؟** لأنّ المالك يفتح هذه اللوحة كلَّ يومٍ لعمله، ولا يفتح تطبيقَ
 * مصحفك بصفة مطوّر. ⇒ الرسائلُ تأتي إلى حيث هو، لا يذهب هو إليها.
 *
 * ⛔ **ولا يراها مشرفو منبر بحال** — حارسان لا واحد:
 * 1. **الواجهة**: الصفُّ والشاشة لا يُركَّبان إلا لـ`AuthService.isOwnerEmail`.
 * 2. **الخادم**: `mushafak-api` لا يُجيب إلا بترويسة `x-owner-key` الصحيحة — ومشرفٌ فتح الشاشةَ
 *    بحيلةٍ لا يجد مفتاحاً في جهازه فلا يرى حرفاً. ⇒ **الحارسُ الحقيقيّ في الخادم، والواجهةُ
 *    لطفٌ بالعين لا سياجُ أمن.**
 *
 * ⛔ **والمفتاحُ لا يُكتب في الشيفرة أبداً**: يُدخله المالكُ مرّةً في جهازه فيُحفظ محلّياً. ولو
 * وُضع في المستودع لصار في يد كلِّ من يقرأ الشيفرة أو يفكّ الحزمة.
 */
object MushafakSupport {

    private const val BASE = "https://mushafak-api.mushafak.workers.dev"
    private const val PREFS = "mushafak_owner"
    private const val KEY = "owner_key"

    data class Ticket(
        val id: String,
        val kind: String,
        val body: String,
        val status: String,
        val updatedAt: Long,
        val context: String,
    ) {
        /** وصفٌ عربيٌّ للصنف — أصنافُ مصحفك الأربعة كما ترسلها الشاشةُ هناك. */
        val kindLabel: String
            get() = when (kind) {
                "bug" -> "عطب"
                "feature" -> "اقتراح"
                "content" -> "مشكلة محتوى"
                else -> "ملاحظة"
            }
    }

    fun key(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun setKey(context: Context, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, value?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    /** `null` = مفتاحٌ مرفوضٌ أو شبكةٌ متعذّرة (‏والشاشةُ تفرّق بينهما برسالتها). */
    suspend fun tickets(context: Context): List<Ticket>? = withContext(Dispatchers.IO) {
        val k = key(context) ?: return@withContext null
        val raw = call("GET", "/v1/support/all", k, null) ?: return@withContext null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        if (root.has("error")) return@withContext null
        val arr = root.optJSONArray("tickets") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Ticket(
                id = t.optString("id"),
                kind = t.optString("kind"),
                body = t.optString("body"),
                status = t.optString("status"),
                updatedAt = t.optLong("updated_at"),
                context = t.optString("context_json"),
            )
        }
    }

    /** ردُّ المالك — يظهر للمستخدم **داخل تطبيقه** لا في بريدٍ ولا رسالةٍ خارجية. */
    suspend fun reply(context: Context, ticketId: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val k = key(context) ?: return@withContext false
        val payload = JSONObject().put("body", body.take(4000)).toString()
        val raw = call("POST", "/v1/support/tickets/$ticketId/messages", k, payload) ?: return@withContext false
        runCatching { !JSONObject(raw).has("error") }.getOrDefault(false)
    }

    /** يتحقّق من مفتاحٍ **قبل حفظه**: مفتاحٌ خاطئٌ يُحفظ يعني صندوقاً فارغاً بلا سببٍ ظاهر. */
    suspend fun verify(key: String): Boolean = withContext(Dispatchers.IO) {
        val raw = call("GET", "/v1/support/all", key, null) ?: return@withContext false
        runCatching { !JSONObject(raw).has("error") }.getOrDefault(false)
    }

    private fun call(method: String, path: String, ownerKey: String, body: String?): String? = runCatching {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("x-owner-key", ownerKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json; charset=utf-8")
            }
        }
        body?.let { c.outputStream.use { s -> s.write(it.toByteArray()) } }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (ok) text else null
    }.getOrNull()
}
