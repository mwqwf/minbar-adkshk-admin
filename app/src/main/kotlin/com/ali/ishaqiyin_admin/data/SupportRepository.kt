package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * 📬 نوع رسالة المستخدم — نفس القيم النصّيّة التي يكتبها الخادم حرفاً بحرف.
 * ⚠️ نصّها العربيّ يُعرض للمالك، فلا كلمة إنجليزيّة واحدة في الواجهة.
 */
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

/** خيط محادثة واحد بين مستخدم من التطبيق العام والمالك. */
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
    /** وصف جهاز المرسِل — يرافق بلاغ العطل، وقد يأتي على أوّل رسالة بدله. */
    val deviceInfo: String,
) {
    val name: String get() = displayName.ifBlank { "مستخدم" }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): SupportThread {
            val d = doc.dataMap()
            return SupportThread(
                id = doc.id,
                uid = str(d["uid"]),
                displayName = str(d["displayName"]),
                kind = SupportKind.of(str(d["kind"])),
                status = str(d["status"]),
                createdAtMs = parseDateMs(d["createdAtMs"]),
                lastMessageAtMs = parseDateMs(d["lastMessageAtMs"]),
                lastMessagePreview = str(d["lastMessagePreview"]),
                // ⚠️ الخادم يكتبه **عدداً** (`increment(1)` ثم `0` عند القراءة)
                // لا قيمةً منطقيّة، فمقارنته بـ`true` كانت تُرجع false دائماً
                // فلا تظهر شارة «غير مقروء» على محادثةٍ واردة أبداً.
                ownerUnread = int(d["ownerUnread"]) > 0,
                ownerReplied = d["ownerReplied"] == true,
                messageCount = int(d["messageCount"]),
                closed = d["closed"] == true,
                blocked = d["blocked"] == true,
                deviceInfo = str(d["deviceInfo"]),
            )
        }
    }
}

/**
 * رسالة واحدة داخل الخيط.
 *
 * ⚠️ الصوت والصور تُحفظ **مسارات تخزين** لا روابط: الرابط الموقَّت ينتهي،
 * والمسار يبقى — ولذلك يُترجَم إلى رابط عند العرض وحده (وبمخزون روابط
 * مشترك فلا يتكرّر الطلب لكلّ فتح للشاشة).
 */
data class SupportMessage(
    val id: String,
    val senderUid: String,
    val fromOwner: Boolean,
    val text: String,
    val audioPath: String,
    val imagePaths: List<String>,
    val createdAtMs: Long,
    /** وصف الجهاز — يُرسله التطبيق العام مع بلاغ العطل وحده. */
    val deviceInfo: String,
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): SupportMessage {
            val d = doc.dataMap()
            return SupportMessage(
                id = doc.id,
                senderUid = str(d["senderUid"]),
                fromOwner = d["fromOwner"] == true,
                text = str(d["text"]),
                audioPath = str(d["audioPath"]),
                imagePaths = (d["imagePaths"] as? List<*>).orEmpty()
                    .map { str(it) }.filter { it.isNotEmpty() },
                createdAtMs = parseDateMs(d["createdAtMs"]),
                deviceInfo = str(d["deviceInfo"]),
            )
        }
    }
}

/** طلب إشراف: ثلاثة أسئلة يجيب عنها صاحب الطلب في التطبيق العام. */
data class SupervisionRequest(
    val id: String,
    val uid: String,
    val displayName: String,
    /** «من أنت؟» */
    val about: String,
    /** «ما صلتك بالمنبر؟» */
    val relation: String,
    /** «ماذا تريد أن تعمل؟» */
    val wants: String,
    val status: String,
    val createdAtMs: Long,
    val threadId: String,
    /** ملاحظة المالك المرافقة للقرار (إن كتبها). */
    val note: String,
) {
    val isPending: Boolean get() = status.isEmpty() || status == "pending"
    val name: String get() = displayName.ifBlank { "مستخدم" }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): SupervisionRequest {
            val d = doc.dataMap()
            return SupervisionRequest(
                id = doc.id,
                uid = str(d["uid"]),
                displayName = str(d["displayName"]),
                about = str(d["about"]),
                relation = str(d["relation"]),
                wants = str(d["wants"]),
                status = str(d["status"]),
                createdAtMs = parseDateMs(d["createdAtMs"]),
                threadId = str(d["threadId"]),
                note = str(d["note"]),
            )
        }
    }
}

/**
 * 📬 «رسائل المستخدمين» — صندوق المالك وحده.
 *
 * كلّ فعل يغيّر شيئاً يمرّ بدالّة سحابيّة (الردّ، الإغلاق، الحظر، قرار طلب
 * الإشراف): القواعد على الخادم هي الحارس، والواجهة لا تكتب في وثائق الخيوط
 * إلّا علامة «قرأتُها» — وهي وحدها ما يُبتلع فشله بلا إزعاج المالك.
 */
object SupportRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    const val THREADS = "support_threads"
    const val MESSAGES = "messages"
    const val REQUESTS = "supervision_requests"

    /**
     * مساحة مرفقات الخيط: `support/{uid}/{threadId}/…`.
     *
     * ⛔ الشكل ليس اختيارياً: قواعد التخزين تسمح بالقراءة **للمالك وصاحب
     * الخيط وحدهما** وتشتقّ ذلك من `uid` في المسار — فأيّ شكل آخر يجعل
     * الملفّ غير مقروء لأحد.
     */
    private const val MEDIA_DIR = "support"

    /** الخيوط كلّها، الأحدث رسالةً أوّلاً (وغير المقروء يعلو داخل الواجهة). */
    fun watchThreads(): Flow<List<SupportThread>> =
        db.collection(THREADS)
            .orderBy("lastMessageAtMs", Query.Direction.DESCENDING)
            .querySnapshots()
            .map { snap -> snap.documents.map { SupportThread.fromDoc(it) } }
            // اللقطة تصل على الخيط الرئيسيّ: التحليل والفرز لا يقعان عليه.
            .flowOn(Dispatchers.Default)

    /** عدد الخيوط التي لم يقرأها المالك — شارة اللوحة و«مهامّي اليوم». */
    fun watchUnreadCount(): Flow<Int> =
        // `ownerUnread` عددٌ لا قيمة منطقيّة (انظر `fromDoc`)، فالاستعلام
        // بـ`== true` كان يُرجع صفراً دائماً وتبقى اللوحة بلا شارة.
        db.collection(THREADS).whereGreaterThan("ownerUnread", 0)
            .querySnapshots().map { it.size() }
            .flowOn(Dispatchers.Default)

    /** رسائل خيط واحد بترتيب زمنيّ صاعد (الأقدم أعلى، كالدردشة). */
    fun watchMessages(threadId: String): Flow<List<SupportMessage>> =
        db.collection(THREADS).document(threadId).collection(MESSAGES)
            .orderBy("createdAtMs", Query.Direction.ASCENDING)
            .querySnapshots()
            .map { snap -> snap.documents.map { SupportMessage.fromDoc(it) } }
            .flowOn(Dispatchers.Default)

    /**
     * طلبات الإشراف — المعلَّق أوّلاً ثمّ الأحدث.
     *
     * الترتيب على الخادم بـ`createdAtMs` تنازلياً (فهرسه منشور)، وتقديمُ
     * المعلَّق يتمّ هنا: هو ترتيب عرض لا استعلام، ودمجه في الاستعلام كان
     * سيحتاج فهرساً مركّباً ثانياً بلا فائدة.
     */
    fun watchSupervisionRequests(): Flow<List<SupervisionRequest>> =
        db.collection(REQUESTS)
            .orderBy("createdAtMs", Query.Direction.DESCENDING)
            .querySnapshots()
            .map { snap ->
                snap.documents.map { SupervisionRequest.fromDoc(it) }
                    .sortedByDescending { it.isPending }
            }
            .flowOn(Dispatchers.Default)

    /** عدد طلبات الإشراف المعلَّقة — تُعرض شارةً على تبويبها. */
    fun watchPendingRequestsCount(): Flow<Int> =
        db.collection(REQUESTS).whereEqualTo("status", "pending")
            .querySnapshots().map { it.size() }
            .flowOn(Dispatchers.Default)

    /**
     * تعليم الخيط مقروءاً عند فتحه.
     *
     * ⚠️ عبر دالّة سحابية لا كتابةً مباشرة: قواعد `support_threads` تمنع
     * الكتابة على العميل مطلقاً (`write: if false`) حتى للمالك، فالكتابة
     * المباشرة كانت تُرفض بصمت وتبقى الشارة على محادثةٍ قُرئت فعلاً.
     * ويبقى الفشل مبتلَعاً: لا يجوز خطأ أحمر فوق محادثة فُتحت بنجاح.
     */
    suspend fun markRead(threadId: String) {
        runCatching {
            functions.getHttpsCallable("markSupportThreadRead")
                .call(mapOf("threadId" to threadId)).await()
        }
    }

    /** ردّ المالك: نصّ، أو صوت، أو صور — أو مزيج منها. */
    suspend fun reply(
        threadId: String,
        text: String = "",
        audioPath: String = "",
        imagePaths: List<String> = emptyList(),
    ) {
        val body = text.trim()
        require(body.isNotEmpty() || audioPath.isNotEmpty() || imagePaths.isNotEmpty()) {
            "لا شيء لإرساله."
        }
        functions.getHttpsCallable("replySupportThread").call(
            buildMap {
                put("threadId", threadId)
                if (body.isNotEmpty()) put("text", body)
                if (audioPath.isNotEmpty()) put("audioPath", audioPath)
                if (imagePaths.isNotEmpty()) put("imagePaths", imagePaths)
            },
        ).await()
    }

    /** إغلاق المحادثة — لا حذف: تبقى معروضة ولا يُكتب فيها. */
    suspend fun close(threadId: String) {
        functions.getHttpsCallable("closeSupportThread")
            .call(mapOf("threadId" to threadId)).await()
    }

    /** حظر المرسِل (أو رفع الحظر عنه بـ[blocked] = false). */
    suspend fun blockUser(uid: String, blocked: Boolean) {
        functions.getHttpsCallable("blockSupportUser")
            .call(mapOf("uid" to uid, "blocked" to blocked)).await()
    }

    /**
     * قرار في طلب إشراف.
     *
     * ⛔ القبول **لا يُنشئ مشرفاً**: اعتماد الحساب يبقى في شاشة «المشرفون»
     * بيد المالك. هذه رسالة قبول لصاحب الطلب لا أكثر — والواجهة تقول ذلك
     * صراحةً كي لا يظنّ المالك أنّ الصلاحية مُنحت.
     */
    suspend fun decideSupervision(requestId: String, approved: Boolean, note: String = "") {
        functions.getHttpsCallable("decideSupervisionRequest").call(
            buildMap {
                put("requestId", requestId)
                put("decision", if (approved) "approved" else "rejected")
                val trimmed = note.trim()
                if (trimmed.isNotEmpty()) put("note", trimmed)
            },
        ).await()
    }

    /**
     * 🔗 مسار تخزين ⇐ رابط عرض، بمخزون يوم كامل.
     *
     * بلاه كان كلّ رجوع إلى المحادثة يطلب رابطاً جديداً لكلّ صوت وصورة —
     * وهو ما لا يُحتمل على إنترنت ضعيف.
     */
    private val urlCache = LinkedHashMap<String, Pair<Long, String>>()
    private const val URL_TTL_MS = 24L * 60 * 60 * 1000
    private const val URL_CACHE_MAX = 300

    suspend fun mediaUrl(path: String): String {
        if (path.isEmpty()) return ""
        val now = System.currentTimeMillis()
        synchronized(urlCache) {
            urlCache[path]?.let { (at, url) -> if (now - at < URL_TTL_MS) return url }
        }
        val url = storage.reference.child(path).downloadUrl.await().toString()
        synchronized(urlCache) {
            urlCache.entries.removeAll { System.currentTimeMillis() - it.value.first >= URL_TTL_MS }
            while (urlCache.size >= URL_CACHE_MAX) {
                urlCache.remove(urlCache.keys.firstOrNull() ?: break)
            }
            urlCache[path] = System.currentTimeMillis() to url
        }
        return url
    }

    /**
     * رفع ملفّ ردّ المالك ثمّ إعادة **مساره** (لا رابطه): العقد الخادميّ
     * يستقبل `audioPath` و`imagePaths`، والرابط يُشتقّ عند العرض.
     */
    suspend fun uploadReplyMedia(
        /** صاحب الخيط — جزء من المسار الذي تحكم به قواعد التخزين. */
        userUid: String,
        threadId: String,
        file: File,
        contentType: String,
    ): String {
        val safeName = file.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(80)
        val path = "$MEDIA_DIR/$userUid/$threadId/${System.currentTimeMillis()}_$safeName"
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        storage.reference.child(path)
            .putFile(android.net.Uri.fromFile(file), metadata)
            .await()
        return path
    }
}
