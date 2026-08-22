package com.ali.ishaqiyin_admin.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * 📞 إشارات المكالمات الصوتيّة بين المشرفين (WebRTC signaling عبر Firestore).
 *
 * البنية:
 *   • `admin_calls/{callId}` — وثيقة المكالمة (معرّف تلقائيّ):
 *     `members: [uidA, uidB]` مرتَّبة (تحتاجها القواعد والاستعلامات)،
 *     `callerId/callerName/callerPhoto`, `calleeId`, `status`,
 *     `createdAtMs`, `answeredAtMs?`, `endedAtMs?`, `endedBy?`,
 *     `offer: {type, sdp}?`, `answer: {type, sdp}?`.
 *   • `admin_calls/{callId}/callerCandidates/{id}` — ICE candidates المتّصل.
 *   • `admin_calls/{callId}/calleeCandidates/{id}` — ICE candidates المستقبِل.
 *
 * هذا الملفّ **إشارات فقط**: لا يعرف شيئاً عن WebRTC ولا عن الواجهة —
 * يملك `CallEngine` دورة حياة الاتّصال ويستهلك هذه الواجهة.
 *
 * الأمان: قواعد Firestore تمنع أيّ أحد غير عضوَي المكالمة (وكلاهما مشرف
 * معتمَد) من القراءة أو الكتابة، والحذف ممنوع تماماً.
 */
object CallPaths {
    const val CALLS = "admin_calls"
    const val CALLER_CANDIDATES = "callerCandidates"
    const val CALLEE_CANDIDATES = "calleeCandidates"
}

/** حالات المكالمة كما تُكتب في `status` — نصوص لأنّها تُقرأ خادميّاً أيضاً. */
object CallStatus {
    const val RINGING = "ringing"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
    const val ENDED = "ended"
    const val MISSED = "missed"
    const val BUSY = "busy"

    /** الحالات التي تعني أنّ المكالمة انتهت (لا إشارات بعدها). */
    val TERMINAL = setOf(DECLINED, ENDED, MISSED, BUSY)

    /** الحالات التي تشغل الطرفين فعليّاً. */
    val ACTIVE = setOf(RINGING, ACCEPTED)
}

/**
 * مهلة الرنين قبل اعتبار المكالمة فائتة (نمط واتساب) — تُستعمل هنا لترشيح
 * وثائق الرنين المنتهية فقط. المهلة الفعليّة التي يعدّها المحرّك في
 * `AppConfig.CALL_RING_TIMEOUT_MS` (نفس القيمة).
 */
private const val CALL_RING_TIMEOUT_MS = 45_000L

/**
 * بعد هذه المدّة تُعدّ المكالمة «العالقة» (بقيت `accepted` لأنّ التطبيق قُتل
 * قبل الإنهاء) ميّتة، فلا تمنع المشرف من إجراء مكالمات جديدة إلى الأبد.
 */
private const val CALL_STALE_ACTIVE_MS = 4L * 60L * 60L * 1000L

/** ICE candidate واحد كما يُخزَّن في المجموعة الفرعيّة. */
data class CallCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

/** نتيجة المكالمة كما تُسجَّل في المحادثة الخاصّة. */
enum class CallOutcome { Answered, Missed, Declined }

/**
 * نتيجة محاولة بدء مكالمة صادرة — تفصل الأسباب التي كانت كلّها `null` واحداً
 * فتُعرض للمستخدم رسالة واحدة تتّهم الطرف الآخر دائماً.
 */
sealed interface CreateCallResult {
    data class Created(val callId: String) : CreateCallResult

    /** أنا مشغول بمكالمة أخرى. */
    data object SelfBusy : CreateCallResult

    /** الطرف الآخر مشغول. */
    data object PeerBusy : CreateCallResult

    data class Failed(val message: String) : CreateCallResult
}

/** وثيقة المكالمة كاملة. */
data class CallDoc(
    val id: String,
    val members: List<String>,
    val callerId: String,
    val callerName: String,
    val callerPhoto: String,
    val calleeId: String,
    val status: String,
    val createdAtMs: Long,
    val answeredAtMs: Long?,
    val endedAtMs: Long?,
    val endedBy: String?,
    val offerSdp: String?,
    val offerType: String?,
    val answerSdp: String?,
    val answerType: String?,
) {
    val isRinging: Boolean get() = status == CallStatus.RINGING
    val isTerminal: Boolean get() = status in CallStatus.TERMINAL

    companion object {
        fun fromDoc(doc: DocumentSnapshot): CallDoc {
            val d = doc.dataMap()
            val offer = d["offer"] as? Map<*, *>
            val answer = d["answer"] as? Map<*, *>
            return CallDoc(
                id = doc.id,
                members = (d["members"] as? List<*>)?.map { str(it) } ?: emptyList(),
                callerId = str(d["callerId"]),
                callerName = str(d["callerName"]).ifEmpty { "مشرف" },
                callerPhoto = str(d["callerPhoto"]),
                calleeId = str(d["calleeId"]),
                status = str(d["status"]).ifEmpty { CallStatus.RINGING },
                createdAtMs = (d["createdAt"] as? Timestamp)?.toDate()?.time
                    ?: (d["createdAtMs"] as? Number)?.toLong()
                    ?: 0L,
                answeredAtMs = (d["answeredAtMs"] as? Number)?.toLong(),
                endedAtMs = (d["endedAtMs"] as? Number)?.toLong(),
                endedBy = str(d["endedBy"]).takeIf { it.isNotEmpty() },
                offerSdp = str(offer?.get("sdp")).takeIf { it.isNotEmpty() },
                offerType = str(offer?.get("type")).takeIf { it.isNotEmpty() },
                answerSdp = str(answer?.get("sdp")).takeIf { it.isNotEmpty() },
                answerType = str(answer?.get("type")).takeIf { it.isNotEmpty() },
            )
        }
    }
}

object CallRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    private fun calls(): CollectionReference = db.collection(CallPaths.CALLS)

    private fun call(callId: String): DocumentReference = calls().document(callId)

    private fun candidates(callId: String, fromCaller: Boolean): CollectionReference =
        call(callId).collection(
            if (fromCaller) CallPaths.CALLER_CANDIDATES else CallPaths.CALLEE_CANDIDATES,
        )

    /** اسمي وصورتي كما تظهر عند الطرف الآخر (نفس منطق رسائل الخاصّ). */
    private fun myIdentity(): Pair<String, String> {
        val u = FirebaseAuth.getInstance().currentUser
        val name = u?.displayName?.takeIf { it.isNotBlank() }
            ?: u?.email?.substringBefore('@')
            ?: "مشرف"
        return name to u?.photoUrl?.toString().orEmpty()
    }

    /**
     * هل هذه المكالمة ما زالت تشغل طرفيها فعليّاً؟ الوثائق العالقة (رنين
     * تجاوز المهلة، أو مكالمة «مقبولة» قديمة جدّاً لأنّ التطبيق قُتل قبل
     * الإنهاء) لا تُحسب — وإلّا لتعطّل الاتّصال إلى الأبد.
     */
    private fun CallDoc.blocksNewCall(now: Long): Boolean = when (status) {
        CallStatus.RINGING -> now - createdAtMs < CALL_RING_TIMEOUT_MS
        CallStatus.ACCEPTED -> now - createdAtMs < CALL_STALE_ACTIVE_MS
        else -> false
    }

    /**
     * استعلام مكالماتي مقصوراً على الأحدث: الحذف ممنوع بالقواعد، فبلا ترتيب
     * وحدّ كان كلّ فحص انشغال يقرأ **كلّ** مكالماتي التاريخيّة (قراءات مفوترة
     * تنمو بلا سقف). العشرة الأحدث تكفي: ما يشغلني الآن أُنشئ للتوّ.
     *
     * ⚠️ يتطلّب فهرساً مركّباً (`members` array-contains + `createdAtMs` تنازليّاً)
     * موجوداً في `firestore.indexes.json` ويحتاج نشراً — قبله يفشل الاستعلام.
     */
    private fun myCallsQuery(me: String): Query =
        calls()
            .whereArrayContains("members", me)
            .orderBy("createdAtMs", Query.Direction.DESCENDING)
            .limit(10)

    /** مكالماتي النشطة فعليّاً الآن (تُستعمل لفحص الانشغال). */
    private suspend fun activeCallsOfMine(): List<CallDoc> {
        val me = uid
        if (me.isEmpty()) return emptyList()
        // القواعد لا تقبل استعلاماً لا يضمن أنّني عضو في كلّ وثيقة مُعادة،
        // فيبقى `array-contains` أساس الاستعلام والترشيح الباقي محليّ.
        val snap = myCallsQuery(me).get().await()
        val now = System.currentTimeMillis()
        return snap.documents
            .map { CallDoc.fromDoc(it) }
            .filter { it.status in CallStatus.ACTIVE && it.blocksNewCall(now) }
    }

    /**
     * قراءة وثيقة مكالمة من **الخادم** لا من الكاش المحلّي: دفعة FCM متأخّرة
     * قد تصل بعد انتهاء المكالمة، ونسخة الكاش قد تقول «ما زالت ترنّ».
     */
    suspend fun fetchCallFromServer(callId: String): CallDoc? = runCatching {
        val snap = call(callId).get(Source.SERVER).await()
        if (snap.exists()) CallDoc.fromDoc(snap) else null
    }.getOrNull()

    /** يحجز معرّفاً محلياً قبل إنشاء الوثيقة كي يربطه المحرّك باتصال WebRTC. */
    fun newCallId(): String = calls().document().id

    /**
     * إنشاء مكالمة صادرة نحو مشرف آخر.
     * @return [CreateCallResult.Created] بمعرّف المكالمة، أو سبباً مميَّزاً
     *   للفشل تعرضه الواجهة كما هو.
     *
     * ⚠️ انشغال الطرف الآخر بمكالمة مع **طرف ثالث** لا يمكن كشفه هنا (القواعد
     * لا تسمح لي بقراءة مكالمات لستُ عضواً فيها) — يكشفه جهازه فيضبط الحالة
     * `busy` عند وصول الرنين، وهذا هو المسار المقصود.
     */
    suspend fun createCall(
        callId: String,
        calleeUid: String,
        calleeName: String,
        offerType: String,
        offerSdp: String,
    ): CreateCallResult {
        val me = uid
        val other = calleeUid.trim()
        if (me.isEmpty()) return CreateCallResult.Failed("لم يُسجَّل الدخول.")
        if (callId.isBlank() || other.isEmpty() || other == me || offerSdp.isBlank()) {
            return CreateCallResult.Failed("لا يمكن الاتّصال بهذا المشرف.")
        }

        // فشل هذا الاستعلام (شبكة/فهرس غير منشور) لا يمنع المكالمة: المحرّك
        // يحرس محليّاً من مكالمة ثانية، ومنعُها هنا كان سيعطّل الميزة كلّياً.
        val active = runCatching { activeCallsOfMine() }.getOrDefault(emptyList())
        // التمييز الوحيد الممكن ضمن ما تسمح القواعد بقراءته: مكالمة نشطة مع
        // طرف ثالث = أنا المشغول، ومكالمة نشطة بيني وبين نفس المشرف = جلسة
        // قائمة معه أصلاً (رنين في أحد الاتّجاهين) فهو المشغول عنّي.
        if (active.any { other !in it.members }) return CreateCallResult.SelfBusy
        if (active.isNotEmpty()) return CreateCallResult.PeerBusy

        val (myName, myPhoto) = myIdentity()
        val now = System.currentTimeMillis()
        val doc = call(callId)
        val payload = mapOf(
            "members" to listOf(me, other).sorted(),
            "callerId" to me,
            "callerName" to myName,
            "callerPhoto" to myPhoto,
            "calleeId" to other,
            "calleeName" to calleeName.trim().ifEmpty { "مشرف" },
            "status" to CallStatus.RINGING,
            "createdAtMs" to now,
            "createdAt" to FieldValue.serverTimestamp(),
            "offer" to mapOf("type" to offerType, "sdp" to offerSdp),
        )
        return runCatching<CreateCallResult> {
            // معاملة لا تُصفّ بلا اتصال: الكتابة العادية تبقى معلّقة محلياً
            // وقد تُنشئ مكالمة شبحية عند عودة الشبكة بعد إغلاق الشاشة.
            db.runTransaction<String> { tx ->
                tx.set(doc, payload)
                doc.id
            }.await()
            CreateCallResult.Created(doc.id)
        }.getOrElse { CreateCallResult.Failed("تعذّر بدء المكالمة — تحقّق من الاتّصال.") }
    }

    /** بثّ حيّ لوثيقة مكالمة بعينها (المصدر الوحيد لحالة المكالمة). */
    fun callStream(callId: String): Flow<CallDoc?> =
        call(callId).docSnapshots().map { if (it.exists()) CallDoc.fromDoc(it) else null }

    /**
     * أحدث مكالمة واردة إليّ ما زالت ترنّ (للكشف داخل التطبيق دون انتظار FCM).
     * تُصدر `null` حين لا يوجد رنين.
     */
    fun incomingCallsStream(): Flow<CallDoc?> {
        val me = uid
        if (me.isEmpty()) return flowOf(null)
        // نفس استعلام الانشغال (الأحدث فقط) والترشيح الباقي محليّاً.
        return myCallsQuery(me).querySnapshots().map { snap ->
            val now = System.currentTimeMillis()
            snap.documents
                .map { CallDoc.fromDoc(it) }
                .filter {
                    it.calleeId == me &&
                        it.status == CallStatus.RINGING &&
                        now - it.createdAtMs < CALL_RING_TIMEOUT_MS
                }
                .maxByOrNull { it.createdAtMs }
        }
    }

    /** يضيف ICE candidate إلى مجموعة صاحبه فور توليده. */
    suspend fun addCandidate(
        callId: String,
        fromCaller: Boolean,
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidate: String,
    ) {
        if (candidate.isBlank()) return
        candidates(callId, fromCaller).add(
            mapOf(
                "sdpMid" to sdpMid,
                "sdpMLineIndex" to sdpMLineIndex,
                "candidate" to candidate,
                "createdAtMs" to System.currentTimeMillis(),
            ),
        ).await()
    }

    /**
     * بثّ حيّ لمرشّحي أحد الطرفين (يُراقب كلّ طرف مجموعة الآخر).
     *
     * ⚠️ **الجديد فقط**: بناء اللقطة من `documentChanges` لا من كامل القائمة —
     * إعادة إرسال كلّ المرشّحين مع كلّ لقطة كانت تعني n(n+1)/2 استدعاء
     * لـ`addIceCandidate`. تعتمد الجهة المستهلِكة هذا العقد فتضيف كلّ ما يصلها.
     */
    fun candidatesStream(callId: String, fromCaller: Boolean): Flow<List<CallCandidate>> =
        candidates(callId, fromCaller)
            .orderBy("createdAtMs", Query.Direction.ASCENDING)
            .querySnapshots()
            .map { snap ->
                snap.documentChanges
                    .filter { it.type == DocumentChange.Type.ADDED }
                    .mapNotNull { change ->
                        val d = change.document.dataMap()
                        val sdp = str(d["candidate"])
                        if (sdp.isEmpty()) return@mapNotNull null
                        CallCandidate(
                            sdpMid = str(d["sdpMid"]).takeIf { it.isNotEmpty() },
                            sdpMLineIndex = (d["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                            candidate = sdp,
                        )
                    }
            }

    /**
     * تغيير حالة المكالمة إلى حالة نهائيّة **مرّة واحدة فقط**: معاملة تمنع
     * أن يدوس «إنهاء» متأخّر على «مرفوضة»/«فائتة» فيرسل الخادم إلغاءً مكرّراً
     * أو يسجّل نتيجة خاطئة في المحادثة.
     */
    private suspend fun finish(callId: String, status: String): Boolean = runCatching {
        val me = uid
        db.runTransaction<Boolean> { tx ->
            val snap = tx.get(call(callId))
            if (!snap.exists()) return@runTransaction false
            val current = str(snap.dataMap()["status"]).ifEmpty { CallStatus.RINGING }
            // «فائتة» لا تصحّ إلّا على مكالمة ما زالت ترنّ.
            if (current !in CallStatus.ACTIVE) return@runTransaction false
            if (status == CallStatus.MISSED && current != CallStatus.RINGING) {
                return@runTransaction false
            }
            tx.update(
                call(callId),
                mapOf(
                    "status" to status,
                    "endedAtMs" to System.currentTimeMillis(),
                    "endedBy" to me,
                ),
            )
            true
        }.await()
    }.getOrDefault(false)

    /**
     * يكتب جواب المستقبِل وقبولَه في معاملة واحدة. فصل الكتابتين كان يسمح
     * بوصول answer مع بقاء الحالة ringing أو بإحياء قبول متأخّر بعد الإلغاء.
     */
    suspend fun acceptWithAnswer(callId: String, type: String, sdp: String): Boolean =
        runCatching {
            db.runTransaction<Boolean> { tx ->
                val ref = call(callId)
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                val current = str(snap.dataMap()["status"]).ifEmpty { CallStatus.RINGING }
                if (current != CallStatus.RINGING) return@runTransaction false
                tx.update(
                    ref,
                    mapOf(
                        "answer" to mapOf("type" to type, "sdp" to sdp),
                        "status" to CallStatus.ACCEPTED,
                        "answeredAtMs" to System.currentTimeMillis(),
                    ),
                )
                true
            }.await()
        }.getOrDefault(false)

    /** رفض المكالمة الواردة. */
    suspend fun decline(callId: String) {
        finish(callId, CallStatus.DECLINED)
    }

    /** إنهاء مكالمة جارية (أو إلغاء صادرة قبل الردّ). */
    suspend fun end(callId: String) {
        finish(callId, CallStatus.ENDED)
    }

    /** انقضاء مهلة الرنين بلا ردّ. */
    suspend fun markMissed(callId: String) {
        finish(callId, CallStatus.MISSED)
    }

    /** المستقبِل مشغول بمكالمة أخرى — يُسقط الرنين عند الطرفين فوراً. */
    suspend fun markBusy(callId: String) {
        finish(callId, CallStatus.BUSY)
    }

    /**
     * 📝 سجلّ المكالمة في المحادثة الخاصّة (نمط واتساب): رسالة بنوع `call`
     * تظهر في مجرى الرسائل وفي معاينة قائمة المحادثات.
     *
     * تُكتب بنفس حقول رسائل الخاصّ حرفيّاً (تشترطها القواعد)، مع حقلين
     * إضافيّين للنتيجة والمدّة — إنشاء رسائل الخاصّ لا يستعمل `hasOnly`
     * فالحقول الإضافيّة مقبولة.
     */
    suspend fun logCallMessage(
        threadId: String,
        otherUid: String,
        outcome: CallOutcome,
        seconds: Int,
    ) {
        val me = uid
        if (me.isEmpty() || threadId.isEmpty()) return
        val (myName, myPhoto) = myIdentity()
        val safeSeconds = seconds.coerceAtLeast(0)
        val text = when (outcome) {
            CallOutcome.Answered -> "📞 مكالمة صوتيّة — ${formatCallDuration(safeSeconds)}"
            CallOutcome.Missed -> "📞 مكالمة فائتة"
            CallOutcome.Declined -> "📞 مكالمة مرفوضة"
        }
        val now = System.currentTimeMillis()
        runCatching {
            // وثيقة المحادثة أوّلاً: قاعدة إنشاء الرسالة تقرأ `members` منها،
            // فلو غابت (أوّل تواصل بين المشرفَين مكالمة) سقط السجلّ بصمت.
            if (otherUid.isNotEmpty()) DmRepository.ensureThread(otherUid)
            db.collection(DmPaths.THREADS).document(threadId)
                .collection(DmPaths.MESSAGES)
                .add(
                    mapOf(
                        "senderId" to me,
                        "senderName" to myName,
                        "senderPhoto" to myPhoto,
                        "type" to chatTypeToString(ChatMessageType.Call),
                        "text" to text,
                        "att" to null,
                        "replyTo" to null,
                        "sentAtMs" to now,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "deleted" to false,
                        "deletedBy" to "",
                        "hiddenFor" to emptyList<String>(),
                        "reactions" to emptyMap<String, String>(),
                        // حقول السجلّ (تقرأها الفقاعة إن احتاجت تمييز الفائتة).
                        "callOutcome" to outcome.name.lowercase(),
                        "callSeconds" to safeSeconds,
                    ),
                ).await()
            // معاينة قائمة المحادثات (نظير `touchThread` في محادثات الخاصّ).
            db.collection(DmPaths.THREADS).document(threadId).set(
                mapOf(
                    "members" to listOf(me, otherUid).sorted(),
                    "lastText" to text,
                    "lastType" to chatTypeToString(ChatMessageType.Call),
                    "lastSenderId" to me,
                    "lastAtMs" to now,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "readAtMs" to mapOf(me to now),
                ),
                SetOptions.merge(),
            ).await()
        }
    }
}

/** مدّة المكالمة بصيغة m:ss (أو h:mm:ss للطويلة) — نمط واتساب. */
fun formatCallDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%d:%02d".format(m, sec)
    }
}
