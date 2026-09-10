package com.ali.ishaqiyin_admin.data

import androidx.compose.runtime.Immutable

/* ============================================================
   نماذج عرض الرسائل — بقيت بعد إلغاء دردشة الإدارة (2026-09-10) لأن صندوق
   «رسائل المستخدمين» يستعملها في فقاعاته ومشغّل صوته وعارض صوره. لا صلة لها
   بأي قاعدة بيانات: بياناتٌ محضة تبنيها الشاشة من `SupportMessage`.
   ============================================================ */

/** معرّف المرسِل حين تكون الرسالة من المالك (صندوق الدعم). */
const val OWNER_SENDER_ID = "owner"

enum class ChatMessageType { Text, Image, Video, Audio, Voice, File, Call }


fun chatTypeLabel(t: ChatMessageType): String = when (t) {
    ChatMessageType.Text -> "رسالة"
    ChatMessageType.Image -> "📷 صورة"
    ChatMessageType.Video -> "🎬 فيديو"
    ChatMessageType.Audio -> "🎵 مقطع صوتي"
    ChatMessageType.Voice -> "🎙️ رسالة صوتيّة"
    ChatMessageType.File -> "📎 ملفّ"
    ChatMessageType.Call -> "📞 مكالمة صوتيّة"
}

data class ChatAttachment(
    val url: String,
    /** مسار Storage — يلزم لحذف الملفّ عند "حذف عند الجميع". */
    val path: String,
    val name: String,
    val size: Long,
    val contentType: String,
    /** للرسائل الصوتيّة. */
    val durationMs: Long? = null,
    /**
     * 🌊 شكل موجة الرسالة الصوتيّة: حتى 40 قيمة في المدى 0..100، تُلتقط من
     * `MediaRecorder.maxAmplitude` أثناء التسجيل. `null` للرسائل القديمة —
     * الواجهة تولّد لها موجة حتميّة من معرّف الرسالة.
     */
    val waveform: List<Int>? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("url", url)
        put("path", path)
        put("name", name)
        put("size", size)
        put("contentType", contentType)
        if (durationMs != null) put("durationMs", durationMs)
        if (!waveform.isNullOrEmpty()) put("waveform", waveform)
    }

}

data class ChatReplyRef(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val preview: String,
    val type: ChatMessageType,
) {

}

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderPhoto: String,
    val type: ChatMessageType,
    val text: String,
    val attachment: ChatAttachment?,
    val replyTo: ChatReplyRef?,
    val fromGroup: ChatReplyRef?,
    val createdAtMs: Long,
    val sentAtMs: Long,
    val deleted: Boolean,
    val deletedBy: String,
    val hiddenFor: List<String>,
    /** تفاعلات الإيموجي: {uid: emoji}. */
    val reactions: Map<String, String>,
    /** كتابة محليّة لم يؤكّدها الخادم بعد. */
    val pending: Boolean,
    /**
     * 🎧 من استمع إلى هذه الرسالة الصوتيّة (نمط واتساب: الميكروفون يزرقّ عند
     * المرسِل). يُملأ بـ`arrayUnion` عند أوّل تشغيل، وغائب في الرسائل القديمة.
     */
    val listenedBy: List<String> = emptyList(),
) {
    /** رسائل المالك في صندوق الدعم هي «رسائلي». */
    val isMine: Boolean get() = senderId == OWNER_SENDER_ID

    /** نصّ معاينة مختصر (للردود والتثبيت). */
    val preview: String
        get() {
            if (deleted) return "رسالة محذوفة"
            if (type == ChatMessageType.Text) {
                return if (text.length > 90) text.take(90) + "…" else text
            }
            val label = chatTypeLabel(type)
            val name = attachment?.name.orEmpty()
            return if (type == ChatMessageType.Voice || name.isEmpty()) label else "$label $name"
        }

    fun asRef(): ChatReplyRef = ChatReplyRef(
        messageId = id,
        senderId = senderId,
        senderName = senderName,
        preview = preview,
        type = type,
    )

}

data class ChatMember(
    val uid: String,
    /** اسم Google. */
    val name: String,
    /** اسم مختار داخل المجموعة (له الأولويّة). */
    val customName: String,
    val email: String,
    /** صورة Google. */
    val photo: String,
    /** صورة شخصيّة مختارة داخل المجموعة (لها الأولويّة). */
    val customPhoto: String,
    /** owner | supervisor (دور التطبيق). */
    val role: String,
    /** '' | moderator (دور داخل المجموعة فقط). */
    val chatRole: String,
    /** أكمل إعداد الملفّ الشخصي لأوّل مرّة. */
    val profileSet: Boolean,
    val lastSeenAtMs: Long?,
    val lastActiveAtMs: Long,
    val lastReadAtMs: Long,
    val typingAtMs: Long,
) {
    val isOwner: Boolean get() = role == "owner"

    /** مشرف مجموعة: صلاحيّات إشراف داخل الدردشة فقط (لا تمسّ التطبيق). */
    val isChatModerator: Boolean get() = chatRole == "moderator"

    /** الاسم المعروض في المجموعة: المختار أولاً ثم اسم Google. */
    val displayName: String get() = customName.ifEmpty { name }

    /** الصورة المعروضة في المجموعة: المخصّصة أولاً ثم صورة Google. */
    val displayPhoto: String get() = customPhoto.ifEmpty { photo }


}

fun isVideoMime(contentType: String): Boolean = contentType.startsWith("video/")


fun chatTypeForMime(contentType: String): ChatMessageType = when {
    contentType.startsWith("image/") -> ChatMessageType.Image
    contentType.startsWith("video/") -> ChatMessageType.Video
    contentType.startsWith("audio/") -> ChatMessageType.Audio
    else -> ChatMessageType.File
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("بايت", "ك.ب", "م.ب", "غ.ب")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val digits = if (v >= 100 || i == 0) 0 else 1
    return "${String.format(java.util.Locale.US, "%.${digits}f", v)} ${units[i]}"
}

/** نوع المحتوى من امتداد الاسم (أدقّ من ContentResolver لأسماء التنزيلات). */
fun guessContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "aac" -> "audio/aac"
    "m4a", "m4b" -> "audio/mp4"
    "amr" -> "audio/amr"
    "flac" -> "audio/flac"
    "wma" -> "audio/x-ms-wma"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    "pdf" -> "application/pdf"
    "mp4" -> "video/mp4"
    "3gp", "3gpp" -> "video/3gpp"
    "mkv" -> "video/x-matroska"
    else -> "application/octet-stream"
}
