package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SupportKind(val key: String, val label: String) {
    Suggestion("suggestion", "اقتراح"),
    Bug("bug", "بلاغ عطل"),
    LessonHelp("lesson_help", "سؤال عن درس"),
    Idea("idea", "فكرة"),
    Supervision("supervision", "طلب إشراف"),
    ;

    companion object {
        fun of(key: String?): SupportKind =
            entries.firstOrNull { it.key == key } ?: Suggestion
    }
}

data class SupportThread(
    val id: String,
    val uid: String,
    val displayName: String,
    val kind: SupportKind,
    val status: String,
    val createdAtMs: Long,
    val lastMessageAtMs: Long,
    val lastMessagePreview: String,
    val ownerUnread: Boolean,
    val ownerReplied: Boolean,
    val messageCount: Int,
    val closed: Boolean,
    val blocked: Boolean,
    val deviceInfo: String,
) {
    val name: String get() = displayName.ifBlank { "مستخدم" }

    companion object {
        fun fromRow(o: JSONObject): SupportThread = SupportThread(
            id = o.optString("id"),
            uid = o.optString("deviceId"),
            displayName = o.optString("displayName"),
            kind = SupportKind.of(o.optString("kind")),
            status = o.optString("status"),
            createdAtMs = o.optLong("createdAtMs"),
            lastMessageAtMs = o.optLong("lastMessageAtMs"),
            lastMessagePreview = o.optString("lastMessagePreview"),
            ownerUnread = o.optBoolean("ownerUnread", false),
            ownerReplied = o.optBoolean("ownerReplied", false),
            messageCount = o.optInt("messageCount"),
            closed = o.optBoolean("closed", false),
            blocked = o.optBoolean("blocked", false),
            deviceInfo = o.optString("deviceInfo"),
        )
    }
}

data class SupportMessage(
    val id: String,
    val senderUid: String,
    val fromOwner: Boolean,
    val text: String,
    val audioPath: String,
    val imagePaths: List<String>,
    val createdAtMs: Long,
    val deviceInfo: String,
) {
    companion object {
        fun fromRow(o: JSONObject): SupportMessage {
            val images = o.optJSONArray("imagePaths") ?: JSONArray()
            return SupportMessage(
                id = o.optString("id"),
                senderUid = "",
                fromOwner = o.optBoolean("fromOwner", false),
                text = o.optString("text"),
                audioPath = o.optString("audioPath"),
                imagePaths = (0 until images.length()).map { images.optString(it) }.filter { it.isNotEmpty() },
                createdAtMs = o.optLong("createdAtMs"),
                deviceInfo = "",
            )
        }
    }
}

/**
 * «راسِل المطوّر» في اللوحة — على `minbar-api` (قرار 2026-09-10): المحادثات
 * والرسائل في D1، المرفقات في R2، والردّ يُخطر المستخدم بـFCM من الخادم.
 * القوائم استطلاعٌ (نصف دقيقة للقائمة، ربع دقيقة للمحادثة) بدل مستمعي Firestore.
 * `uid` في النماذج = معرّف جهاز المستخدم (هو ما يُحظر به).
 */
object SupportRepository {
    private const val THREADS_POLL_MS = 30_000L
    private const val MESSAGES_POLL_MS = 15_000L

    private fun JSONArray?.rows(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun fetchThreads(): List<SupportThread> =
        MinbarAdminApi.get("/admin/support/threads").optJSONArray("items").rows().map(SupportThread::fromRow)

    fun watchThreads(): Flow<List<SupportThread>> = flow {
        while (true) {
            emit(runCatching { fetchThreads() }.getOrDefault(emptyList()))
            delay(THREADS_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun watchUnreadCount(): Flow<Int> = flow {
        while (true) {
            emit(runCatching { MinbarAdminApi.get("/admin/community/counts").optInt("unreadThreads", 0) }.getOrDefault(0))
            delay(THREADS_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun watchMessages(threadId: String): Flow<List<SupportMessage>> = flow {
        while (true) {
            emit(
                runCatching {
                    MinbarAdminApi.get("/admin/support/threads/$threadId/messages").optJSONArray("items").rows()
                        .map(SupportMessage::fromRow)
                }.getOrDefault(emptyList()),
            )
            delay(MESSAGES_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun markRead(threadId: String) {
        runCatching { MinbarAdminApi.post("/admin/support/threads/$threadId/read") }
    }

    suspend fun reply(
        threadId: String,
        text: String = "",
        audioPath: String = "",
        imagePaths: List<String> = emptyList(),
    ) {
        val body = text.trim()
        require(body.isNotEmpty() || audioPath.isNotEmpty() || imagePaths.isNotEmpty()) { "لا شيء لإرساله." }
        MinbarAdminApi.post(
            "/admin/support/threads/$threadId/reply",
            JSONObject().put("text", body).put("audioKey", audioPath).put("imageKeys", JSONArray(imagePaths)),
        )
    }

    suspend fun close(threadId: String) {
        MinbarAdminApi.post("/admin/support/threads/$threadId/close")
    }

    suspend fun blockUser(uid: String, blocked: Boolean) {
        MinbarAdminApi.post("/admin/support/block", JSONObject().put("deviceId", uid).put("blocked", blocked))
    }

    /** مفتاح مرفق في R2 → رابط قراءة عبر minbar-api. */
    suspend fun mediaUrl(path: String): String =
        if (path.isEmpty()) "" else MinbarAdminApi.mediaUrl(path)

    suspend fun uploadReplyMedia(
        userUid: String,
        threadId: String,
        file: File,
        contentType: String,
    ): String {
        val safeName = file.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(80)
        val key = "support/$threadId/${System.currentTimeMillis()}_$safeName"
        MinbarAdminApi.upload("/admin/upload/$key", file, contentType)
        return key
    }
}
