package com.ali.ishaqiyin_admin.data

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.tasks.await

/**
 * 💬 المحادثات الفرديّة بين المشرفين (نمط واتساب: محادثة خاصّة لكلّ ثنائي).
 *
 * البنية:
 *   • `admin_dm_threads/{threadId}` — بيانات المحادثة:
 *     `members: [uidA, uidB]` (مرتَّبة أبجديّاً فيكون المعرّف حتميّاً)،
 *     آخر رسالة ووقتها ومرسِلها، ومؤشّرات القراءة والكتابة لكلّ عضو.
 *   • `admin_dm_threads/{threadId}/messages/{msgId}` — الرسائل بنفس شكل
 *     رسائل المجموعة تماماً (فتُعاد استعمال نفس الفقاعة والمزايا).
 *
 * الأمان: القواعد تسمح بالقراءة/الكتابة فقط لعضوَي المحادثة، ولا يصل
 * إليها تطبيق منبر العامّ إطلاقاً.
 */
object DmPaths {
    const val THREADS = "admin_dm_threads"
    const val MESSAGES = "messages"
    const val STORAGE_FOLDER = "admin_chat/dm"

    /** معرّف حتميّ للمحادثة بين طرفين (مستقلّ عن ترتيب الاستدعاء). */
    fun threadId(a: String, b: String): String {
        val ids = listOf(a, b).sorted()
        return "${ids[0]}__${ids[1]}"
    }
}

/** ملخّص محادثة فرديّة كما يظهر في قائمة «الرسائل الخاصّة». */
data class DmThread(
    val id: String,
    val members: List<String>,
    val lastText: String,
    val lastType: ChatMessageType,
    val lastSenderId: String,
    val lastAtMs: Long,
    /** آخر لحظة قراءة لكلّ عضو {uid: ms}. */
    val readAtMs: Map<String, Long>,
    /**
     * لحظة «حذف المحادثة عندي» لكلّ عضو {uid: ms}. المحادثة تختفي من قائمته
     * ما دام لا جديد بعدها، وتعود وحدها مع أوّل رسالة جديدة — نمط واتساب
     * تماماً (الحذف عندي لا يقطع الصلة، بل يمسح ما مضى).
     */
    val clearedAtMs: Map<String, Long>,
) {
    fun otherOf(myUid: String): String = members.firstOrNull { it != myUid } ?: myUid

    /** هل يعرضها قائمة [myUid]؟ (حُذفت عنده ولا جديد بعد الحذف ⇒ لا). */
    fun visibleFor(myUid: String): Boolean =
        lastAtMs > 0L && lastAtMs > (clearedAtMs[myUid] ?: 0L)

    /** عدد غير المقروء تقريبيّ: رسالة أحدث من آخر قراءتي ومن غيري. */
    fun hasUnreadFor(myUid: String): Boolean =
        lastSenderId.isNotEmpty() &&
            lastSenderId != myUid &&
            lastAtMs > (readAtMs[myUid] ?: 0L)

    /** معاينة آخر رسالة بنمط واتساب (مع بادئة «أنت:» لرسائلي). */
    fun previewFor(myUid: String): String {
        val body = if (lastType == ChatMessageType.Text) lastText else chatTypeLabel(lastType)
        if (body.isEmpty()) return "لا رسائل بعد"
        return if (lastSenderId == myUid) "أنت: $body" else body
    }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): DmThread {
            val d = doc.dataMap()
            val read = mutableMapOf<String, Long>()
            (d["readAtMs"] as? Map<*, *>)?.forEach { (k, v) ->
                if (v is Number) read[str(k)] = v.toLong()
            }
            val cleared = mutableMapOf<String, Long>()
            (d["clearedAtMs"] as? Map<*, *>)?.forEach { (k, v) ->
                if (v is Number) cleared[str(k)] = v.toLong()
            }
            return DmThread(
                id = doc.id,
                members = (d["members"] as? List<*>)?.map { str(it) } ?: emptyList(),
                lastText = str(d["lastText"]),
                lastType = chatTypeFromString(str(d["lastType"]).ifEmpty { "text" }),
                lastSenderId = str(d["lastSenderId"]),
                lastAtMs = (d["lastAtMs"] as? Number)?.toLong() ?: 0L,
                readAtMs = read,
                clearedAtMs = cleared,
            )
        }
    }
}

object DmRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    private fun thread(id: String): DocumentReference =
        db.collection(DmPaths.THREADS).document(id)

    private fun msgs(id: String): CollectionReference =
        thread(id).collection(DmPaths.MESSAGES)

    /** كلّ محادثاتي (الأحدث أولاً). */
    fun threadsStream(): Flow<List<DmThread>> {
        val me = uid
        if (me.isEmpty()) return flowOf(emptyList())
        return db.collection(DmPaths.THREADS).whereArrayContains("members", me)
            .querySnapshots()
            .map { snap ->
                snap.documents.map { DmThread.fromDoc(it) }
                    // المحذوفة عندي تختفي حتى تصل رسالة جديدة بعد حذفها.
                    .filter { it.visibleFor(me) }
                    .sortedByDescending { it.lastAtMs }
            }
    }

    /** عدد المحادثات التي تحوي رسائل لم أقرأها (شارة اللوحة). */
    fun unreadThreadsStream(): Flow<Int> {
        val me = uid
        if (me.isEmpty()) return flowOf(0)
        return threadsStream().map { list -> list.count { it.hasUnreadFor(me) } }
    }

    fun threadStream(threadId: String): Flow<DmThread?> =
        thread(threadId).docSnapshots().map { if (it.exists()) DmThread.fromDoc(it) else null }

    fun messagesStream(threadId: String, limit: Long = 60): Flow<ChatPage> {
        val me = uid
        return msgs(threadId).orderBy("sentAtMs", Query.Direction.DESCENDING).limit(limit)
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
     * يضمن وجود وثيقة المحادثة قبل أوّل رسالة (تحتاجها القواعد للتحقّق من
     * العضويّة، ويحتاجها بثّ القائمة لإظهار المحادثة فوراً).
     */
    fun ensureThread(otherUid: String): String {
        val id = DmPaths.threadId(uid, otherUid)
        val members = listOf(uid, otherUid).sorted()
        // ⚠️ بلا await: مهمّة Firestore لا تكتمل إلا بتأكيد الخادم، وانتظارها
        // كان يُجمّد فتح المحادثة كلّياً على شبكة ضعيفة. المعرّف حتميّ
        // (مشتقّ من المعرّفين) فلا حاجة لانتظار شيء، والكتابة تُطبَّق محليّاً
        // فوراً وتُرسَل تلقائياً عند توفّر الشبكة.
        thread(id).set(
            mapOf("members" to members, "createdAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )
        return id
    }

    private fun touchThread(
        threadId: String,
        otherUid: String,
        lastText: String,
        type: ChatMessageType,
    ) {
        val members = listOf(uid, otherUid).sorted()
        val now = System.currentTimeMillis()
        thread(threadId).set(
            mapOf(
                "members" to members,
                "lastText" to if (lastText.length > 120) lastText.take(120) + "…" else lastText,
                "lastType" to chatTypeToString(type),
                "lastSenderId" to uid,
                "lastAtMs" to now,
                "updatedAt" to FieldValue.serverTimestamp(),
                "readAtMs" to mapOf(uid to now),
            ),
            SetOptions.merge(),
        )
    }

    suspend fun sendText(
        threadId: String,
        otherUid: String,
        text: String,
        replyTo: ChatReplyRef? = null,
        fromGroup: ChatReplyRef? = null,
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        msgs(threadId).add(
            senderFields() + mapOf(
                "type" to "text",
                "text" to t,
                "att" to null,
                "replyTo" to replyTo?.toMap(),
                // اقتباس رسالة من المجموعة عند «الردّ بشكل خاص» (نمط واتساب).
                "fromGroup" to fromGroup?.toMap(),
                "sentAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "deleted" to false,
                "deletedBy" to "",
                "hiddenFor" to emptyList<String>(),
                "reactions" to emptyMap<String, String>(),
            ),
        )
        touchThread(threadId, otherUid, t, ChatMessageType.Text)
    }

    suspend fun sendAttachment(
        threadId: String,
        otherUid: String,
        uri: Uri,
        filename: String,
        contentType: String,
        type: ChatMessageType,
        caption: String = "",
        durationMs: Long? = null,
        waveform: List<Int>? = null,
        replyTo: ChatReplyRef? = null,
        fromGroup: ChatReplyRef? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ) {
        val up = chatUploadFile(
            uri = uri,
            filename = filename,
            contentType = contentType,
            folder = "${DmPaths.STORAGE_FOLDER}/$threadId",
            onProgress = onProgress,
            isAborted = isAborted,
        )
        msgs(threadId).add(
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
                // اقتباس المجموعة يبقى مع المرفق أيضاً («ردّ بشكل خاص» بمرفق).
                "fromGroup" to fromGroup?.toMap(),
                "sentAtMs" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
                "deleted" to false,
                "deletedBy" to "",
                "hiddenFor" to emptyList<String>(),
                "reactions" to emptyMap<String, String>(),
            ),
        )
        touchThread(
            threadId,
            otherUid,
            caption.trim().ifEmpty { filename },
            type,
        )
    }

    /**
     * إعادة توجيه رسالة إلى محادثة خاصّة — المرفق **يُنسَخ إلى مسار جديد**
     * فلا يفقد التوجيه ملفّه عند حذف الأصل عند الطرفين.
     */
    suspend fun forward(threadId: String, otherUid: String, msg: ChatMessage) {
        val att = msg.attachment?.let {
            chatCopyAttachment(it, "${DmPaths.STORAGE_FOLDER}/$threadId")
        }
        msgs(threadId).add(
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
        touchThread(
            threadId,
            otherUid,
            msg.text.ifEmpty { chatTypeLabel(msg.type) },
            msg.type,
        )
    }

    fun deleteForMe(threadId: String, messageId: String) {
        msgs(threadId).document(messageId).update("hiddenFor", FieldValue.arrayUnion(uid))
    }

    /**
     * 🧹 «مسح المحادثة عندي» في محادثة خاصّة — يُخفي كلّ رسائلها عنّي وحدي
     * (hiddenFor) دون أن يفقد الطرف الآخر شيئاً، ودون حذف من الخادم.
     */
    suspend fun clearForMe(threadId: String): Int {
        val me = uid
        if (me.isEmpty()) return 0
        var cleared = 0
        // ترقيم بمؤشّر (كما في المجموعة): بلا startAfter لا يُمسّ ما هو أقدم
        // من أحدث 300 رسالة، وصفحة كلّها مخفيّة تقدّم المؤشّر ولا توقف الحلقة.
        var last: DocumentSnapshot? = null
        while (true) {
            var query: Query = msgs(threadId)
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

    /**
     * 🗑️ **حذف المحادثة عندي** — نمط واتساب: تُخفى كلّ رسائلها عنّي وتختفي
     * من قائمتي، ولا يفقد الطرف الآخر شيئاً. وتعود المحادثة وحدها إن راسلني
     * لاحقاً (الحذف يمسح ما مضى ولا يقطع الصلة).
     */
    suspend fun deleteThreadForMe(threadId: String) {
        val me = uid
        if (me.isEmpty()) return
        clearForMe(threadId)
        // الختم **بعد** الإخفاء: أيّ رسالة تصل أثناء المسح تبقى ظاهرة لأنّ
        // زمنها أحدث من الختم، فلا تُبتلع رسالة لم يرها أحد.
        thread(threadId).set(
            mapOf("clearedAtMs" to mapOf(me to System.currentTimeMillis())),
            SetOptions.merge(),
        ).await()
    }

    /**
     * ☠️ **حذف المحادثة عند الطرفين ومعها محتواها** — للمالك وحده.
     *
     * حذفٌ فعليّ لا إخفاء: تُمحى وثائق الرسائل من Firestore وتُمحى مرفقاتها
     * من التخزين (صور/صوت/ملفّات)، ثمّ تُصفَّر معاينة المحادثة فتختفي من
     * قائمتَي الطرفين. الوثيقة نفسها تبقى (معرّفها حتميّ ويُعاد استعماله عند
     * أوّل رسالة جديدة) لكنّها تصير فارغة تماماً.
     *
     * ⚠️ بلا تراجع، ولذلك هو للمالك وحده — في القواعد قبل الواجهة.
     * يعيد عدد الرسائل التي مُحيت.
     */
    suspend fun deleteThreadForEveryone(threadId: String): Int {
        val storage = FirebaseStorage.getInstance()
        var removed = 0
        while (true) {
            // بلا startAfter: كلّ دورة تقرأ رأس ما بقي بعد حذف سابقتها.
            val snapshot = msgs(threadId)
                .orderBy("sentAtMs", Query.Direction.DESCENDING)
                .limit(200)
                .get()
                .await()
            if (snapshot.isEmpty) break
            // المرفقات أوّلاً: حذف الوثيقة قبل ملفّها يترك ملفاً يتيماً في
            // التخزين لا وثيقة تشير إليه ولا واجهة تعرضه.
            snapshot.documents.forEach { doc ->
                val path = ((doc.dataMap()["att"] as? Map<*, *>)?.get("path") as? String).orEmpty()
                if (path.isNotEmpty()) {
                    runCatching { storage.reference.child(path).delete().await() }
                }
            }
            val batch = db.batch()
            snapshot.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            removed += snapshot.size()
            if (snapshot.size() < 200) break
        }
        // تصفير المعاينة والأختام: بلاه تبقى المحادثة في القائمتين بنصّ آخر
        // رسالة وقد مُحيت فعلاً.
        thread(threadId).set(
            mapOf(
                "lastText" to "",
                "lastType" to "text",
                "lastSenderId" to "",
                "lastAtMs" to 0L,
                "clearedAtMs" to emptyMap<String, Long>(),
                "readAtMs" to emptyMap<String, Long>(),
            ),
            SetOptions.merge(),
        ).await()
        return removed
    }

    /** حذف عند الطرفين — للمرسِل نفسه فقط (لا إشراف في المحادثات الخاصّة). */
    suspend fun deleteForEveryone(threadId: String, msg: ChatMessage) {
        msgs(threadId).document(msg.id).update(
            mapOf(
                "deleted" to true,
                "deletedBy" to uid,
                "deletedAt" to FieldValue.serverTimestamp(),
                "text" to "",
                "att" to null,
            ),
        )
        // لو كانت آخر رسالة فمعاينة قائمة المحادثات يجب ألّا تبقى على نصّها.
        runCatching {
            val lastAtMs =
                (thread(threadId).get().await().dataMap()["lastAtMs"] as? Number)?.toLong() ?: 0L
            if (msg.sentAtMs >= lastAtMs) {
                thread(threadId).set(
                    mapOf("lastText" to "تم حذف هذه الرسالة", "lastType" to "text"),
                    SetOptions.merge(),
                )
            }
        }
        val path = msg.attachment?.path.orEmpty()
        if (path.isNotEmpty()) {
            runCatching { FirebaseStorage.getInstance().reference.child(path).delete().await() }
        }
    }

    fun setReaction(threadId: String, messageId: String, emoji: String?) {
        msgs(threadId).document(messageId).update(
            "reactions.$uid",
            if (emoji.isNullOrEmpty()) FieldValue.delete() else emoji,
        )
    }

    /**
     * تعليم رسالة صوتيّة «مسموعة» عند أوّل تشغيل — نظير `markListened` في
     * المجموعة (بلا انتظار، إشارة تجميليّة لا تؤخّر التشغيل).
     */
    fun markListened(threadId: String, messageId: String) {
        if (uid.isEmpty()) return
        msgs(threadId).document(messageId).update("listenedBy", FieldValue.arrayUnion(uid))
    }

    fun markRead(threadId: String) {
        if (uid.isEmpty()) return
        runCatching {
            thread(threadId).set(
                mapOf("readAtMs" to mapOf(uid to System.currentTimeMillis())),
                SetOptions.merge(),
            )
        }
    }

    fun typingTick(threadId: String) {
        if (uid.isEmpty()) return
        runCatching {
            thread(threadId).set(
                mapOf("typingAtMs" to mapOf(uid to System.currentTimeMillis())),
                SetOptions.merge(),
            )
        }
    }

    /**
     * هل الطرف الآخر يكتب الآن؟ (يقرأ خريطة typingAtMs من وثيقة المحادثة)
     * ينطفئ من تلقاء نفسه عند انقضاء النافذة: لا شيء يكتب الوثيقة بعد توقّف
     * الكتابة، فكان المؤشّر يعلق ظاهراً للأبد.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun otherTypingStream(threadId: String, otherUid: String): Flow<Boolean> =
        thread(threadId).docSnapshots().transformLatest { doc ->
            val map = doc.dataMap()["typingAtMs"] as? Map<*, *>
            val at = (map?.get(otherUid) as? Number)?.toLong() ?: 0L
            val remaining = at + TYPING_WINDOW_MS - System.currentTimeMillis()
            emit(remaining > 0)
            if (remaining > 0) {
                delay(remaining)
                emit(false)
            }
        }
}
