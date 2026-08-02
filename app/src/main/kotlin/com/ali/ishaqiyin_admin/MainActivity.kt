package com.ali.ishaqiyin_admin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.ui.AdminApp
import com.ali.ishaqiyin_admin.ui.MinbarAdminTheme
import com.ali.ishaqiyin_admin.ui.NotificationRoute
import com.ali.ishaqiyin_admin.ui.ShareIntake
import com.ali.ishaqiyin_admin.util.sharedAudioFrom

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* رفض الإذن لا يعطّل اللوحة — الإشعارات وحدها تتأثّر. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureShare(intent)
        captureNotification(intent)
        requestNotificationPermission()
        setContent {
            MinbarAdminTheme {
                // كامل اللوحة من اليمين لليسار (نظير Directionality في الأصل).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AdminApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureShare(intent)
        // ⚠️ إلزاميّ: الإشعار يصل والتطبيق مفتوح أصلاً في أغلب الأحيان،
        // وبلا هذا السطر لا يُوجَّه إلّا الفتح من إقلاع بارد.
        captureNotification(intent)
    }

    /**
     * 🔔 حمولة الإشعار ← وجهة داخل اللوحة.
     *
     * تعمل مع نيّتنا في المقدّمة ومع النيّة التي يبنيها النظام حين ترسم حزمة
     * FCM الإشعار في الخلفيّة (تُضاف حقول `data` كلّها extras نصّيّة).
     * تُمسح المفاتيح بعد قراءتها كي لا يُعاد فتح الوجهة نفسها مع كلّ إعادة
     * إنشاء للنشاط (تدوير الشاشة مثلاً) — والاستهلاك الثاني في
     * `NotificationRoute.consume`.
     *
     * ⚠️ المكالمات لا تمرّ من هنا: لها نشاطها ونيّتها المستقلّتان.
     */
    private fun captureNotification(intent: Intent?) {
        val route = NotificationRoute.fromIntent(intent) ?: return
        NotificationRoute.set(route)
        NotificationRoute.KEYS.forEach { key ->
            runCatching { intent?.removeExtra(key) }
        }
    }

    private fun captureShare(intent: Intent?) {
        ShareIntake.add(sharedAudioFrom(intent))
        // نصّ مشارَك من تطبيق خارجي: وجهته الوحيدة «النص المشروح» — يفتح
        // اختيار الدرس مباشرة (الملفات تمرّ بورقة الوجهات كالسابق).
        if (intent?.action == Intent.ACTION_SEND &&
            intent.type.orEmpty().startsWith("text/")
        ) {
            val text = runCatching {
                intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            }.getOrDefault("")
            if (text.isNotBlank()) {
                runCatching { intent.removeExtra(Intent.EXTRA_TEXT) }
                com.ali.ishaqiyin_admin.ui.TranscriptIntake.set(text.trim(), emptyList())
            }
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
