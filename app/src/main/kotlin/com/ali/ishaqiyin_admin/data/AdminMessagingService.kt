package com.ali.ishaqiyin_admin.data

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ali.ishaqiyin_admin.MainActivity
import com.ali.ishaqiyin_admin.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** قنوات إشعارات اللوحة — يجب إنشاؤها مسبقاً وإلا سقطت رسائل الخلفية على قناة صمّاء. */
object AdminChannels {
    /** القناة التي تستهدفها رسائل الخادم (pushToAdmins: channelId=admin_alerts). */
    const val ALERTS = "admin_alerts"

    /** قناة العرض أثناء فتح التطبيق (نظير flutter_local_notifications). */
    const val URGENT = "admin_urgent_alerts"
}

/**
 * استقبال رسائل FCM: أثناء فتح التطبيق تصل هنا فنعرضها بأنفسنا بنفس منطق
 * نسخة Flutter (كتم رسائل المجموعة إن كانت مفتوحة أو مكتومة، وكتم رسالة
 * خاصّة إن كانت محادثتها معروضة).
 */
class AdminMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { AdminNotificationService.onTokenRefreshed(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"].orEmpty()
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
        show(title, body, message.messageId?.hashCode() ?: System.currentTimeMillis().toInt())
    }

    private fun show(title: String, body: String, id: Int) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
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
