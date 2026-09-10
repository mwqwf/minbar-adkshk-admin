package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.Uri
import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 🛡️ حارس تكرار الرفع: بصمة الملفّ قبل الطابور تُسأل عنها القاعدة —
 * إن كان لدرسٍ منشورٍ البايتات نفسها فالرفع تكرارٌ لا جديد.
 *
 * على `minbar-api` منذ 2026-09-10: استعلام واحد على `sha256` و`source_sha256`
 * معاً (الأولى بصمة الملفّ المُقدَّم، والثانية بصمة أصله قبل الترميز).
 */
object DuplicateAudioGuard {
    data class Match(
        val sha256: String,
        val fileName: String,
        val existingTitle: String,
        val existingSection: String,
        val existingDurationSeconds: Long,
    )

    fun sha256Of(context: Context, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }.getOrNull()

    /** أوّل ملفّ من [uris] له درسٌ موجود ببصمته؛ `null` إن لم يتكرّر شيء. */
    suspend fun firstMatch(
        context: Context,
        uris: List<Uri>,
        names: List<String> = emptyList(),
        /** بصمات أقرّ المشرف بتكرارها فلا يُسأل عنها ثانية. */
        skipShas: List<String> = emptyList(),
    ): Match? = withContext(Dispatchers.IO) {
        uris.forEachIndexed { index, uri ->
            val sha = sha256Of(context, uri) ?: return@forEachIndexed
            if (sha in skipShas) return@forEachIndexed
            val found = runCatching { MinbarAdminApi.get("/admin/lessons/by-sha?sha=$sha") }.getOrNull()
            if (found != null && found.optBoolean("found", false)) {
                return@withContext Match(
                    sha256 = sha,
                    fileName = names.getOrNull(index).orEmpty(),
                    existingTitle = found.optString("title"),
                    existingSection = found.optString("section"),
                    existingDurationSeconds = found.optLong("durationSeconds"),
                )
            }
        }
        null
    }
}
