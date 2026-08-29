package com.ali.ishaqiyin_admin

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
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
        // Firebase أوّلاً: لا google-services.json هنا، فالتطبيق الافتراضي لا
        // يُنشأ إلا في initializeFirebase — وأي إيقاظ للعامل قبله يصل إلى
        // Firestore/Storage غير مهيّأين وبإعدادات الشبكة الضعيفة غير مضبوطة.
        initializeFirebase()
        tuneForWeakNetworks()
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
        }.onFailure {
            // فشل ضبط الكاش كان يضيع صامتاً فيبدو البطء لاحقاً بلا سبب ظاهر.
            Log.w("AdminApplication", "تعذّر ضبط كاش Firestore", it)
        }
        runCatching {
            FirebaseStorage.getInstance().apply {
                maxDownloadRetryTimeMillis = 10 * 60 * 1000L
                // ⚠️ كانت 10 دقائق — وهي **القيمة الافتراضيّة نفسها** في
                // Firebase Storage، فالسطر كان بلا أثر رغم أنّ التعليق فوقه
                // يَعِد بتوسيع النوافذ. ونافذة العشر دقائق هي بالضبط ما كان
                // يحوّل انقطاعاً عابراً إلى ERROR_RETRY_LIMIT_EXCEEDED فيخرج
                // الأمر من Firebase إلى WorkManager ويبدأ تأخير طويل.
                maxUploadRetryTimeMillis = 30 * 60 * 1000L
                maxOperationRetryTimeMillis = 3 * 60 * 1000L
            }
        }.onFailure {
            Log.w("AdminApplication", "تعذّر ضبط نوافذ إعادة محاولة التخزين", it)
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
                // 📞 قناة المكالمات: أهميّة قصوى + نغمة رنين النظام واهتزاز،
                // وإلّا وصلت المكالمة الواردة صامتة فلا يراها المشرف.
                NotificationChannel(
                    AdminChannels.CALLS,
                    "مكالمات الإدارة",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "مكالمات صوتيّة واردة من المشرفين"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 700, 700, 700)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                // 📞 المكالمة **الجارية**: إشعار حالة لا تنبيه — أهميّة منخفضة
                // بلا صوت ولا اهتزاز ولا شارة. لو بقي على قناة الرنين لرنّ
                // مجدّداً عند بدء المكالمة وتخطّى «عدم الإزعاج» معها.
                NotificationChannel(
                    AdminChannels.CALL_ONGOING,
                    "مكالمة جارية",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "إشعار المكالمة القائمة"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            ),
        )
    }
}
