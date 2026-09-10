package com.ali.ishaqiyin_admin.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.MainActivity
import com.ali.ishaqiyin_admin.R
import com.ali.ishaqiyin_admin.ui.NotificationRoute
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

class AdminMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        /** ذهب الهويّة (Gold300) — لون تمييز الأيقونة الصغيرة في الدرج. */
        val ACCENT = 0xFFF4BB44.toInt()
    }

    override fun onNewToken(token: String) {
        scope.launch { AdminNotificationService.onTokenRefreshed(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"].orEmpty()
        // 📞 مكالمة صوتيّة: حمولة data-only لا تُرسَم تلقائياً في الدرج،
        // فنبنيها هنا بنمط المكالمات (شاشة كاملة + قبول/رفض).
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
        // ⚠️ قناة واحدة للتنبيه الواحد: الخادم يثبّت `admin_alerts` لكلّ دفعاته،
        // فبناء المقدّمة على `admin_urgent_alerts` كان يجعل إسكات «تنبيهات
        // الإدارة» من إعدادات النظام لا يُسكت التنبيه نفسه واللوحة مفتوحة.
        val notification = NotificationCompat.Builder(this, AdminChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
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
