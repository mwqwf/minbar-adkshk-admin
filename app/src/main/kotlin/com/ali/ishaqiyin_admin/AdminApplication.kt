package com.ali.ishaqiyin_admin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ali.ishaqiyin_admin.core.FirebaseConfig
import com.ali.ishaqiyin_admin.data.AdminChannels
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck

class AdminApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        createNotificationChannels()
        initializeFirebase()
    }

    /**
     * نفس مشروع التطبيق العام (mxqp-8d1e8) بحزمة اللوحة — بلا
     * google-services.json (مطابق لما كانت تفعله نسخة Flutter عبر
     * firebase_options.dart).
     */
    private fun initializeFirebase() {
        runCatching {
            val app = FirebaseApp.getApps(this).firstOrNull() ?: FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey(FirebaseConfig.API_KEY)
                    .setApplicationId(FirebaseConfig.APP_ID)
                    .setGcmSenderId(FirebaseConfig.SENDER_ID)
                    .setProjectId(FirebaseConfig.PROJECT_ID)
                    .setStorageBucket(FirebaseConfig.STORAGE_BUCKET)
                    .build(),
            )
            // الخادم في وضع مراقبة (غير مُنفِذ بعد) — فشل تفعيل App Check لا
            // يبرر حجب اللوحة كلها خلف شاشة خطأ توحي بالانهيار.
            runCatching {
                FirebaseAppCheck.getInstance(app)
                    .installAppCheckProviderFactory(AdminAppCheckProvider.factory())
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    AdminChannels.ALERTS,
                    getString(R.string.admin_alerts_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.admin_alerts_channel_desc) },
                NotificationChannel(
                    AdminChannels.URGENT,
                    getString(R.string.admin_urgent_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.admin_urgent_channel_desc) },
            ),
        )
    }
}
