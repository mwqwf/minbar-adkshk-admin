package com.ali.ishaqiyin_admin.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.MainActivity
import com.ali.ishaqiyin_admin.R
import com.ali.ishaqiyin_admin.call.CallActivity
import com.ali.ishaqiyin_admin.call.CallEngine
import com.ali.ishaqiyin_admin.call.CallService
import com.ali.ishaqiyin_admin.core.AppConfig
import com.ali.ishaqiyin_admin.ui.NotificationRoute
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** قنوات إشعارات اللوحة — يجب إنشاؤها مسبقاً وإلا سقطت رسائل الخلفية على قناة صمّاء. */
object AdminChannels {
    /** القناة التي تستهدفها رسائل الخادم (pushToAdmins: channelId=admin_alerts). */
    const val ALERTS = "admin_alerts"

    /** قناة العرض أثناء فتح التطبيق (نظير flutter_local_notifications). */
    const val URGENT = "admin_urgent_alerts"

    /** قناة المكالمات الصوتيّة: أهميّة قصوى + نغمة رنين واهتزاز. */
    const val CALLS = "admin_calls"
}

/** إشعار المكالمة الواردة — معرّف ثابت ليُلغى من أيّ مكان. */
object AdminCallNotifications {
    const val INCOMING_ID = 90_211

    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(INCOMING_ID) }
    }
}

/**
 * 📞 المكالمات التي وصل إلغاؤها — ترتيب دفعتَي FCM (`incoming` و`cancel`)
 * غير مضمون، فقد تُعالَج `cancel` أوّلاً ثمّ يعرض فرع `incoming` بعدها إشعاراً
 * دائماً لمكالمة ميّتة. مجموعة محدودة الحجم (آخر 50 معرّفاً) تكفي:
 * الدفعتان لا تفترقان إلّا ثوانيَ.
 */
private object CancelledCallIds {
    private const val MAX = 50
    private val ids = LinkedHashSet<String>()

    fun mark(callId: String) = synchronized(ids) {
        ids.remove(callId)
        ids.add(callId)
        while (ids.size > MAX) ids.remove(ids.first())
    }

    fun contains(callId: String): Boolean = synchronized(ids) { ids.contains(callId) }
}

/**
 * استقبال رسائل FCM: أثناء فتح التطبيق تصل هنا فنعرضها بأنفسنا بنفس منطق
 * نسخة Flutter (كتم رسائل المجموعة إن كانت مفتوحة أو مكتومة، وكتم رسالة
 * خاصّة إن كانت محادثتها معروضة).
 */
class AdminMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val REQUEST_ANSWER = 9011
        const val REQUEST_DECLINE = 9012
        const val REQUEST_RINGING = 9013
    }

    override fun onNewToken(token: String) {
        scope.launch { AdminNotificationService.onTokenRefreshed(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"].orEmpty()
        // 📞 مكالمة صوتيّة: حمولة data-only لا تُرسَم تلقائياً في الدرج،
        // فنبنيها هنا بنمط المكالمات (شاشة كاملة + قبول/رفض).
        if (type == "admin_call") {
            handleCallPush(message.data)
            return
        }
        if (type == "admin_chat" && (ChatNotifications.isChatOpen || ChatNotifications.isMuted)) {
            return
        }
        // رسالة خاصّة: لا تُزعج إن كانت محادثتها نفسها مفتوحة أمام المستخدم.
        if (type == "admin_dm") {
            val threadId = message.data["threadId"].orEmpty()
            if (threadId.isNotEmpty() && ChatNotifications.openDmThreadId == threadId) return
            if (ChatNotifications.isMuted) return
        }
        val title = message.notification?.title ?: message.data["title"] ?: "تنبيه الإدارة"
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        if (title.isEmpty() && body.isEmpty()) return
        show(
            title = title,
            body = body,
            id = message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
            data = message.data,
        )
    }

    /**
     * 📞 حمولة المكالمة: `action = incoming` تُظهر إشعار رنين بشاشة كاملة،
     * و`cancel` تُسقطه وتُغلق شاشة الرنين إن كانت ظاهرة.
     */
    private fun handleCallPush(data: Map<String, String>) {
        val callId = data["callId"].orEmpty()
        if (callId.isEmpty()) return
        when (data["action"].orEmpty()) {
            "incoming" -> scope.launch {
                // ⚠️ الرسائل المتأخّرة (شبكة بطيئة/جهاز نائم) قد تصل بعد
                // انتهاء المكالمة — لا نرنّ إلّا إن كانت ما تزال ترنّ فعلاً.
                // القراءة من الخادم حصراً: نسخة الكاش المحلّي قد تكون لقطة
                // «ترنّ» قديمة. بلا شبكة تفشل القراءة فلا نرنّ — وهو المطلوب،
                // إذ لا تقوم مكالمة أصلاً بلا شبكة.
                if (CancelledCallIds.contains(callId)) return@launch
                val doc = withTimeoutOrNull(6000L) {
                    CallRepository.fetchCallFromServer(callId)
                }
                // فحص الإلغاء ثانيةً: قد يصل أثناء القراءة أعلاه.
                val ageMs = doc?.let { System.currentTimeMillis() - it.createdAtMs }
                val current = CallEngine.state.value
                if (current.busy && current.callId != callId) {
                    withTimeoutOrNull(5000L) {
                        runCatching { CallRepository.markBusy(callId) }
                    }
                    return@launch
                }
                if (
                    doc?.isRinging == true &&
                    ageMs != null &&
                    ageMs in -300_000L..AppConfig.CALL_RING_TIMEOUT_MS &&
                    !CancelledCallIds.contains(callId)
                ) {
                    showIncomingCall(
                        callId = callId,
                        callerId = data["callerId"].orEmpty(),
                        callerName = data["callerName"].orEmpty().ifEmpty { "مشرف" },
                        callerPhoto = data["callerPhoto"].orEmpty(),
                    )
                }
            }

            "cancel" -> {
                CancelledCallIds.mark(callId)
                AdminCallNotifications.cancelIncoming(this)
                CallEngine.cancelIncoming(callId)
            }
        }
    }

    private fun showIncomingCall(
        callId: String,
        callerId: String,
        callerName: String,
        callerPhoto: String,
    ) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // نيّتان متمايزتان (ورمزا طلب مختلفان وإلّا داس أحدهما الآخر):
        // زرّ «ردّ» يقبل مباشرة، وفتح الإشعار يعرض شاشة الرنين.
        val answerIntent = PendingIntent.getActivity(
            this,
            REQUEST_ANSWER,
            CallActivity.incomingIntent(
                context = this,
                callId = callId,
                callerUid = callerId,
                callerName = callerName,
                callerPhoto = callerPhoto,
                answerNow = true,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ringingIntent = PendingIntent.getActivity(
            this,
            REQUEST_RINGING,
            CallActivity.incomingIntent(this, callId, callerId, callerName, callerPhoto),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declineRaw = Intent(this, CallService::class.java)
            .setAction(CallService.ACTION_DECLINE)
            .putExtra(CallService.EXTRA_CALL_ID, callId)
        // ⚠️ getService يستدعي startService فتنهار الخدمة على أندرويد 8+
        // وهي في الخلفيّة — الرفض من الإشعار يحتاج getForegroundService.
        val declineIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                REQUEST_DECLINE,
                declineRaw,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                REQUEST_DECLINE,
                declineRaw,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val notification = NotificationCompat.Builder(this, AdminChannels.CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(callerName)
            .setContentText("مكالمة صوتيّة واردة")
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declineIntent,
                    answerIntent,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(ringingIntent)
            // يفتح شاشة الرنين فوق القفل مباشرة (نمط واتساب).
            .setFullScreenIntent(ringingIntent, true)
            .build()
        runCatching { manager.notify(AdminCallNotifications.INCOMING_ID, notification) }
    }

    private fun show(title: String, body: String, id: Int, data: Map<String, String>) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        // أندرويد 13+: بلا إذن POST_NOTIFICATIONS يُرمى SecurityException.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // 🔔 حمولة التوجيه: بلاها كان كلّ إشعار — مساهمة أو نصّ مشروح أو
            // رسالة خاصّة — ينتهي إلى اللوحة الرئيسية. نفس مفاتيح FCM حرفياً
            // كي يقرأها MainActivity بالقارئ نفسه في المقدّمة والخلفيّة.
            NotificationRoute.KEYS.forEach { key ->
                data[key]?.takeIf { it.isNotEmpty() }?.let { putExtra(key, it) }
            }
        }
        // ⚠️ رمز الطلب = معرّف الإشعار لا 0: مع FLAG_UPDATE_CURRENT ورمز
        // واحد تتشارك الإشعارات كلّها نيّة واحدة، فيرث الأقدمُ حمولةَ الأحدث
        // وتفتح كلّها الوجهة نفسها.
        val pending = PendingIntent.getActivity(
            this,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, AdminChannels.URGENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}
