package com.ali.ishaqiyin_admin.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import com.ali.ishaqiyin_admin.ui.NotificationRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * ⏰ استطلاع تنبيهات المشرفين بلا FCM (منذ ٢٠٢٢/١.٨.٠) — بنبضٍ لا بساعةٍ ثابتة
 * (أمر المالك 2026-09-12):
 *
 * 1. يُقرأ النبض `pulse.js` أولاً (بضع مئات بايت، بلا مصادقة، من الـCDN):
 *    `{"contentMs","lessonsCount","notifMs","alertsMs","at"}`، واحتياطه
 *    `GET /v1/pulse` على الـWorker. لا يُنادى `/admin/alerts` إلا إذا كان
 *    `alertsMs` أحدث من آخر ما رأيناه — فالجهاز الساكن لا يكلّف الخادم ولا
 *    شبكة المالك شيئاً. وإن فشل النبضان معاً فالاحتياط `/admin/alerts` مباشرة.
 * 2. الجدولة تكيّفيّة: عمل وحيد يعيد جدولة نفسه بتأخيرٍ بحسب حداثة آخر تغيير
 *    في النبض — خلال 24 ساعة ⇒ 30 دقيقة، خلال 1–3 أيام ⇒ 3 ساعات، أقدم ⇒ 12 ساعة.
 * 3. حارس دوريّ كل 12 ساعة يعيد الحلقة إن ماتت (قتل النظام للعمل الوحيد).
 *    ⛔ `setInitialDelay` إلزاميّ للدوريّ (درس 2026-08-29).
 * 4. نبضة فوريّة عند فتح اللوحة إن مضى أكثر من 15 دقيقة على آخر نبض.
 *
 * الإشعار محليّ لكل تنبيه غير مقروء أحدث من [AppPrefs.lastAlertSeenMs]، فلا
 * يتكرّر. والمشرف بلا جلسة لا يُستطلع.
 */
class AdminAlertsPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AuthService.isLoggedIn) return Result.success()
        AppPrefs.lastPulseRunMs = System.currentTimeMillis()

        val pulse = readPulse()
        val alertsMs = pulse?.optLong("alertsMs") ?: 0L
        val needFetch = pulse == null || alertsMs > AppPrefs.lastPulseAlertsMs
        var ok = true
        if (needFetch) {
            ok = fetchAndNotify()
            if (ok && pulse != null) AppPrefs.lastPulseAlertsMs = alertsMs
        }

        // إعادة الجدولة بحسب حداثة آخر تغيير (أحدث الأختام الثلاثة).
        val lastChange = pulse?.let {
            maxOf(it.optLong("contentMs"), it.optLong("notifMs"), it.optLong("alertsMs"))
        } ?: 0L
        scheduleNext(applicationContext, delayFor(lastChange))
        return if (ok) Result.success() else Result.retry()
    }

    /** يقرأ النبض من الـCDN، ثم من الـWorker احتياطاً؛ `null` عند فشلهما. */
    private suspend fun readPulse(): JSONObject? =
        readCdnPulse() ?: runCatching { MinbarAdminApi.get("/v1/pulse", auth = false) }.getOrNull()

    private suspend fun readCdnPulse(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(PULSE_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return@runCatching null
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** يجلب التنبيهات ويُشعر بالجديد غير المقروء؛ `false` عند فشل الشبكة. */
    private suspend fun fetchAndNotify(): Boolean {
        val me = AdminAlertsFeed.myEmail
        val alerts = try {
            val items = MinbarAdminApi.get("/admin/alerts").optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map(AdminAlert::fromJson)
        } catch (e: Exception) {
            return false
        }
        val since = AppPrefs.lastAlertSeenMs
        alerts
            .filter { it.createdAtMs > since && !it.isReadBy(me) }
            .sortedBy { it.createdAtMs }
            // أقصى خمسة إشعارات في الدفعة: الباقي يُقرأ من شاشة التنبيهات.
            .takeLast(MAX_PER_RUN)
            .forEach { alert ->
                AdminNotificationPoster.post(
                    applicationContext,
                    title = alert.title.ifEmpty { "تنبيه الإدارة" },
                    body = alert.body,
                    id = alert.id.hashCode(),
                    data = mapOf(
                        NotificationRoute.KEY_TYPE to alert.type,
                        NotificationRoute.KEY_REF to alert.refId,
                    ),
                )
            }
        val newest = alerts.maxOfOrNull { it.createdAtMs } ?: 0L
        if (newest > since) AppPrefs.lastAlertSeenMs = newest
        return true
    }

    companion object {
        const val PULSE_URL = "https://media.menbar.app/pulse.js"
        private const val LOOP = "admin_alerts_pulse"
        private const val GUARD = "admin_alerts_guard"
        private const val MAX_PER_RUN = 5
        private const val FOREGROUND_GAP_MS = 15L * 60 * 1000

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** تأخير النبضة التالية بحسب عمر آخر تغيير. */
        internal fun delayFor(lastChangeMs: Long, now: Long = System.currentTimeMillis()): Long {
            val age = if (lastChangeMs <= 0L) Long.MAX_VALUE else now - lastChangeMs
            return when {
                age < TimeUnit.HOURS.toMillis(24) -> TimeUnit.MINUTES.toMillis(30)
                age < TimeUnit.DAYS.toMillis(3) -> TimeUnit.HOURS.toMillis(3)
                else -> TimeUnit.HOURS.toMillis(12)
            }
        }

        private fun scheduleNext(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<AdminAlertsPollWorker>()
                .setConstraints(connected)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(LOOP, ExistingWorkPolicy.REPLACE, request)
        }

        /** عند الإقلاع: الحارس الدوريّ (KEEP) + بدء الحلقة إن لم تكن قائمة. */
        fun schedule(context: Context) {
            val guard = PeriodicWorkRequestBuilder<AdminAlertsPollWorker>(12, TimeUnit.HOURS)
                .setConstraints(connected)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.enqueueUniquePeriodicWork(GUARD, ExistingPeriodicWorkPolicy.KEEP, guard)
            val first = OneTimeWorkRequestBuilder<AdminAlertsPollWorker>()
                .setConstraints(connected)
                .setInitialDelay(delayFor(0L), TimeUnit.MILLISECONDS)
                .build()
            manager.enqueueUniqueWork(LOOP, ExistingWorkPolicy.KEEP, first)
        }

        /** نبضة فوريّة عند العودة إلى المقدّمة إن مضى أكثر من 15 دقيقة على آخر نبض. */
        fun pulseNowIfStale(context: Context) {
            if (System.currentTimeMillis() - AppPrefs.lastPulseRunMs < FOREGROUND_GAP_MS) return
            scheduleNext(context, 0L)
        }
    }
}
