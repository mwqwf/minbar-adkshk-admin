package com.ali.ishaqiyin_admin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ali.ishaqiyin_admin.core.FirebaseConfig
import com.ali.ishaqiyin_admin.data.AdminChannels
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.storage.FirebaseStorage

class AdminApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        NetworkMonitor.start(this)
        // طابور رفع الدروس: يُستأنف وحده إن بقيت فيه دروس من جلسة سابقة
        // (انقطاع اتصال أو إغلاق التطبيق أثناء الرفع).
        UploadQueue.init(this)
        if (!UploadQueue.isEmpty()) LessonUploadWorker.kick(this)
        createNotificationChannels()
        initializeFirebase()
        tuneForWeakNetworks()
    }

    /**
     * 📶 صلابة على الشبكات الضعيفة جدّاً:
     * • Firestore: كاش دائم بلا حدّ — اللوحة والدردشة تفتحان من الذاكرة
     *   فوراً بلا انتظار الشبكة، والرسائل المُرسَلة تُحفظ وتُبعث تلقائياً
     *   عند عودة الاتصال.
     * • Storage: توسيع نوافذ إعادة المحاولة بدل الفشل عند أوّل انقطاع
     *   (الافتراضي قصير جدّاً لشبكة متقطّعة).
     */
    private fun tuneForWeakNetworks() {
        runCatching {
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build(),
                    )
                    .build()
        }
        runCatching {
            FirebaseStorage.getInstance().apply {
                maxDownloadRetryTimeMillis = 10 * 60 * 1000L
                maxUploadRetryTimeMillis = 10 * 60 * 1000L
                maxOperationRetryTimeMillis = 3 * 60 * 1000L
            }
        }
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
