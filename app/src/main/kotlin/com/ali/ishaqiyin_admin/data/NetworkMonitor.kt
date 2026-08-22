package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 📶 مراقب الاتصال — مصدر واحد لحالة الشبكة تستعمله الواجهة (بانر «دون
 * اتصال») ومخزن الوسائط (استئناف التنزيلات المعلَّقة فور عودة الشبكة).
 *
 * ملاحظة مهمّة للشبكات الضعيفة: نعتبر الاتصال قائماً بمجرّد وجود شبكة
 * `NET_CAPABILITY_INTERNET` حتى لو لم تُثبَّت `VALIDATED`، لأن الشبكات
 * الضعيفة كثيراً ما تفشل في تحقّق النظام بينما تنقل البيانات فعلاً.
 */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private var started = false
    private val activeNetworks = mutableSetOf<Network>()

    /** سياق التطبيق — يلزم لإيقاظ عامل الرفع فور عودة الشبكة. */
    private var appContext: Context? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // الحالة الابتدائية تُحسب **قبل** تسجيل المستمع: حسابها بعده كان
        // قد يدوس قيمة onAvailable المبكرة بـfalse عابرة (activeNetwork
        // متقلّب لحظة الإقلاع) فيعلق «دون اتصال» رغم اتصال سليم.
        _online.value = runCatching {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
        runCatching {
            manager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(activeNetworks) { activeNetworks.add(network) }
                        val wasOffline = !_online.value
                        _online.value = true
                        if (wasOffline) wakeUploads()
                    }

                    override fun onLost(network: Network) {
                        val empty = synchronized(activeNetworks) {
                            activeNetworks.remove(network)
                            activeNetworks.isEmpty()
                        }
                        if (empty) _online.value = false
                    }
                },
            )
        }
    }

    /**
     * ⚡ عودة الشبكة توقظ الرفع فوراً.
     *
     * ⚠️ كان `onAvailable` يضبط الراية في الذاكرة **ولا شيء غيرها**: لا يمسّ
     * الطابور ولا يوقظ العامل. فيبقى العامل نائماً في backoff (كان يبلغ سقف
     * WorkManager: خمس ساعات) بينما تعرض الواجهة «بانتظار الإنترنت — سيُستأنف
     * تلقائياً» فوق إشارة واي‑فاي ممتلئة. ⛔ لا يجوز أن يعود وعدٌ بالاستئناف
     * التلقائيّ بلا هذا الإيقاظ: `kickNow` بـREPLACE هو وحده ما يصفّر
     * `runAttemptCount` ويكسر أيّ تأخير عالق.
     */
    private fun wakeUploads() {
        val context = appContext ?: return
        if (UploadQueue.isEmpty() || UploadQueue.isPaused()) return
        // الوسم «NetworkMonitor» لا «LessonUpload»: به يُفصل مصدر الإيقاظ عن
        // سطور العامل في `adb logcat -s NetworkMonitor:V`.
        Log.i("NetworkMonitor", "onAvailable ⇒ kickNow(REPLACE)")
        UploadQueue.clearNetworkWait()
        runCatching { LessonUploadWorker.kickNow(context) }
    }
}
