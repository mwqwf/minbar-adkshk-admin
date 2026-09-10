package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import org.json.JSONObject

data class UpdateConfig(
    val latestVersionCode: Int = 0,
    val minSupportedVersionCode: Int = 0,
    val message: String = "",
    val storeUrl: String = "",
)

/**
 * إعداد تذكير التحديث للتطبيق العام واللوحة — في `minbar-api` (`app_config`):
 * القراءة عامة (`/v1/config/{key}`) والكتابة للمشرفين (`/admin/config/{key}`).
 */
object UpdateConfigRepository {
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

    suspend fun load(target: Target = Target.PublicApp): UpdateConfig {
        val json = try {
            MinbarAdminApi.get("/v1/config/${target.document}", auth = false)
        } catch (e: MinbarAdminApi.ApiException) {
            if (e.code == 404) return UpdateConfig() else throw e
        }
        return UpdateConfig(
            latestVersionCode = json.optInt("latestVersionCode", 0),
            minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0),
            message = json.optString("message"),
            storeUrl = json.optString("storeUrl"),
        )
    }

    suspend fun save(config: UpdateConfig, target: Target = Target.PublicApp) {
        MinbarAdminApi.put(
            "/admin/config/${target.document}",
            JSONObject()
                .put("latestVersionCode", config.latestVersionCode)
                .put("minSupportedVersionCode", config.minSupportedVersionCode)
                .put("message", config.message)
                .put("storeUrl", config.storeUrl.ifBlank { target.storeUrl })
                .put("updatedBy", AuthService.currentUser?.email.orEmpty()),
        )
    }
}
