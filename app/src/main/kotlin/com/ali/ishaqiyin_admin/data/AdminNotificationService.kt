package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.IOException

/**
 * فشل إرسال إشعار عام — [message] **عربية دائماً** وصالحة للعرض مباشرة
 * للمستخدم. لا يجوز أبداً تسريب نصّ Firebase الخام (INTERNAL/NOT_FOUND…)
 * إلى الشاشة: المالك يرى حينها «لغة لا يعرفها» داخل جملة عربية.
 */
class NotificationSendException(override val message: String) : Exception(message)

/** حصيلة الإرسال كما يعيدها الخادم (قد لا يُرجع عدداً فيبقى null). */
data class BroadcastOutcome(val sent: Int? = null, val failed: Int? = null)

/**
 * يسجّل جهاز المالك/المشرف في مجموعة خاصة ليصله دفع الإشعارات،
 * ويرسل الإشعار العام اليدوي عبر الدالة السحابية `sendNotification`.
 * لا يُستدعى التسجيل إلا بعد نجاح التحقق من الدور والحظر.
 */
object AdminNotificationService {
    private const val TAG = "AdminNotifications"

    /** الخادم يقصّ العنوان عند 80 والنص عند 500 (cleanString) — نطابقه هنا. */
    const val TITLE_MAX = 80
    const val BODY_MAX = 500

    /**
     * إرسال إشعار عام إلى كل مستخدمي «منبر ادكصهك».
     *
     * الخادم (`functions/index.js` → `exports.sendNotification`) يقبل
     * `title` أو `body`، ويرمي `invalid-argument` إن خلا الاثنان — لذلك
     * نُرسل المفتاحين دائماً ونمنع الطلب الفارغ من العميل أصلاً.
     *
     * كل فشل يخرج من هنا [NotificationSendException] برسالة عربية.
     */
    suspend fun sendBroadcast(title: String, body: String): BroadcastOutcome {
        val cleanTitle = title.trim().take(TITLE_MAX)
        val cleanBody = body.trim().take(BODY_MAX)
        if (cleanTitle.isEmpty() && cleanBody.isEmpty()) {
            throw NotificationSendException("اكتب عنوان الإشعار أو نصّه قبل الإرسال.")
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            throw NotificationSendException(
                "انتهت جلسة الدخول. سجّل الخروج ثم ادخل بحساب Google مجدّداً.",
            )
        }
        val result = try {
            FirebaseFunctions.getInstance()
                .getHttpsCallable("sendNotification")
                .call(hashMapOf("title" to cleanTitle, "body" to cleanBody))
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            Log.w(TAG, "sendNotification failed: code=${e.code} raw=${e.message}")
            throw NotificationSendException(arabicMessage(e))
        } catch (e: Exception) {
            Log.w(TAG, "sendNotification failed: $e")
            throw NotificationSendException(
                if (isOffline(e)) {
                    "لا يوجد اتصال بالإنترنت. تحقّق من الشبكة ثم أعد المحاولة."
                } else {
                    "تعذّر إرسال الإشعار. أعد المحاولة بعد قليل."
                },
            )
        }

        val data = result.getData() as? Map<*, *>
        // الخادم الحالي يعيد {ok:true}، ونسخ أقدم كانت تعيد {success:false,error}.
        val rejected = data != null && (data["ok"] == false || data["success"] == false)
        if (rejected) {
            val reason = data?.get("error")?.toString().orEmpty().trim()
            throw NotificationSendException(
                if (reason.isNotEmpty() && isArabic(reason)) {
                    reason
                } else {
                    "رفض الخادم إرسال الإشعار. أعد المحاولة بعد قليل."
                },
            )
        }
        return BroadcastOutcome(
            sent = (data?.get("sent") as? Number)?.toInt(),
            failed = (data?.get("failed") as? Number)?.toInt(),
        )
    }

    /**
     * ترجمة رمز خطأ الدالة السحابية إلى عربية مفهومة. رسالة الخادم تُفضَّل
     * إن كانت عربية أصلاً (كل أخطاء `HttpsError` في هذا المشروع عربية)،
     * وإلا فالرمز وحده يصل بالإنجليزية (`NOT_FOUND`, `INTERNAL`…) لأن
     * الأخطاء غير المعالَجة والانقطاعات لا تحمل رسالة خادم.
     */
    private fun arabicMessage(e: FirebaseFunctionsException): String {
        val server = e.message.orEmpty().trim()
        if (server.isNotEmpty() && isArabic(server)) return server
        return when (e.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                "انتهت جلسة الدخول. سجّل الخروج ثم ادخل بحساب Google مجدّداً."
            FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                "هذا الحساب غير مخوّل لإرسال الإشعارات."
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                "بيانات الإشعار ناقصة أو غير صالحة. اكتب عنواناً أو نصاً ثم أعد المحاولة."
            FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                "رفض الخادم الطلب قبل التحقق من سلامة التطبيق. أعد فتح اللوحة وحاول مجدّداً."
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                "طلبات كثيرة متتابعة. انتظر قليلاً ثم أعد المحاولة."
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.UNIMPLEMENTED,
            ->
                "خدمة الإشعارات غير متاحة على الخادم حالياً (لم تُنشر دالة الإرسال)."
            FirebaseFunctionsException.Code.UNAVAILABLE ->
                "تعذّر الوصول إلى الخادم. تحقّق من الاتصال بالإنترنت ثم أعد المحاولة."
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                "انتهت مهلة الاتصال بالخادم. أعد المحاولة."
            FirebaseFunctionsException.Code.CANCELLED ->
                "أُلغيت عملية الإرسال قبل اكتمالها."
            else ->
                if (isOffline(e)) {
                    "لا يوجد اتصال بالإنترنت. تحقّق من الشبكة ثم أعد المحاولة."
                } else {
                    "خطأ في الخادم أثناء الإرسال. أعد المحاولة بعد قليل."
                }
        }
    }

    /** نطاق الحروف العربية الأساسي U+0600..U+06FF (بلا حروف حرفية في الكود). */
    private fun isArabic(text: String): Boolean =
        text.any { it.code in 0x0600..0x06FF }

    /** انقطاع الشبكة يصل ملفوفاً داخل سلسلة أسباب — نفحصها كلّها. */
    private fun isOffline(error: Throwable): Boolean {
        var cause: Throwable? = error
        var guard = 0
        while (cause != null && guard < 10) {
            if (cause is IOException) return true
            cause = cause.cause
            guard += 1
        }
        return false
    }

    suspend fun registerCurrentDevice(isOwner: Boolean) {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email.orEmpty().trim().lowercase()
            if (user == null || email.isEmpty()) return

            val token = FirebaseMessaging.getInstance().token.await()
            if (!token.isNullOrEmpty()) saveToken(user, email, token, isOwner)
            ChatNotifications.syncSubscription()
        } catch (e: Exception) {
            // لا نعطّل لوحة الإدارة إن رفض النظام إذن الإشعارات أو انقطعت الشبكة.
            Log.d(TAG, "Admin notifications registration failed: $e")
        }
    }

    /** يُستدعى من خدمة الرسائل عند تجديد الرمز. */
    suspend fun onTokenRefreshed(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email.orEmpty().trim().lowercase()
        if (email.isEmpty()) return
        // الدور يُشتق من البريد لا من حالة مخبّأة: التجديد قد يقع في عملية
        // خلفية باردة لم تمرّ بالتسجيل، فتهبط رتبة المالك إلى supervisor.
        val owner = AuthService.isOwnerEmail(email)
        runCatching { saveToken(user, email, token, owner) }
            .onFailure { Log.d(TAG, "FCM token refresh failed: $it") }
    }

    private suspend fun saveToken(
        user: FirebaseUser,
        email: String,
        token: String,
        isOwner: Boolean,
    ) {
        FirebaseFirestore.getInstance()
            .collection("admin_device_tokens").document(user.uid)
            .set(
                mapOf(
                    "uid" to user.uid,
                    "email" to email,
                    "role" to if (isOwner) "owner" else "supervisor",
                    "token" to token,
                    "platform" to "android",
                    "chatMuted" to ChatNotifications.isMuted,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
    }

    suspend fun unregisterCurrentDevice() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            // تسجيل الخروج يجب ألا يُحتجز بسبب تعذّر تنظيف الرمز.
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection("admin_device_tokens").document(user.uid).delete().await()
            }
        }
        ChatNotifications.unsubscribe()
    }
}
