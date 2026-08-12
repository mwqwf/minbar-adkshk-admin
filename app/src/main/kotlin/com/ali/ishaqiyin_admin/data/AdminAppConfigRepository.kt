package com.ali.ishaqiyin_admin.data

import android.content.Context
import com.ali.ishaqiyin_admin.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * 🔔 تذكير تحديث **لوحة الإدارة نفسها** — نظير الآليّة العاملة في التطبيق
 * العام، بوثيقة مستقلّة (`app_config/admin_android`) كي لا يختلط إصدار
 * اللوحة بإصدار التطبيق العام.
 *
 * - أقدم من `latestVersionCode` ⇒ تذكير يمكن صرفه (مرّة كل ٢٤ ساعة).
 * - أقدم من `minSupportedVersionCode` ⇒ تذكير يتكرّر كل تشغيل.
 *
 * ولا يُحجب المشرف أبداً: زرّ «لاحقاً» موجود دائماً — تعطيل لوحة الإدارة
 * لأجل إصدار يعني توقّف النشر والإشراف كلّه.
 *
 * اللوحة تبقى في **الاختبار المغلق** دائماً، فرابط المتجر ثابت ولا يتغيّر
 * ([PLAY_URL])، ويكفي أن يفتحه المشرف ليحصل على النسخة الجديدة.
 */
class AdminAppConfigRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val db = FirebaseFirestore.getInstance()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    sealed interface Status {
        data object None : Status

        data class Optional(val latest: Int, val message: String, val storeUrl: String) : Status

        data class Required(val latest: Int, val message: String, val storeUrl: String) : Status
    }

    /** يقرأ الإعداد (بحدّ أدنى ست ساعات بين قراءتين) ثم يقارن. */
    suspend fun status(): Status {
        refreshIfStale()
        val latest = prefs.getInt(KEY_LATEST, 0)
        val minSupported = prefs.getInt(KEY_MIN, 0)
        val message = prefs.getString(KEY_MESSAGE, "").orEmpty()
        val storeUrl = prefs.getString(KEY_STORE, "").orEmpty().ifBlank { PLAY_URL }
        val current = BuildConfig.VERSION_CODE
        return when {
            current < minSupported -> Status.Required(latest, message, storeUrl)
            current < latest -> Status.Optional(latest, message, storeUrl)
            else -> Status.None
        }
    }

    fun shouldPrompt(status: Status): Boolean = when (status) {
        is Status.None -> false
        is Status.Required -> true
        is Status.Optional -> {
            val dismissed = prefs.getInt(KEY_DISMISSED, 0) >= status.latest
            val since = System.currentTimeMillis() - prefs.getLong(KEY_PROMPTED, 0L)
            !dismissed && since >= PROMPT_INTERVAL_MS
        }
    }

    fun markPrompted() {
        prefs.edit().putLong(KEY_PROMPTED, System.currentTimeMillis()).apply()
    }

    fun dismiss(latest: Int) {
        prefs.edit().putInt(KEY_DISMISSED, latest).apply()
    }

    /**
     * ⚙️ **نشر ذاتيّ لإصدار اللوحة** — يُنادى عند دخول المالك.
     *
     * الآليّة كانت تعتمد خطوة يدويّة (يفتح المالك شاشة التذكير ويكتب الرقم)،
     * وقد نُسيت فعلاً: بقي `latestVersionCode` عند 10 بينما التطبيق المنشور
     * 13، فلم يُذكَّر أحد بشيء. الآن: أوّل مرّة يفتح فيها المالكُ **بناءً
     * أحدث مما في الوثيقة** تُحدَّث الوثيقة وحدها، ويصل التذكير لبقيّة
     * المشرفين بلا أن يتذكّر أحد شيئاً.
     *
     * مقصور على المالك: القاعدة لا تسمح لغيره بالكتابة أصلاً، ولأنّ مالك
     * الإصدار هو من يعرف أنّه نُشر فعلاً على المتجر.
     *
     * يعيد `true` إن نشر شيئاً (فتُرسِل الواجهة إعلان المجموعة).
     */
    suspend fun autoPublishOwnVersion(): Boolean {
        val current = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_PUBLISHED, 0) >= current) return false
        val reference = db.collection(COLLECTION).document(DOCUMENT)
        val doc = runCatching { reference.get().await() }.getOrNull() ?: return false
        val published = (doc.getLong("latestVersionCode") ?: 0L).toInt()
        if (published >= current) {
            // الوثيقة محدَّثة أصلاً: نختم محليّاً كي لا نقرأها كل تشغيل.
            prefs.edit().putInt(KEY_PUBLISHED, current).apply()
            return false
        }
        val saved = runCatching {
            reference.set(
                mapOf(
                    "latestVersionCode" to current,
                    "minSupportedVersionCode" to
                        (doc.getLong("minSupportedVersionCode") ?: 0L).toInt(),
                    "message" to doc.getString("message").orEmpty(),
                    "storeUrl" to PLAY_URL,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "updatedBy" to AuthService.currentUser?.email.orEmpty(),
                ),
            ).await()
        }.isSuccess
        if (saved) prefs.edit().putInt(KEY_PUBLISHED, current).apply()
        return saved
    }

    private suspend fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_INTERVAL_MS) return
        val reference = db.collection(COLLECTION).document(DOCUMENT)
        // ⚠️ الخادم أوّلاً دائماً: تقديم الكاش كان يجمّد القيم على أوّل قراءة
        // إلى الأبد (الوثيقة تصير مخزَّنة فلا يُسأل الخادم بعدها قطّ، فلا يصل
        // تذكير أيّ إصدار لاحق). الكاش هنا خطّة بديلة عند فشل الشبكة فقط.
        val doc = runCatching { reference.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { reference.get(Source.CACHE).await() }
                .getOrNull()
                ?.takeIf { it.exists() }
            ?: return
        if (!doc.exists()) {
            prefs.edit().putLong(KEY_CHECKED, now).apply()
            return
        }
        prefs.edit()
            .putInt(KEY_LATEST, (doc.getLong("latestVersionCode") ?: 0L).toInt())
            .putInt(KEY_MIN, (doc.getLong("minSupportedVersionCode") ?: 0L).toInt())
            .putString(KEY_MESSAGE, doc.getString("message").orEmpty())
            .putString(KEY_STORE, doc.getString("storeUrl").orEmpty())
            .putLong(KEY_CHECKED, now)
            .apply()
    }

    companion object {
        const val COLLECTION = "app_config"

        /** وثيقة اللوحة — مستقلّة عن `android` الخاصّة بالتطبيق العام. */
        const val DOCUMENT = "admin_android"

        const val STORE_PACKAGE = "com.ali.ishaqiyin_admin"

        /** ثابت: اللوحة تبقى في الاختبار المغلق فلا يتبدّل رابط صفحتها. */
        const val PLAY_URL =
            "https://play.google.com/store/apps/details?id=$STORE_PACKAGE"

        private const val PREFS = "minbar_admin_app_config"
        private const val KEY_LATEST = "latest_version_code"
        private const val KEY_MIN = "min_supported_version_code"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STORE = "store_url"
        private const val KEY_CHECKED = "checked_at_ms"
        private const val KEY_DISMISSED = "dismissed_for"
        private const val KEY_PROMPTED = "prompted_at_ms"

        /** آخر إصدار نشره هذا الجهاز تلقائياً — كي لا يُقرأ ويُكتب كل تشغيل. */
        private const val KEY_PUBLISHED = "auto_published_version"
        private const val PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: AdminAppConfigRepository? = null

        fun get(context: Context): AdminAppConfigRepository =
            instance ?: synchronized(this) {
                instance ?: AdminAppConfigRepository(context).also { instance = it }
            }
    }
}
