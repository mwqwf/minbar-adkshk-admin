package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.ali.ishaqiyin_admin.BuildConfig
import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class NotificationSendException(message: String) : Exception(message)

data class BroadcastOutcome(val sent: Int?, val failed: Int?)

/**
 * 🔔 إشعارات اللوحة — على `minbar-api` (قرار 2026-09-10):
 * - تسجيل جهاز المشرف (رمز FCM) في D1 ليصله ما يُنبَّه به المشرفون.
 * - إرسال الإشعار العام إلى كل مستخدمي التطبيق (موضوع `content`).
 *
 * ⛔ لا Firestore ولا دوال سحابيّة. وFCM يبقى (مجانيّ بلا فوترة، ولا بديل
 * مجانيّ موثوق للدفع على أندرويد لا يستنزف بطاريّة المستخدم).
 */
object AdminNotificationService {
    private const val TAG = "AdminNotifications"
    private const val TOKEN_REWRITE_MS = 3L * 24 * 60 * 60 * 1000

    /** الخادم يقصّ العنوان عند 120 والنصّ عند 500 — نطابقه هنا. */
    const val TITLE_MAX = 80
    const val BODY_MAX = 500

    /** يسجّل جهاز المشرف الحالي (بعد الدخول وعند كل عودة للمقدّمة). */
    suspend fun registerCurrentDevice(isOwner: Boolean = false) {
        try {
            val email = AuthService.currentUser?.email.orEmpty().trim().lowercase()
            if (email.isEmpty()) return
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNullOrEmpty()) return
            saveToken(email, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Admin notifications registration failed: $e")
        }
    }

    suspend fun onTokenRefreshed(token: String) {
        val email = AuthService.currentUser?.email.orEmpty().trim().lowercase()
        if (email.isEmpty()) return
        runCatching { saveToken(email, token) }
            .onFailure { Log.d(TAG, "FCM token refresh failed: $it") }
    }

    private suspend fun saveToken(email: String, token: String) {
        val sig = "$email|$token"
        val fresh = System.currentTimeMillis() - AppPrefs.lastDeviceTokenWriteMs < TOKEN_REWRITE_MS
        if (sig == AppPrefs.lastDeviceTokenSig && fresh) return
        MinbarAdminApi.post(
            "/admin/devices",
            JSONObject().put("token", token).put("versionCode", BuildConfig.VERSION_CODE),
        )
        AppPrefs.lastDeviceTokenSig = sig
        AppPrefs.lastDeviceTokenWriteMs = System.currentTimeMillis()
    }

    suspend fun unregisterCurrentDevice() {
        AppPrefs.lastDeviceTokenSig = null
        AppPrefs.lastDeviceTokenWriteMs = 0L
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            if (!token.isNullOrEmpty()) {
                MinbarAdminApi.delete("/admin/devices/${java.net.URLEncoder.encode(token, "UTF-8")}")
            }
        }
    }

    /**
     * إشعار عام إلى كل مستخدمي «منبر ادكصهك».
     * كل فشل يخرج من هنا [NotificationSendException] برسالة عربية.
     */
    suspend fun sendBroadcast(title: String, body: String): BroadcastOutcome {
        val cleanTitle = title.trim().take(TITLE_MAX)
        val cleanBody = body.trim().take(BODY_MAX)
        if (cleanTitle.isEmpty() && cleanBody.isEmpty()) {
            throw NotificationSendException("اكتب عنوان الإشعار أو نصّه قبل الإرسال.")
        }
        if (AuthService.currentUser == null) {
            throw NotificationSendException(
                "انتهت جلسة الدخول. سجّل الخروج ثم ادخل بحساب Google مجدّداً.",
            )
        }
        val result = try {
            MinbarAdminApi.post(
                "/admin/notify",
                JSONObject()
                    .put("title", cleanTitle)
                    .put("body", cleanBody)
                    .put("topic", "content")
                    .put("type", "manual"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MinbarAdminApi.ApiException) {
            Log.w(TAG, "notify failed: code=${e.code} raw=${e.message}")
            throw NotificationSendException(e.message ?: "رفض الخادم إرسال الإشعار.")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: $e")
            throw NotificationSendException(
                "تعذّر إرسال الإشعار — تحقّق من الاتصال ثم أعد المحاولة.",
            )
        }
        if (!result.optBoolean("ok", false)) {
            throw NotificationSendException(
                result.optString("error").ifBlank { "رفض الخادم إرسال الإشعار. أعد المحاولة بعد قليل." },
            )
        }
        return BroadcastOutcome(sent = null, failed = null)
    }
}
