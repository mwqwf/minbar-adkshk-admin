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
                if (isOfflineError(e)) {
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
                if (reason.isNotEmpty() && isArabicText(reason)) {
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
     * ترجمة رمز خطأ الدالة السحابية إلى عربية مفهومة. المنطق العامّ صار في
     * `ErrorMessages.kt` (`Throwable.arabicReason()`) وتستعمله كلّ الشاشات؛
     * هنا لا تبقى إلّا الصياغات الخاصّة بالإشعارات وحدها، وما عداها يُفوَّض.
     * رسالة الخادم العربية تُفضَّل كما هي (كل أخطاء `HttpsError` عربية).
     */
    private fun arabicMessage(e: FirebaseFunctionsException): String {
        val server = e.message.orEmpty().trim()
        if (server.isNotEmpty() && isArabicText(server)) return server
        // 401 من بوّابة Google (لا من الدالة) يعيد صفحة HTML لا JSON، فيفشل
        // تحليلها ويبقى نصّ الرسالة اسمَ الرمز المجرَّد "UNAUTHENTICATED".
        // حدث ذلك فعلاً حين فقدت `sendNotification` وحدها ربط الاستدعاء
        // (allUsers/invoker) فرُفض الطلب قبل بلوغ الشيفرة، وكان يُعرض حينها
        // «انتهت جلسة الدخول» فيدور المالك في خروج ودخول لا يُصلحان شيئاً.
        // أمّا انتهاء الجلسة الحقيقي فرسالته "Unauthenticated" داخل JSON،
        // فيسقط إلى `arabicReason()` ويأخذ نصّ الجلسة الصحيح من ErrorMessages.
        if (e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED &&
            server == "UNAUTHENTICATED"
        ) {
            return "رفض الخادم الطلب قبل أن يبلغ دالة الإشعارات. " +
                "راجع نشر الدالة وصلاحية استدعائها."
        }
        return when (e.code) {
            FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                "هذا الحساب غير مخوّل لإرسال الإشعارات."
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                "بيانات الإشعار ناقصة أو غير صالحة. اكتب عنواناً أو نصّاً ثم أعد المحاولة."
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.UNIMPLEMENTED,
            ->
                "خدمة الإشعارات غير متاحة على الخادم حالياً (لم تُنشر دالة الإرسال)."
            FirebaseFunctionsException.Code.CANCELLED ->
                "أُلغيت عملية الإرسال قبل اكتمالها."
            else -> e.arabicReason()
        }
    }

    suspend fun registerCurrentDevice(isOwner: Boolean) {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email.orEmpty().trim().lowercase()
            if (user == null || email.isEmpty()) return

            val token = FirebaseMessaging.getInstance().token.await()
            if (!token.isNullOrEmpty()) saveToken(user, email, token, isOwner)
            ChatNotifications.syncSubscription()
        } catch (e: CancellationException) {
            // ⚠️ `catch (Exception)` كان يبتلع إلغاء الكوروتين (مغادرة الشاشة)
            // فيُسجَّل كأنّه فشل تسجيل جهاز — كما فُعل صراحةً في [sendBroadcast].
            throw e
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

    /** أقصى عمر لبصمة الرمز قبل إعادة الكتابة رغم ثباتها (يُبقي updatedAt حيّاً). */
    private const val TOKEN_REWRITE_MS = 3L * 24 * 60 * 60 * 1000

    private suspend fun saveToken(
        user: FirebaseUser,
        email: String,
        token: String,
        isOwner: Boolean,
    ) {
        // كتابة واحدة عند التغيّر فقط: الرمز والدور والكتم لا تتبدّل غالباً
        // بين إقلاعين، وكانت الوثيقة تُكتب في كلّ فتح للتطبيق بلا داعٍ.
        // تُعاد الكتابة دوريّاً كي لا يبدو الرمز بائتاً لمن ينظّف بالخادم.
        val sig = "${user.uid}|$email|$isOwner|$token|${ChatNotifications.isMuted}"
        val fresh = System.currentTimeMillis() - AppPrefs.lastDeviceTokenWriteMs < TOKEN_REWRITE_MS
        if (sig == AppPrefs.lastDeviceTokenSig && fresh) return
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
        AppPrefs.lastDeviceTokenSig = sig
        AppPrefs.lastDeviceTokenWriteMs = System.currentTimeMillis()
    }

    suspend fun unregisterCurrentDevice() {
        // تُمسح البصمة كي تُكتب الوثيقة من جديد عند الدخول التالي.
        AppPrefs.lastDeviceTokenSig = null
        AppPrefs.lastDeviceTokenWriteMs = 0L
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
