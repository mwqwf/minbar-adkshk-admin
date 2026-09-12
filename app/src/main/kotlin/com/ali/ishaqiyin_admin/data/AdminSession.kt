package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 🔑 «جلسة منبر» (2026-09-12) — هوية اللوحة الدائمة بلا Firebase.
 *
 * الخادم (`minbar-api`) يُصدر رمز جلسة طويل العمر مقابل رمز Firebase أو Google
 * الحالي (`POST /admin/session/exchange`) ويخزّنه مجزّأً. هنا يُحفظ الرمز في
 * تفضيلات خاصّة بالتطبيق (كما يحفظ Firebase رمز تجديده تماماً) ويُرسل `Bearer`
 * في كل نداء إداري.
 *
 * **شرط المالك**: لا يخرج أي مشرف من حسابه. لذلك الانتقال صامت: أوّل إقلاع بعد
 * التحديث يجد جلسة Firebase القائمة فيبدّلها بجلسة منبر خلف الكواليس، ولا تُعرض
 * شاشة دخول إلا لجهاز جديد لم يدخل قطّ.
 */
object AdminSession {
    private const val TAG = "AdminSession"
    private const val FILE = "minbar_admin_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_PHOTO = "photo"
    private const val KEY_ROLE = "role"

    private lateinit var appContext: Context
    private val prefs get() = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** يتغيّر عند كل حفظ/مسح — تراقبه بوّابة الدخول لتعيد التحقق. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    val email: String get() = prefs.getString(KEY_EMAIL, "").orEmpty()
    val displayName: String get() = prefs.getString(KEY_NAME, "").orEmpty()
    val photoUrl: String get() = prefs.getString(KEY_PHOTO, "").orEmpty()
    val role: String get() = prefs.getString(KEY_ROLE, "").orEmpty()
    val isActive: Boolean get() = token != null && email.isNotBlank()

    private fun save(token: String, email: String, role: String, name: String, photo: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email.trim().lowercase())
            .putString(KEY_ROLE, role)
            .putString(KEY_NAME, name)
            .putString(KEY_PHOTO, photo)
            .apply()
        _version.value++
    }

    fun clear() {
        if (!isActive) return
        prefs.edit().clear().apply()
        _version.value++
    }

    private fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    /**
     * يبدّل رمز هوية (Firebase أو Google ID) برمز جلسة. يعيد `true` عند النجاح.
     * 401 يعني أن البريد ليس في قائمة المشرفين — ليس عطباً، فيُترك مسار رمز
     * الاعتماد يعمل كما هو.
     */
    suspend fun exchange(identityBearer: String, name: String = "", photo: String = ""): Boolean {
        return try {
            val res = MinbarAdminApi.post(
                "/admin/session/exchange",
                JSONObject().put("device", deviceLabel()),
                bearerOverride = "Bearer $identityBearer",
            )
            val tok = res.optString("token")
            val who = res.optString("who")
            if (tok.isBlank() || !who.contains("@")) return false
            save(tok, who, res.optString("role"), name, photo)
            Log.i(TAG, "session issued for ${who.substringBefore('@')}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MinbarAdminApi.ApiException) {
            Log.d(TAG, "exchange refused: ${e.code}")
            false
        } catch (e: Exception) {
            Log.d(TAG, "exchange failed: $e")
            false
        }
    }

    /** يعتمد جلسةً أصدرها الخادم مقابل رمز ربط/دعوة (بلا Google). */
    fun adopt(token: String, email: String, role: String, name: String = "") {
        if (token.isBlank() || !email.contains("@")) return
        save(token, email, role, name, "")
        Log.i(TAG, "session adopted for ${email.substringBefore('@')}")
    }

    /**
     * 🔗 استبدال رمز دخول (ثماني خانات، بلا حساسية لحالة الأحرف) بجلسة منبر —
     * `POST /access/redeem` بلا Authorization. يعيد رسالة الخطأ العربيّة أو `null`
     * عند النجاح (والبوّابة تعيد التحقق وحدها لأن `version` تغيّر).
     */
    suspend fun redeemCode(rawCode: String): String? {
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) return "الرمز ثماني خانات."
        return try {
            val res = MinbarAdminApi.post(
                "/access/redeem",
                JSONObject().put("code", code).put("device", deviceLabel()),
                auth = false,
            )
            val tok = res.optString("token")
            val who = res.optString("who")
            if (tok.isBlank() || !who.contains("@")) return "ردّ الخادم ناقص — أعد المحاولة."
            adopt(tok, who, res.optString("role"))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: MinbarAdminApi.ApiException) {
            when (e.code) {
                400 -> "الرمز غير صالح أو منتهٍ."
                429 -> "محاولات كثيرة — انتظر عشر دقائق."
                else -> e.message ?: "تعذّر الدخول بالرمز."
            }
        } catch (e: Exception) {
            "تعذّر الاتصال — تحقّق من الشبكة ثم أعد المحاولة."
        }
    }

    /** يُبطل الجلسة على الخادم (أفضل جهد) ثم يمسحها محلياً. */
    suspend fun signOut() {
        val tok = token
        if (tok != null) {
            runCatching { MinbarAdminApi.delete("/admin/session") }
        }
        clear()
    }
}
