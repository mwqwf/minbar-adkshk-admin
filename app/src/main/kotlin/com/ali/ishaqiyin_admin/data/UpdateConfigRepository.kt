package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** إعداد تذكير التحديث الذي يقرؤه تطبيق منبر العام. */
data class UpdateConfig(
    val latestVersionCode: Int = 0,
    val minSupportedVersionCode: Int = 0,
    val message: String = "",
    val storeUrl: String = "",
)

/**
 * ⬆️ وثيقة واحدة (`app_config/android`) يقارن بها التطبيق العام إصدارَه.
 *
 * القراءة عامّة والكتابة **للمالك وحده** بقاعدة Firestore — ضبط حدّ أدنى
 * للإصدار قرارُ إصدارٍ لا قرار محتوى، وخطؤه يُظهر تذكيراً لكل المستخدمين.
 */
object UpdateConfigRepository {
    private const val COLLECTION = "app_config"

    /**
     * وثيقتان مستقلّتان: التطبيق العام ولوحة الإدارة. خلطهما كان يعني أن
     * ترقيم إصدار أحدهما يُظهر تذكيراً كاذباً لمستخدمي الآخر.
     */
    enum class Target(val document: String, val label: String, val storeUrl: String) {
        PublicApp(
            document = "android",
            label = "التطبيق العام",
            storeUrl = "https://play.google.com/store/apps/details?id=com.ali.menbaradkshk",
        ),
        AdminApp(
            document = AdminAppConfigRepository.DOCUMENT,
            label = "لوحة الإدارة",
            storeUrl = AdminAppConfigRepository.PLAY_URL,
        ),
    }

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    suspend fun load(target: Target = Target.PublicApp): UpdateConfig {
        val doc = db.collection(COLLECTION).document(target.document).get().await()
        if (!doc.exists()) return UpdateConfig()
        return UpdateConfig(
            latestVersionCode = (doc.getLong("latestVersionCode") ?: 0L).toInt(),
            minSupportedVersionCode = (doc.getLong("minSupportedVersionCode") ?: 0L).toInt(),
            message = doc.getString("message").orEmpty(),
            storeUrl = doc.getString("storeUrl").orEmpty(),
        )
    }

    suspend fun save(config: UpdateConfig, target: Target = Target.PublicApp) {
        // `set` لا `update`: الوثيقة قد لا تكون موجودة في أوّل ضبط.
        // ولا نكتب مفاتيح خارج ما تسمح به القاعدة (hasOnly) وإلا رُفضت.
        db.collection(COLLECTION).document(target.document).set(
            mapOf(
                "latestVersionCode" to config.latestVersionCode,
                "minSupportedVersionCode" to config.minSupportedVersionCode,
                "message" to config.message,
                "storeUrl" to config.storeUrl.ifBlank { target.storeUrl },
                "updatedAt" to FieldValue.serverTimestamp(),
                "updatedBy" to AuthService.currentUser?.email.orEmpty(),
            ),
        ).await()
    }
}
