package com.ali.ishaqiyin_admin.data

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * دردشة إدارة منبر ادكصهك الجماعيّة — منقولة من لوحة نبراس بكامل مزاياها،
 * خاصّة بتطبيق الإدارة فقط (لا يقرأها تطبيق منبر العامّ إطلاقاً، وتحميها
 * قواعد Firestore/Storage بدور المالك/المشرف).
 *
 * المجموعات:
 *   • `admin_chat_messages/{msgId}` — الرسائل (زمن حقيقي عبر snapshots)
 *     مع خريطة تفاعلات `reactions: {uid: emoji}`.
 *   • `admin_chat_members/{uid}`   — الأعضاء (عضويّة تلقائيّة) + حضور
 *     (lastActiveAtMs) + قراءة (lastReadAtMs) + كتابة (typingAtMs).
 *   • `admin_chat_meta/group`      — هويّة المجموعة (اسم/صورة/قفل/تثبيت).
 */
object ChatPaths {
    const val MESSAGES = "admin_chat_messages"
    const val MEMBERS = "admin_chat_members"
    const val META = "admin_chat_meta"

    /** مجلّد مرفقات الدردشة في Storage (قاعدة خاصّة به في storage.rules). */
    const val STORAGE_FOLDER = "admin_chat/files"
    const val AVATARS_FOLDER = "admin_chat/avatars"
    const val META_FOLDER = "admin_chat/meta"
}

/** نافذة اعتبار العضو «متصلاً الآن»: آخر نبضة حضور خلال هذه المدّة. */
const val ONLINE_WINDOW_MS = 95_000L

/** نافذة اعتبار العضو «يكتب الآن…». */
const val TYPING_WINDOW_MS = 6_000L

/** التفاعلات السريعة (مطابقة لواتساب). */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * نوع الرسالة — يحدّد شكل الفقاعة في الواجهة.
 * `Call` سجلّ مكالمة صوتيّة في المحادثة الخاصّة (نمط واتساب): رسالة نصّيّة
 * بلا مرفق تكتبها `CallRepository.logCallMessage` عند انتهاء المكالمة.
 */
enum class ChatMessageType { Text, Image, Video, Audio, Voice, File, Call }

fun chatTypeFromString(s: String?): ChatMessageType = when (s) {
    "image" -> ChatMessageType.Image
    "video" -> ChatMessageType.Video
    "audio" -> ChatMessageType.Audio
    "voice" -> ChatMessageType.Voice
    "file" -> ChatMessageType.File
    "call" -> ChatMessageType.Call
    else -> ChatMessageType.Text
}

fun chatTypeToString(t: ChatMessageType): String = when (t) {
    ChatMessageType.Text -> "text"
    ChatMessageType.Image -> "image"
    ChatMessageType.Video -> "video"
    ChatMessageType.Audio -> "audio"
    ChatMessageType.Voice -> "voice"
    ChatMessageType.File -> "file"
    ChatMessageType.Call -> "call"
}

/** تسمية عربيّة مختصرة للنوع (تظهر في معاينة الردّ/التثبيت). */
fun chatTypeLabel(t: ChatMessageType): String = when (t) {
    ChatMessageType.Text -> "رسالة"
    ChatMessageType.Image -> "📷 صورة"
    ChatMessageType.Video -> "🎬 فيديو"
    ChatMessageType.Audio -> "🎵 مقطع صوتي"
    ChatMessageType.Voice -> "🎙️ رسالة صوتيّة"
    ChatMessageType.File -> "📎 ملفّ"
    ChatMessageType.Call -> "📞 مكالمة صوتيّة"
}

/** مرفق رسالة (صورة/فيديو/صوت/ملفّ) مرفوع إلى Storage. */
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

    companion object {
        fun fromMap(m: Any?): ChatAttachment? {
            if (m !is Map<*, *>) return null
            val url = str(m["url"])
            if (url.isEmpty()) return null
            // Firestore يعيد الأعداد كـ Long — نطبّعها ونقصّها إلى المدى 0..100.
            val wave = (m["waveform"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt()?.coerceIn(0, 100) }
                ?.takeIf { it.isNotEmpty() }
            return ChatAttachment(
                url = url,
                path = str(m["path"]),
                name = str(m["name"]).ifEmpty { "ملفّ" },
                size = (m["size"] as? Number)?.toLong() ?: 0L,
                contentType = str(m["contentType"]).ifEmpty { "application/octet-stream" },
                durationMs = (m["durationMs"] as? Number)?.toLong(),
                waveform = wave,
            )
        }
    }
}

/** مقتطف رسالة مُشار إليها (ردّ أو تثبيت) — يُخزَّن ذاتيّاً داخل الوثيقة. */
data class ChatReplyRef(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val preview: String,
    val type: ChatMessageType,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "messageId" to messageId,
        "senderId" to senderId,
        "senderName" to senderName,
        "preview" to preview,
        "type" to chatTypeToString(type),
    )

    companion object {
        fun fromMap(m: Any?): ChatReplyRef? {
            if (m !is Map<*, *>) return null
            val id = str(m["messageId"])
            if (id.isEmpty()) return null
            return ChatReplyRef(
                messageId = id,
                senderId = str(m["senderId"]),
                senderName = str(m["senderName"]),
                preview = str(m["preview"]),
                type = chatTypeFromString(str(m["type"]).ifEmpty { "text" }),
            )
        }
    }
}

/** رسالة دردشة. */
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
    val isMine: Boolean get() = senderId == FirebaseAuth.getInstance().currentUser?.uid

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

    companion object {
        fun fromDoc(doc: DocumentSnapshot): ChatMessage {
            val d = doc.dataMap()
            val ts = d["createdAt"]
            val sentAtMs = (d["sentAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val reactions = mutableMapOf<String, String>()
            (d["reactions"] as? Map<*, *>)?.forEach { (k, v) ->
                val emoji = str(v)
                if (emoji.isNotEmpty()) reactions[str(k)] = emoji
            }
            return ChatMessage(
                id = doc.id,
                senderId = str(d["senderId"]),
                senderName = str(d["senderName"]).ifEmpty { "مستخدم" },
                senderPhoto = str(d["senderPhoto"]),
                type = chatTypeFromString(str(d["type"]).ifEmpty { "text" }),
                text = str(d["text"]),
                attachment = ChatAttachment.fromMap(d["att"]),
                replyTo = ChatReplyRef.fromMap(d["replyTo"]),
                fromGroup = ChatReplyRef.fromMap(d["fromGroup"]),
                // الكتابات المعلَّقة (serverTimestamp لم يصل بعد) تعود لوقت الإرسال المحلّي.
                createdAtMs = if (ts is Timestamp) ts.toDate().time else sentAtMs,
                sentAtMs = sentAtMs,
                deleted = d["deleted"] == true,
                deletedBy = str(d["deletedBy"]),
                hiddenFor = (d["hiddenFor"] as? List<*>)?.map { str(it) } ?: emptyList(),
                reactions = reactions,
                pending = doc.metadata.hasPendingWrites(),
                listenedBy = (d["listenedBy"] as? List<*>)?.map { str(it) } ?: emptyList(),
            )
        }
    }
}

/** عضو في المجموعة. */
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

    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastActiveAtMs < ONLINE_WINDOW_MS

    val isTyping: Boolean
        get() = System.currentTimeMillis() - typingAtMs < TYPING_WINDOW_MS

    companion object {
        fun fromDoc(doc: DocumentSnapshot): ChatMember {
            val d = doc.dataMap()
            val ts = d["lastSeenAt"]
            fun ms(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
            return ChatMember(
                uid = doc.id,
                name = str(d["name"]).ifEmpty { "مستخدم" },
                customName = str(d["customName"]),
                email = str(d["email"]),
                photo = str(d["photo"]),
                customPhoto = str(d["customPhoto"]),
                role = str(d["role"]).ifEmpty { "supervisor" },
                chatRole = str(d["chatRole"]),
                profileSet = d["profileSet"] == true,
                lastSeenAtMs = if (ts is Timestamp) ts.toDate().time else null,
                lastActiveAtMs = ms(d["lastActiveAtMs"]),
                lastReadAtMs = ms(d["lastReadAtMs"]),
                typingAtMs = ms(d["typingAtMs"]),
            )
        }
    }
}

/** هويّة المجموعة. */
data class ChatGroupMeta(
    val name: String,
    val photoUrl: String,
    /** عند القفل لا يرسل غير المالك (نمط «إعلانات» في واتساب). */
    val locked: Boolean,
    /** الرسالة المثبَّتة (إن وُجدت). */
    val pinned: ChatReplyRef?,
) {
    companion object {
        val fallback = ChatGroupMeta(
            name = "مجموعة إدارة منبر ادكصهك",
            photoUrl = "",
            locked = false,
            pinned = null,
        )

        fun fromDoc(doc: DocumentSnapshot): ChatGroupMeta {
            val d = doc.dataMap()
            val name = str(d["name"])
            return ChatGroupMeta(
                name = name.ifEmpty { fallback.name },
                photoUrl = str(d["photoUrl"]),
                locked = d["locked"] == true,
                pinned = ChatReplyRef.fromMap(d["pinned"]),
            )
        }
    }
}

/** نتيجة رفع مرفق إلى Storage. */
data class ChatUploadResult(
    val url: String,
    val path: String,
    val contentType: String,
    val size: Long,
)

private fun sanitizeSegment(name: String): String {
    val cleaned = name.trim().filter { it.code > 0x1F && it.code != 0x7F }
    var s = cleaned.replace(Regex("[#$\\[\\]./\\\\:*?\"<>|]+"), "_")
    if (s.length > 180) s = s.take(180)
    return s.ifEmpty { "file" }
}

/**
 * رفع ملفّ من محدّد النظام أو من ملفّ محلّي (بثّ من القرص — لا يُحمَّل كاملاً
 * في الذاكرة، فيدعم الملفّات الضخمة). الترتيب الثابت: Storage أولاً ثم وثيقة
 * Firestore.
 */
suspend fun chatUploadFile(
    uri: Uri,
    filename: String,
    contentType: String = "application/octet-stream",
    folder: String = ChatPaths.STORAGE_FOLDER,
    onProgress: ((Double) -> Unit)? = null,
    isAborted: (() -> Boolean)? = null,
): ChatUploadResult {
    val storage = FirebaseStorage.getInstance()
    val safeName = sanitizeSegment(filename)
    val cleanFolder = folder.trim('/')
    val path = "$cleanFolder/${System.currentTimeMillis()}_$safeName"
    val ref = storage.reference.child(path)
    val ct = contentType.trim().ifEmpty { "application/octet-stream" }

    val task = ref.putFile(uri, StorageMetadata.Builder().setContentType(ct).build())
    var size = 0L
    task.addOnProgressListener { snapshot ->
        if (isAborted?.invoke() == true) {
            task.cancel()
            return@addOnProgressListener
        }
        size = snapshot.totalByteCount
        if (snapshot.totalByteCount > 0) {
            onProgress?.invoke(snapshot.bytesTransferred.toDouble() / snapshot.totalByteCount * 100)
        }
    }

    try {
        val snapshot = task.await()
        size = snapshot.totalByteCount
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_CANCELED) error("تمّ إلغاء الرفع.")
        throw e
    }

    if (isAborted?.invoke() == true) error("تمّ إلغاء الرفع.")

    val url = ref.downloadUrl.await().toString()
    return ChatUploadResult(url = url, path = path, contentType = ct, size = size)
}

/**
 * نسخ مرفق إلى مسار Storage جديد عند **إعادة التوجيه**: كلّ نسخة تملك
 * ملفّها فيصبح «الحذف عند الجميع» للأصل آمناً ولا يُفرِّغ النسخ المُوجَّهة.
 * لا يوفّر Firebase نسخاً خادميّاً، فنستعمل النسخة المحليّة (تُنزَّل إن
 * لزم — وهي منزَّلة أصلاً في الغالب) ثمّ نرفعها من جديد.
 */
suspend fun chatCopyAttachment(att: ChatAttachment, folder: String): ChatAttachment {
    val file = ChatMediaStore.download(att)
        ?: error("تعذّر تحضير المرفق لإعادة التوجيه — تحقّق من الاتصال ثمّ أعد المحاولة.")
    val up = chatUploadFile(
        uri = Uri.fromFile(file),
        filename = att.name,
        contentType = att.contentType,
        folder = folder,
    )
    return att.copy(
        url = up.url,
        path = up.path,
        size = if (up.size > 0L) up.size else att.size,
        contentType = up.contentType,
    )
}

object ChatRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private val messages: CollectionReference get() = db.collection(ChatPaths.MESSAGES)
    private val members: CollectionReference get() = db.collection(ChatPaths.MEMBERS)
    private val meta: DocumentReference get() = db.collection(ChatPaths.META).document("group")

    private val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ─── الرسائل ─────────────────────────────────────────────

    /**
     * بثّ آخر [limit] رسالة (تنازليّاً) — الرسائل المحذوفة "عندي" تُرشَّح هنا.
     *
     * الترتيب على `sentAtMs` (طابع محلّي يُكتب مع كلّ رسالة) وليس على
     * serverTimestamp: الحقل موجود فوراً حتى في الكتابات المعلَّقة، فتظهر
     * رسالتك لحظيّاً وتعمل الدردشة دون انقطاع أثناء ضعف الشبكة.
     */
    fun messagesStream(limit: Long = 60): Flow<ChatPage> {
        val me = uid
        return messages.orderBy("sentAtMs", Query.Direction.DESCENDING).limit(limit)
            .querySnapshots()
            .map { snap ->
                ChatPage(
                    messages = snap.documents
                        .map { ChatMessage.fromDoc(it) }
                        .filter { !it.hiddenFor.contains(me) },
                    rawSize = snap.size(),
                )
            }
    }

    private fun senderFields(): Map<String, Any?> {
        val u = FirebaseAuth.getInstance().currentUser
        val name = u?.displayName?.takeIf { it.isNotBlank() }
            ?: u?.email?.substringBefore('@') ?: "مستخدم"
        return mapOf(
            "senderId" to u?.uid.orEmpty(),
            "senderName" to name,
            "senderPhoto" to u?.photoUrl?.toString().orEmpty(),
        )
    }

    /**
     * إرسال نصّ — **بلا انتظار تأكيد الخادم**: Firestore يطبّق الكتابة
     * محليّاً فتظهر الرسالة فوراً بعلامة الانتظار، ويعيد إرسالها تلقائياً
     * عند عودة الشبكة. انتظار التأكيد كان يجمّد زرّ الإرسال على شبكة ضعيفة.
     */
    fun sendText(text: String, replyTo: ChatReplyRef? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        messages.add(
            senderFields() + mapOf(
                "type" to "text",
                "text" to t,
                "att" to null,
                "replyTo" to replyTo?.toMap(),
                "sentAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "deleted" to false,
                "deletedBy" to "",
                "hiddenFor" to emptyList<String>(),
                "reactions" to emptyMap<String, String>(),
            ),
        )
    }

    /** رفع ملفّ ثم إرسال رسالة تشير إليه. */
    suspend fun sendAttachment(
        uri: Uri,
        filename: String,
        contentType: String,
        type: ChatMessageType,
        caption: String = "",
        durationMs: Long? = null,
        waveform: List<Int>? = null,
        replyTo: ChatReplyRef? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ) {
        val up = chatUploadFile(
            uri = uri,
            filename = filename,
            contentType = contentType,
            folder = ChatPaths.STORAGE_FOLDER,
            onProgress = onProgress,
            isAborted = isAborted,
        )
        messages.add(
            senderFields() + mapOf(
                "type" to chatTypeToString(type),
                "text" to caption.trim(),
                "att" to ChatAttachment(
                    url = up.url,
                    path = up.path,
                    name = filename,
                    size = up.size,
                    contentType = up.contentType,
                    durationMs = durationMs,
                    waveform = waveform,
                ).toMap(),
                "replyTo" to replyTo?.toMap(),
                "sentAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "deleted" to false,
                "deletedBy" to "",
                "hiddenFor" to emptyList<String>(),
                "reactions" to emptyMap<String, String>(),
            ),
        )
    }

    /**
     * إعادة توجيه رسالة إلى المجموعة — المرفق **يُنسَخ إلى مسار جديد** كي لا
     * يفقد التوجيه ملفّه عند حذف الرسالة الأصليّة عند الجميع.
     */
    suspend fun forward(msg: ChatMessage) {
        val att = msg.attachment?.let { chatCopyAttachment(it, ChatPaths.STORAGE_FOLDER) }
        messages.add(
            senderFields() + mapOf(
                "type" to chatTypeToString(msg.type),
                "text" to msg.text,
                "att" to att?.toMap(),
                "replyTo" to null,
                "sentAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "deleted" to false,
                "deletedBy" to "",
                "hiddenFor" to emptyList<String>(),
                "reactions" to emptyMap<String, String>(),
            ),
        )
    }

    /** حذف عندي فقط — تبقى الرسالة ظاهرة للبقيّة (مطابق لواتساب). */
    fun deleteForMe(messageId: String) {
        messages.document(messageId).update("hiddenFor", FieldValue.arrayUnion(uid))
    }

    /**
     * حذف عند الجميع — للمرسِل نفسه أو للمالك/مشرف المجموعة. تُمسح الحمولة
     * ويُحذف مرفق Storage (بأفضل جهد) وتبقى وثيقة "تم حذف الرسالة".
     */
    suspend fun deleteForEveryone(msg: ChatMessage) {
        messages.document(msg.id).update(
            mapOf(
                "deleted" to true,
                "deletedBy" to uid,
                "deletedAt" to FieldValue.serverTimestamp(),
                "text" to "",
                "att" to null,
            ),
        )
        val path = msg.attachment?.path.orEmpty()
        if (path.isNotEmpty()) {
            // أفضل جهد — الوثيقة حُذفت منطقيّاً على أيّ حال.
            runCatching { FirebaseStorage.getInstance().reference.child(path).delete().await() }
        }
    }

    /**
     * 🧹 «مسح المحادثة عندي» (نمط واتساب): يُخفي كلّ الرسائل عنّي وحدي عبر
     * إضافة معرّفي إلى `hiddenFor` — لا تتأثّر نسخة بقيّة الأعضاء إطلاقاً
     * ولا تُحذف أيّ رسالة من الخادم. يعمل على دفعات كي لا يفشل على
     * المحادثات الطويلة أو الشبكات الضعيفة، ويعيد عدد ما مُسح.
     */
    suspend fun clearForMe(): Int {
        val me = uid
        if (me.isEmpty()) return 0
        var cleared = 0
        // ترقيم بمؤشّر: بلا startAfter كانت الحلقة تعيد أحدث 300 رسالة نفسها
        // فلا يُمسّ ما هو أقدم منها. وصفحة كلّها مخفيّة تقدّم المؤشّر ولا توقف.
        var last: DocumentSnapshot? = null
        while (true) {
            var query: Query = messages
                .orderBy("sentAtMs", Query.Direction.DESCENDING)
                .limit(300)
            last?.let { query = query.startAfter(it) }
            val snapshot = query.get().await()
            if (snapshot.isEmpty) return cleared
            last = snapshot.documents.lastOrNull()
            val targets = snapshot.documents.filter { doc ->
                val hidden = doc.get("hiddenFor") as? List<*> ?: emptyList<Any>()
                !hidden.contains(me)
            }
            if (targets.isNotEmpty()) {
                val batch = db.batch()
                targets.forEach {
                    batch.update(it.reference, "hiddenFor", FieldValue.arrayUnion(me))
                }
                batch.commit().await()
                cleared += targets.size
            }
            if (snapshot.size() < 300) return cleared
        }
    }

    // ─── التفاعلات (إيموجي مثل واتساب) ───────────────────────

    /** ضبط تفاعلي على رسالة: emoji جديد يستبدل السابق، و null يزيله. */
    fun setReaction(messageId: String, emoji: String?) {
        messages.document(messageId).update(
            "reactions.$uid",
            if (emoji.isNullOrEmpty()) FieldValue.delete() else emoji,
        )
    }

    // ─── الاستماع للرسائل الصوتيّة ───────────────────────────

    /**
     * تعليم رسالة صوتيّة «مسموعة» عند أوّل تشغيل (يزرقّ ميكروفونها عند
     * المرسِل). بلا انتظار — إشارة تجميليّة يجب ألّا تؤخّر التشغيل، وتُعاد
     * تلقائيّاً عند عودة الشبكة كبقيّة كتابات Firestore.
     */
    fun markListened(messageId: String) {
        if (uid.isEmpty()) return
        messages.document(messageId).update("listenedBy", FieldValue.arrayUnion(uid))
    }

    // ─── هويّة المجموعة ──────────────────────────────────────
    // الاسم والصورة: **يعدّلهما أيّ عضو معتمَد** (مالكاً كان أو مشرفاً) — مثل
    // واتساب. أمّا القفل فللمالك وحده، والتثبيت للمالك ومشرف المجموعة ⭐.

    fun metaStream(): Flow<ChatGroupMeta> = meta.docSnapshots()
        .map { if (it.exists()) ChatGroupMeta.fromDoc(it) else ChatGroupMeta.fallback }

    /** اسم من قام بآخر تعديل — يُعرض في معلومات المجموعة (شفافيّة). */
    private fun editorFields(): Map<String, Any?> {
        val u = FirebaseAuth.getInstance().currentUser
        val name = u?.displayName?.takeIf { it.isNotBlank() }
            ?: u?.email?.substringBefore('@') ?: "مشرف"
        return mapOf(
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedByUid" to u?.uid.orEmpty(),
            "updatedByName" to name,
        )
    }

    fun setGroupName(name: String) {
        meta.set(mapOf("name" to name.trim()) + editorFields(), SetOptions.merge())
    }

    /** رفع صورة المجموعة وتحديث الهويّة (متاح لكلّ المشرفين). */
    suspend fun setGroupPhoto(uri: Uri, filename: String) {
        val up = chatUploadFile(
            uri = uri,
            filename = filename,
            contentType = guessContentType(filename),
            folder = ChatPaths.META_FOLDER,
        )
        meta.set(
            mapOf("photoUrl" to up.url, "photoPath" to up.path) + editorFields(),
            SetOptions.merge(),
        ).await()
    }

    /** إزالة صورة المجموعة (العودة للأيقونة الافتراضيّة). */
    fun clearGroupPhoto() {
        meta.set(mapOf("photoUrl" to "", "photoPath" to "") + editorFields(), SetOptions.merge())
    }

    fun setLocked(locked: Boolean) {
        meta.set(
            mapOf("locked" to locked, "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )
    }

    /** تثبيت رسالة (null لإلغاء التثبيت) — للمالك 👑 ومشرف المجموعة ⭐. */
    fun setPinned(ref: ChatReplyRef?) {
        meta.set(
            mapOf("pinned" to ref?.toMap(), "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )
    }

    // ─── الأعضاء والحضور ─────────────────────────────────────

    /**
     * إضافة/تحديث العضو الحالي تلقائيّاً — تُستدعى عند فتح اللوحة (أي أنّ
     * كلّ من يدخل تطبيق الإدارة ينضمّ للمجموعة دون أيّ خطوة يدويّة).
     * صامتة عند الفشل: انقطاع الشبكة أو قواعد لم تُنشر بعد يجب ألّا يُسقط
     * اللوحة — شاشة الدردشة تعرض الخطأ الفعلي عند فتحها.
     */
    suspend fun upsertSelf(role: String? = null) {
        val u = FirebaseAuth.getInstance().currentUser ?: return
        val name = u.displayName?.takeIf { it.isNotBlank() }
            ?: u.email?.substringBefore('@') ?: "مستخدم"
        runCatching {
            val data = buildMap<String, Any?> {
                put("name", name)
                put("email", u.email.orEmpty().lowercase())
                put("photo", u.photoUrl?.toString().orEmpty())
                if (role != null) put("role", role)
                put("lastSeenAt", FieldValue.serverTimestamp())
                put("lastActiveAtMs", System.currentTimeMillis())
            }
            // بلا await: الانضمام يجب ألّا يؤخّر فتح اللوحة أو الدردشة.
            members.document(u.uid).set(data, SetOptions.merge())
            members.document(u.uid).set(
                mapOf("joinedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
        }
    }

    /** نبضة حضور دوريّة — تجعل العضو «متصلاً الآن» لبقيّة المجموعة. */
    fun presenceTick() {
        if (uid.isEmpty()) return
        runCatching {
            members.document(uid).set(
                mapOf(
                    "lastActiveAtMs" to System.currentTimeMillis(),
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
        }
    }

    /** تحديث مؤشّر القراءة — كلّ رسالة أقدم من هذا الطابع تُعتبر مقروءة منّي. */
    fun markRead() {
        if (uid.isEmpty()) return
        runCatching {
            members.document(uid).set(
                mapOf("lastReadAtMs" to System.currentTimeMillis()),
                SetOptions.merge(),
            )
        }
    }

    /** إشارة «يكتب الآن…» (تُستدعى مقنَّنة أثناء الكتابة). */
    fun typingTick() {
        if (uid.isEmpty()) return
        runCatching {
            members.document(uid).set(
                mapOf("typingAtMs" to System.currentTimeMillis()),
                SetOptions.merge(),
            )
        }
    }

    /** اختيار صورة شخصيّة للمجموعة (تظهر مع اسمي في الدردشة تلقائيّاً). */
    suspend fun setMyPhoto(uri: Uri, filename: String) {
        val up = chatUploadFile(
            uri = uri,
            filename = filename,
            contentType = guessContentType(filename),
            folder = ChatPaths.AVATARS_FOLDER,
        )
        members.document(uid).set(
            mapOf("customPhoto" to up.url, "customPhotoPath" to up.path),
            SetOptions.merge(),
        )
    }

    /** إزالة صورتي المخصّصة (يعود لصورة Google). */
    fun clearMyPhoto() {
        members.document(uid).set(
            mapOf("customPhoto" to "", "customPhotoPath" to ""),
            SetOptions.merge(),
        )
    }

    /** تغيير اسمي المعروض في المجموعة (ويُعلِّم اكتمال إعداد الملفّ الشخصي). */
    fun setMyName(name: String) {
        members.document(uid).set(
            mapOf("customName" to name.trim(), "profileSet" to true),
            SetOptions.merge(),
        )
    }

    /** جلب وثيقتي (لملء حوار الملفّ الشخصي وفحص أوّل مرّة). */
    suspend fun fetchSelf(): ChatMember? {
        if (uid.isEmpty()) return null
        // الكاش أوّلاً: فتح اللوحة يجب ألّا ينتظر ردّ الخادم على شبكة ضعيفة.
        val cached = runCatching {
            members.document(uid).get(com.google.firebase.firestore.Source.CACHE).await()
        }.getOrNull()?.takeIf { it.exists() }
        // لا نثق بالكاش إلّا إذا حمل إثباتاً صريحاً بأنّ الملفّ الشخصي مُعدّ:
        // upsertSelf تكتب محليّاً بلا انتظار، فلو اكتفينا بوجود الوثيقة لعاد
        // حوار «اسمك وصورتك» لمشرف قديم بعد كلّ تثبيت جديد (كاش فارغ).
        if (cached != null && cached.dataMap()["profileSet"] == true) {
            return ChatMember.fromDoc(cached)
        }
        val fromServer = runCatching {
            val doc = members.document(uid).get().await()
            if (doc.exists()) ChatMember.fromDoc(doc) else null
        }.getOrNull()
        // تعذّر الخادم (دون اتصال) ⇒ نعود للكاش بدل حجب الشاشة.
        return fromServer ?: cached?.let { ChatMember.fromDoc(it) }
    }

    /** تعيين/إزالة مشرف مجموعة ⭐ (صلاحيّات داخل الدردشة فقط) — للمالك. */
    fun setChatRole(memberUid: String, role: String?) {
        members.document(memberUid).set(
            mapOf("chatRole" to (if (role.isNullOrEmpty()) FieldValue.delete() else role)),
            SetOptions.merge(),
        )
    }

    /**
     * إزالة عضو من المجموعة بالبريد — يستدعيها المالك عند حذف/حظر مشرف من
     * شاشة «الحساب والمشرفون» (الإزالة التلقائيّة المقابلة للإضافة التلقائيّة).
     */
    suspend fun removeMemberByEmail(email: String) {
        val e = email.trim().lowercase()
        if (e.isEmpty()) return
        // أفضل جهد — فقدان الصلاحيّة يمنع وصوله للدردشة على أيّ حال.
        runCatching {
            val snap = members.whereEqualTo("email", e).get().await()
            snap.documents.forEach { it.reference.delete().await() }
        }
    }

    fun membersStream(): Flow<List<ChatMember>> = members.orderBy("name")
        .querySnapshots()
        .map { snap -> snap.documents.map { ChatMember.fromDoc(it) } }

    /**
     * بثّ عدد الرسائل غير المقروءة (تقريبي، حتى 60 رسالة) — لشارة اللوحة.
     * مؤشّر القراءة يُدمج كتدفّق حيّ (لا `get()` لمرّة واحدة) فتُصفَّر الشارة
     * فور كتابة `markRead` محليّاً.
     */
    fun unreadCountStream(): Flow<Int> {
        val me = uid
        if (me.isEmpty()) return kotlinx.coroutines.flow.flowOf(0)
        return combine(
            messages.orderBy("sentAtMs", Query.Direction.DESCENDING).limit(60).querySnapshots(),
            members.document(me).docSnapshots(),
        ) { snap, meDoc ->
            val lastRead = (meDoc.dataMap()["lastReadAtMs"] as? Number)?.toLong() ?: 0L
            snap.documents
                .map { ChatMessage.fromDoc(it) }
                .count {
                    !it.deleted &&
                        it.senderId != me &&
                        !it.hiddenFor.contains(me) &&
                        it.sentAtMs > lastRead
                }
        }
    }
}

/**
 * صفحة رسائل: المعروض بعد ترشيح المخفيّ محليّاً، و[rawSize] حجم ما أعاده
 * الخادم فعلاً. الترقيم يقارن بـ[rawSize] لا بحجم القائمة المرشَّحة — وإلّا
 * توقّف تحميل الأقدم إلى الأبد بمجرّد إخفاء رسالة واحدة.
 */
data class ChatPage(
    val messages: List<ChatMessage> = emptyList(),
    val rawSize: Int = 0,
)

/** تخمين MIME من الامتداد (لاختيار نوع الفقاعة وقاعدة Storage الملائمة). */
fun guessContentType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"
        "docx" ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

/** نوع الرسالة الملائم لمرفق بحسب MIME. */
fun chatTypeForMime(contentType: String): ChatMessageType = when {
    contentType.startsWith("image/") -> ChatMessageType.Image
    contentType.startsWith("video/") -> ChatMessageType.Video
    contentType.startsWith("audio/") -> ChatMessageType.Audio
    else -> ChatMessageType.File
}

/** تنسيق حجم ملفّ للعرض. */
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
