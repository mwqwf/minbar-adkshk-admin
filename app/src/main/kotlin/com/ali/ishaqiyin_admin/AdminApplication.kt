package com.ali.ishaqiyin_admin

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.ali.ishaqiyin_admin.core.FirebaseConfig
import com.ali.ishaqiyin_admin.data.AdminChannels
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.UploadWorkWatcher
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class AdminApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        NetworkMonitor.start(this)
        // Firebase أوّلاً: لا google-services.json هنا، فالتطبيق الافتراضي لا
        // يُنشأ إلا في initializeFirebase — وأي إيقاظ للعامل قبله يصل إلى
        // Firestore/Storage غير مهيّأين وبإعدادات الشبكة الضعيفة غير مضبوطة.
        initializeFirebase()
        createNotificationChannels()
        // طابور رفع الدروس: يُستأنف وحده إن بقيت فيه دروس من جلسة سابقة
        // (انقطاع اتصال أو إغلاق التطبيق أثناء الرفع).
        UploadQueue.init(this)
        // مراقبة حالة WorkManager: هي مصدر نصوص الواجهة الصادقة، وبها يُعرف
        // هل هناك عمل يجري فعلاً قبل أيّ إيقاظ.
        UploadWorkWatcher.start(this)
        // ⛔ `kick` لا `kickNow` هنا: حين يقرّر WorkManager تشغيل عامل الرفع
        // والعمليّة ميتة (بعد إعادة الإقلاع أو الإيقاف القسريّ) يُنشئ النظام
        // الـApplication **أوّلاً** ثمّ يشغّل العامل — فـ`kickNow` بـREPLACE
        // كان يُلغي هنا العملَ الذي أيقظ العمليّة أصلاً. الغرض في `onCreate`
        // «تأكّد أنّ هناك عملاً مجدولاً» لا «اقطع ما يجري».
        if (!UploadQueue.isEmpty()) LessonUploadWorker.kick(this)
        watchForegroundReturns()
    }

    /**
     * 🍃 امتثال متطلب Play (ذاكرة الصور النقطية بالخلفية): عند اختفاء الواجهة
     * أو أشدّ يُفرَغ كاش ذاكرة Coil (صور الأعضاء وصفحات «النص المشروح»)،
     * وعند ضغطٍ أخفّ والواجهة ظاهرة يُقلَّص للنصف فقط. الصور تُعاد من كاش
     * القرص/الشبكة عند العودة فلا أثر وظيفي.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val imageCache = runCatching {
            coil3.SingletonImageLoader.get(this).memoryCache
        }.getOrNull()
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { imageCache?.clear() }
        } else if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { imageCache?.let { it.trimToSize(it.size / 2) } }
        }
    }

    /**
     * ⚡ إيقاظ الطابور عند كلّ عودة إلى المقدّمة لا عند البدء البارد وحده.
     *
     * ⚠️ كان `onCreate` هو المُوقظ الوحيد، فالعودة الدافئة من «التطبيقات
     * الأخيرة» لا توقظ شيئاً — وعلى سامسونج تحديداً تضع «العناية بالجهاز»
     * التطبيق في «النائمة» فلا يشغّل JobScheduler مهامّه حتى يُفتح يدوياً.
     *
     * ⛔ الشرط `!UploadWorkWatcher.isRunning()` ليس زينة: `kickNow` يستعمل
     * REPLACE، فإيقاظه فوق رفع جارٍ يقطعه ويعيد بدءه بلا داعٍ.
     */
    private fun watchForegroundReturns() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private var visible = 0

                override fun onActivityStarted(activity: Activity) {
                    val enteringForeground = visible == 0
                    visible += 1
                    if (!enteringForeground) return
                    if (UploadQueue.isEmpty() || UploadQueue.isPaused()) return
                    if (UploadWorkWatcher.isRunning()) return
                    LessonUploadWorker.kickNow(this@AdminApplication)
                }

                override fun onActivityStopped(activity: Activity) {
                    if (visible > 0) visible -= 1
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
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
