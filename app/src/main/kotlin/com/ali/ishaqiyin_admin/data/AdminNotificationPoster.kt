package com.ali.ishaqiyin_admin.data

import android.Manifest
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

/** قنوات إشعارات اللوحة — تُنشأ في AdminApplication قبل أي إشعار. */
object AdminChannels {
    /** تنبيهات المشرفين (المساهمات والنصوص والرسائل ورموز الاعتماد). */
    const val ALERTS = "admin_alerts"

    /** التنبيهات العاجلة (الدروس المشبوهة وطلبات النشر). */
    const val URGENT = "admin_urgent_alerts"
}

/**
 * 🔔 ناشر إشعارات اللوحة المحليّ — بلا FCM (منذ ٢٠٢٢/١.٨.٠).
 *
 * يبني الإشعار بحمولة توجيه تقرؤها [MainActivity] بالمفاتيح نفسها التي كانت
 * تصل من الخادم، فالنقر يفتح الوجهة الصحيحة (مساهمة/نصّ/رسالة) لا اللوحة
 * الرئيسية. يستعمله عامل الاستطلاع الساعيّ [AdminAlertsPollWorker].
 */
object AdminNotificationPoster {
    /** ذهب الهويّة (Gold300) — لون تمييز الأيقونة الصغيرة في الدرج. */
    private const val ACCENT = 0xFFF4BB44.toInt()

    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        // أندرويد 13+: بلا إذن POST_NOTIFICATIONS يُرمى SecurityException.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun post(context: Context, title: String, body: String, id: Int, data: Map<String, String> = emptyMap()) {
        if (title.isEmpty() && body.isEmpty()) return
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            NotificationRoute.KEYS.forEach { key ->
                data[key]?.takeIf { it.isNotEmpty() }?.let { putExtra(key, it) }
            }
        }
        // ⚠️ رمز الطلب = معرّف الإشعار لا 0: مع FLAG_UPDATE_CURRENT ورمز
        // واحد تتشارك الإشعارات كلّها نيّة واحدة، فيرث الأقدمُ حمولةَ الأحدث.
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AdminChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
