package com.ali.ishaqiyin_admin.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
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
 * 2. **الخادم**: `mushafak-api` يتحقّق من **بريد حامل الرمز** ويقارنه ببريد المالك — ومشرفٌ فتح
 *    الشاشةَ بحيلةٍ يمرّر رمزَ حسابه هو فيُردّ. ⇒ **الحارسُ الحقيقيّ في الخادم، والواجهةُ لطفٌ
 *    بالعين لا سياجُ أمن.**
 *
 * ⛔ **ولا رمزَ يُطلب منه** (تصحيحُ المالك 2026-09-10: «لا داعي لأن يُطلب مني رمزٌ بما أني
 * المالك — قضيّةُ هذا الرمز عبثٌ فقط»). **وهو محقّ**: اللوحةُ تعرف صاحبَها بحسابه من Firebase،
 * فسؤالُه عن سرٍّ ثانٍ إثباتٌ لما ثبت. ⇒ يُمرَّر **رمزُ هويّة Firebase** نفسُه إلى الخادم فيتحقّق
 * منه بمفاتيح جوجل العلنيّة ويقارن البريدَ ببريد المالك.
 *
 * ⭐ **وهو أقوى من المفتاح الثابت لا أضعف**: عمرُه ساعةٌ ويجدّده Firebase من نفسه، ولا سرَّ
 * يُخزَّن في جهازٍ ولا في شيفرة — والمفتاحُ الثابت يبقى صالحاً أبداً ولا يُبطله إلا تبديلُه يدوياً.
 */
object MushafakSupport {

    private const val BASE = "https://mushafak-api.mushafak.workers.dev"
    /** ⏱️ رمزُ الهويّة يُجلب عند الحاجة — والمكتبةُ تُعيد المكاشَ ما لم يشارف الانتهاء. */
    private suspend fun idToken(): String? = runCatching {
        val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching null
        user.getIdToken(false).await().token
    }.getOrNull()

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

    /** `null` = رمزٌ مرفوضٌ أو شبكةٌ متعذّرة. */
    suspend fun tickets(context: Context): List<Ticket>? = withContext(Dispatchers.IO) {
        val k = idToken() ?: return@withContext null
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
        val k = idToken() ?: return@withContext false
        val payload = JSONObject().put("body", body.take(4000)).toString()
        val raw = call("POST", "/v1/support/tickets/$ticketId/messages", k, payload) ?: return@withContext false
        runCatching { !JSONObject(raw).has("error") }.getOrDefault(false)
    }

    private fun call(method: String, path: String, idToken: String, body: String?): String? = runCatching {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            // 🔐 هويّةُ المالك لا سرَّه — يتحقّق منها الخادمُ بمفاتيح جوجل العلنيّة.
            setRequestProperty("x-firebase-token", idToken)
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
