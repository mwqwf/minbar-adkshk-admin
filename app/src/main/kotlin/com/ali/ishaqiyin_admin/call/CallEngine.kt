package com.ali.ishaqiyin_admin.call

import android.content.Context
import android.media.MediaRecorder
import com.ali.ishaqiyin_admin.core.AppConfig
import com.ali.ishaqiyin_admin.data.AdminCallNotifications
import com.ali.ishaqiyin_admin.data.CallDoc
import com.ali.ishaqiyin_admin.data.CallOutcome
import com.ali.ishaqiyin_admin.data.CallRepository
import com.ali.ishaqiyin_admin.data.CallStatus
import com.ali.ishaqiyin_admin.data.CreateCallResult
import com.ali.ishaqiyin_admin.data.DmPaths
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume

/** مراحل المكالمة كما تراها الواجهة. */
enum class CallPhase {
    /** لا مكالمة. */
    Idle,

    /** صادرة: نُنشئ الوثيقة وننتظر ردّ الطرف الآخر. */
    Dialing,

    /** واردة: يرنّ الجهاز وننتظر قبول المستخدم. */
    Ringing,

    /** قُبِلت وجارٍ تبادل الإشارات وتوصيل الصوت. */
    Connecting,

    /** الصوت متّصل — عدّاد المدّة يعمل. */
    Active,

    /** انتهت (أو فشلت) — تُعرض لحظات ثمّ تُغلق الشاشة. */
    Ended,
}

/** حالة المكالمة الحاليّة (مصدر وحيد للحقيقة لكلّ الواجهات). */
data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val peerPhoto: String = "",
    /** واردة إليّ (لا صادرة منّي). */
    val incoming: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** لحظة توصّل الصوت فعلاً — أساس عدّاد المدّة. */
    val connectedAtMs: Long = 0L,
    /** سطر الحالة تحت الاسم («يرنّ…»، «انتهت المكالمة»…). */
    val note: String = "",
) {
    /** مكالمة قائمة تمنع بدء أخرى. */
    val busy: Boolean get() = phase != CallPhase.Idle && phase != CallPhase.Ended
}

/**
 * وصلة توجيه الصوت وإيقاف الخدمة — تنفّذها [CallService] وحدها.
 * المحرّك يملك WebRTC، والخدمة تملك دورة الحياة و`AudioManager`.
 */
internal interface CallAudioRouter {
    fun applySpeaker(on: Boolean)
    fun stopCallService()
}

/**
 * 📞 محرّك المكالمات الصوتيّة (WebRTC) في المحادثات الخاصّة.
 *
 * مفرد على مستوى العمليّة: [CallActivity] تعرض [state]، و[CallService]
 * تُبقيه حيّاً وتوجّه الصوت. الإشارات كلّها عبر `CallRepository`
 * (Firestore)، وخوادم ICE من [AppConfig.ICE_SERVERS].
 *
 * ⚠️ **من يكتب سجلّ المكالمة في المحادثة**: المتصل وحده — قاعدة حتميّة
 * تمنع ازدواج الرسالة عند الطرفين (الطرفان يريان تغيّر الحالة نفسه).
 */
object CallEngine {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null

    private var signalJob: Job? = null
    private var watchJob: Job? = null
    private var candidatesJob: Job? = null
    private var timeoutJob: Job? = null

    /** مراقبة وثيقة المكالمة الواردة طوال الرنين (قبل القبول). */
    private var ringWatchJob: Job? = null

    /** مهلة التعافي بعد انقطاع مؤقّت للاتّصال. */
    private var reconnectJob: Job? = null

    @Volatile
    private var router: CallAudioRouter? = null

    private var factoryReady = false
    private var remoteDescriptionSet = false
    private var outcomeLogged = false

    /** المرشّحات الواصلة قبل ضبط الوصف البعيد تُخزَّن ثمّ تُضاف بعده. */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    /** مهلة يُمنحها الاتّصال ليتعافى من انقطاع مؤقّت قبل الإغلاق. */
    private const val RECONNECT_GRACE_MS = 12_000L

    /**
     * كتابة الحالة النهائيّة في Firestore معاملة قد تتعثّر بلا شبكة، ولا يتوقّف
     * عليها شيء محليّاً — نتخلّى عنها بعد هذه المدّة.
     */
    private const val REMOTE_WRITE_TIMEOUT_MS = 5_000L
    private const val SIGNAL_WRITE_TIMEOUT_MS = 12_000L

    private val myUid: String get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ────────────────────────── الواجهة العامّة ──────────────────────────

    /**
     * بدء مكالمة صادرة. تُستدعى بعد التأكّد من إذن الميكروفون.
     * الخدمة الأماميّة هي من يشغّل المنطق فعلاً (لتبقى المكالمة حيّة
     * بعد مغادرة الشاشة).
     */
    fun startOutgoing(context: Context, calleeUid: String, calleeName: String, calleePhoto: String) {
        if (_state.value.busy) return
        outcomeLogged = false
        _state.value = CallUiState(
            phase = CallPhase.Dialing,
            peerUid = calleeUid,
            peerName = calleeName,
            peerPhoto = calleePhoto,
            incoming = false,
            note = "جارٍ الاتصال…",
        )
        CallService.start(context, CallService.ACTION_OUTGOING)
    }

    /**
     * تهيئة شاشة الرنين لمكالمة واردة قبل أن يقبلها المستخدم.
     *
     * @return `false` إن لم تُتبنَّ المكالمة (أنا مشغول بأخرى) — عندها لا يجوز
     *   للمتّصل بها استدعاء [acceptIncoming] وإلّا خطف المكالمةَ الجديدةَ
     *   محلَّ القائمة.
     */
    fun prepareIncoming(
        context: Context,
        callId: String,
        callerUid: String,
        callerName: String,
        callerPhoto: String,
    ): Boolean {
        val current = _state.value
        if (current.busy && current.callId != callId) {
            // مشغول بمكالمة أخرى: نُسقط الواردة صراحةً بدل تجاهلها بصمت،
            // وإلّا بقي المتّصل يرنّ حتى المهلة بلا سبب.
            if (callId.isNotEmpty()) {
                scope.launch {
                    withTimeoutOrNull(REMOTE_WRITE_TIMEOUT_MS) {
                        runCatching { CallRepository.markBusy(callId) }
                    }
                }
            }
            AdminCallNotifications.cancelIncoming(context)
            return false
        }
        // نفس المكالمة وقد تجاوزت الرنين (قُبِلت أصلاً): لا نُرجعها إلى الرنين.
        if (current.callId == callId && current.phase != CallPhase.Ringing && current.busy) {
            return true
        }
        outcomeLogged = false
        _state.value = CallUiState(
            phase = CallPhase.Ringing,
            callId = callId,
            peerUid = callerUid,
            peerName = callerName,
            peerPhoto = callerPhoto,
            incoming = true,
            note = "مكالمة صوتيّة واردة",
        )
        armIncomingRing(context, callId)
        return true
    }

    /** قبول مكالمة واردة. تُستدعى بعد التأكّد من إذن الميكروفون. */
    fun acceptIncoming(context: Context, callId: String) {
        // مهلة الرنين ومراقبته انتهى دورهما — `watchCall` يتولّى الوثيقة الآن.
        timeoutJob?.cancel()
        timeoutJob = null
        ringWatchJob?.cancel()
        ringWatchJob = null
        _state.update {
            if (it.callId == callId) {
                it.copy(phase = CallPhase.Connecting, note = "جارٍ الاتصال…")
            } else {
                CallUiState(
                    phase = CallPhase.Connecting,
                    callId = callId,
                    incoming = true,
                    note = "جارٍ الاتصال…",
                )
            }
        }
        CallService.start(context, CallService.ACTION_ACCEPT)
    }

    /**
     * رفض مكالمة واردة قبل قبولها. تعمل حتى لو كانت الحالة فارغة (رفض من
     * الإشعار بعد إعادة تشغيل العمليّة).
     */
    fun declineIncoming(callId: String) {
        // ⚠️ الإنهاء محليّاً **أوّلاً**: كتابة الرفض معاملة Firestore قد تتعثّر
        // بلا شبكة، وانتظارها كان يُبقي الرنين والحالة `busy` عاملَين.
        finish("رُفضت المكالمة")
        if (callId.isNotEmpty()) writeFinalStatus(callId, decline = true)
    }

    /**
     * المتصل ألغى قبل الردّ (دفعة `action=cancel`): تُسقَط شاشة الرنين
     * محليّاً بلا كتابة أيّ حالة — الوثيقة حُدِّثت عند المتصل أصلاً.
     */
    fun cancelIncoming(callId: String) {
        val snapshot = _state.value
        if (snapshot.callId == callId && snapshot.phase == CallPhase.Ringing) {
            finish("انتهت المكالمة")
        }
    }

    /** إنهاء المكالمة من هذا الطرف (زرّ الإنهاء الأحمر). */
    fun hangUp() {
        val snapshot = _state.value
        if (snapshot.phase == CallPhase.Idle || snapshot.phase == CallPhase.Ended) return
        // ⚠️ التحرير محليّاً **أوّلاً** ثمّ الكتابة بلا انتظار: كانت الشاشة
        // والميكروفون يبقيان عاملَين طوال تعثّر معاملة Firestore بلا شبكة.
        finish("انتهت المكالمة")
        val id = snapshot.callId
        if (id.isNotEmpty()) {
            writeFinalStatus(
                callId = id,
                decline = snapshot.incoming && snapshot.phase == CallPhase.Ringing,
            )
        }
        scope.launch {
            logOutcome(
                snapshot,
                if (snapshot.connectedAtMs > 0L) CallOutcome.Answered else CallOutcome.Missed,
            )
        }
    }

    /** كتم/إلغاء كتم الميكروفون (يُعطَّل المسار المحلّي فيصمت فوراً). */
    fun toggleMute() {
        val muted = !_state.value.muted
        runCatching { localTrack?.setEnabled(!muted) }
        runCatching { audioModule?.setMicrophoneMute(muted) }
        _state.update { it.copy(muted = muted) }
    }

    /**
     * تحرير كلّ الموارد **وتصفير الحالة** — لموت الخدمة بلا إنهاء منظّم
     * (قتل النظام، أو فشل الإشعار الأماميّ). آمنة للاستدعاء المتكرّر.
     *
     * ⚠️ [releaseAll] وحدها لا تمسّ `_state`، فكانت الحالة تبقى `busy` إلى
     * الأبد بعد موت الخدمة فيموت زرّ الاتصال ويُتجاهَل كلّ وارد.
     */
    fun forceIdle() {
        releaseAll()
        outcomeLogged = false
        _state.value = CallUiState()
    }

    /** تبديل مكبّر الصوت (يطبّقه [CallService] على `AudioManager`). */
    fun toggleSpeaker() {
        val on = !_state.value.speaker
        _state.update { it.copy(speaker = on) }
        router?.applySpeaker(on)
    }

    // ─────────────────── ما تستدعيه الخدمة وحدها ───────────────────

    internal fun attachRouter(value: CallAudioRouter) {
        router = value
        // إعادة تطبيق الاختيار الحالي بعد إعادة إنشاء الخدمة.
        value.applySpeaker(_state.value.speaker)
    }

    internal fun detachRouter(value: CallAudioRouter) {
        if (router === value) router = null
    }

    /**
     * كتابة الحالة النهائيّة للمكالمة القائمة بلا انتظار — تُستدعى قبل
     * [forceIdle] عند موت الخدمة، وإلّا بقيت الوثيقة «ترنّ» عند الطرف الآخر.
     */
    internal fun abandonRemoteState() {
        val snapshot = _state.value
        if (!snapshot.busy || snapshot.callId.isEmpty()) return
        writeFinalStatus(
            callId = snapshot.callId,
            decline = snapshot.incoming && snapshot.phase == CallPhase.Ringing,
        )
    }

    /** مسار المكالمة الصادرة كاملاً. */
    internal fun runOutgoing(context: Context) {
        // ⚠️ حماية من التكرار: أمر تشغيل مُعاد للخدمة كان سينشئ مكالمة ثانية.
        if (signalJob?.isActive == true || peer != null) return
        signalJob = scope.launch {
            val snapshot = _state.value
            val id = CallRepository.newCallId()
            if (!buildPeer(context, id, fromCaller = true)) {
                finish("تعذّر تشغيل الصوت على هذا الجهاز.")
                return@launch
            }
            val connection = peer ?: return@launch
            val offer = connection.createOfferAwait()
            if (offer == null) {
                finish("تعذّر بدء المكالمة.")
                return@launch
            }
            val result = withTimeoutOrNull(SIGNAL_WRITE_TIMEOUT_MS) {
                runCatching {
                    CallRepository.createCall(
                        callId = id,
                        calleeUid = snapshot.peerUid,
                        calleeName = snapshot.peerName,
                        offerType = offer.type.canonicalForm(),
                        offerSdp = offer.description,
                    )
                }.getOrElse { CreateCallResult.Failed("تعذّر بدء المكالمة.") }
            } ?: CreateCallResult.Failed("تعذّر بدء المكالمة — تحقّق من الاتّصال.")
            // لكلّ سبب رسالته: اتّهام الطرف الآخر دائماً كان يُضلّل حين أكون
            // أنا المشغول بمكالمة أخرى.
            val createdId = when (result) {
                is CreateCallResult.Created -> result.callId
                CreateCallResult.SelfBusy -> {
                    finish("أنت في مكالمة أخرى.")
                    return@launch
                }

                CreateCallResult.PeerBusy -> {
                    finish("الطرف الآخر مشغول في مكالمة أخرى.")
                    return@launch
                }

                is CreateCallResult.Failed -> {
                    finish(result.message.ifEmpty { "تعذّر بدء المكالمة." })
                    return@launch
                }
            }
            if (createdId.isEmpty()) {
                finish("تعذّر بدء المكالمة.")
                return@launch
            }
            _state.update { it.copy(callId = createdId, note = "يرنّ…") }
            listenCandidates(createdId, fromCaller = true)
            if (!connection.setLocalAwait(offer)) {
                writeFinalStatus(id, decline = false)
                finish("تعذّر بدء المكالمة.")
                return@launch
            }
            watchCall(createdId, fromCaller = true)
            armRingTimeout(createdId)
        }
    }

    /** مسار قبول المكالمة الواردة كاملاً. */
    internal fun runAccept(context: Context) {
        if (signalJob?.isActive == true || peer != null) return
        signalJob = scope.launch {
            val id = _state.value.callId
            if (id.isEmpty()) {
                finish("انتهت المكالمة")
                return@launch
            }
            // العرض (offer) قد يصل بعد لحظة من إنشاء الوثيقة.
            val doc = awaitOffer(id)
            if (doc == null) {
                writeFinalStatus(id, decline = true)
                finish("تعذّر استقبال المكالمة.")
                return@launch
            }
            _state.update {
                it.copy(
                    peerUid = doc.callerId,
                    peerName = doc.callerName,
                    peerPhoto = doc.callerPhoto,
                )
            }
            if (!buildPeer(context, id, fromCaller = false)) {
                writeFinalStatus(id, decline = true)
                finish("تعذّر تشغيل الصوت على هذا الجهاز.")
                return@launch
            }
            listenCandidates(id, fromCaller = false)
            val connection = peer ?: return@launch
            val remote = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(doc.offerType ?: "offer"),
                doc.offerSdp.orEmpty(),
            )
            if (!connection.setRemoteAwait(remote)) {
                writeFinalStatus(id, decline = true)
                finish("تعذّر استقبال المكالمة.")
                return@launch
            }
            markRemoteReady()
            val answer = connection.createAnswerAwait()
            if (answer == null || !connection.setLocalAwait(answer)) {
                writeFinalStatus(id, decline = true)
                finish("تعذّر الردّ على المكالمة.")
                return@launch
            }
            val accepted = withTimeoutOrNull(SIGNAL_WRITE_TIMEOUT_MS) {
                CallRepository.acceptWithAnswer(
                    id,
                    answer.type.canonicalForm(),
                    answer.description,
                )
            } == true
            if (!accepted) {
                finish("انتهت المكالمة")
                return@launch
            }
            watchCall(id, fromCaller = false)
        }
    }

    /** تحرير كلّ شيء — تستدعيه الخدمة في `onDestroy`. */
    internal fun releaseAll() {
        signalJob?.cancel()
        watchJob?.cancel()
        candidatesJob?.cancel()
        timeoutJob?.cancel()
        ringWatchJob?.cancel()
        reconnectJob?.cancel()
        signalJob = null
        watchJob = null
        candidatesJob = null
        timeoutJob = null
        ringWatchJob = null
        reconnectJob = null
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
        // ترتيب التحرير مهمّ: الاتّصال ثمّ المسارات ثمّ المصنع.
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioModule?.release() }
        audioModule = null
    }

    // ───────────────────────── منطق داخليّ ─────────────────────────

    /**
     * تهيئة المصنع والاتّصال والمسار الصوتي المحلّي.
     * `PeerConnectionFactory.initialize` مرّة واحدة لعمر العمليّة.
     */
    private fun buildPeer(context: Context, callId: String, fromCaller: Boolean): Boolean =
        runCatching {
            if (!factoryReady) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                factoryReady = true
            }
            val module = JavaAudioDeviceModule.builder(context.applicationContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(
                    JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
                )
                .setUseHardwareNoiseSuppressor(
                    JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
                )
                .createAudioDeviceModule()
            audioModule = module
            val built = PeerConnectionFactory.builder()
                .setAudioDeviceModule(module)
                .createPeerConnectionFactory()
            factory = built

            val config = PeerConnection.RTCConfiguration(
                AppConfig.ICE_SERVERS.map {
                    PeerConnection.IceServer.builder(it).createIceServer()
                },
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val connection = built.createPeerConnection(config, observerFor(callId, fromCaller))
                ?: return@runCatching false
            peer = connection

            val source = built.createAudioSource(MediaConstraints())
            audioSource = source
            val track = built.createAudioTrack("minbar_mic", source)
            track.setEnabled(!_state.value.muted)
            localTrack = track
            connection.addTrack(track, listOf("minbar_call"))

            true
        }.getOrDefault(false)

    private fun observerFor(callId: String, fromCaller: Boolean) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            scope.launch {
                runCatching {
                    CallRepository.addCandidate(
                        callId = callId,
                        fromCaller = fromCaller,
                        sdpMid = c.sdpMid,
                        sdpMLineIndex = c.sdpMLineIndex,
                        candidate = c.sdp,
                    )
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> scope.launch { markConnected() }

                // انقطاع مؤقّت (تبديل شبكة/إشارة ضعيفة): WebRTC يحاول التعافي
                // وحده قبل أن يعلنها `FAILED` بعد ~٣٠ ثانية — نُخبر المستخدم
                // ونمنح مهلة قصيرة بدل الصمت أو الإغلاق الفوريّ.
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    scope.launch { armReconnectGrace() }

                PeerConnection.PeerConnectionState.FAILED -> scope.launch {
                    // بلا خادم TURN يفشل الاتّصال خلف NAT صارم.
                    reconnectJob?.cancel()
                    val snapshot = _state.value
                    if (snapshot.callId.isNotEmpty()) {
                        writeFinalStatus(snapshot.callId, decline = false)
                    }
                    finish("تعذّر توصيل الصوت — جرّب شبكة أخرى.")
                    scope.launch { logOutcome(snapshot, CallOutcome.Missed) }
                }

                else -> Unit
            }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            if (
                newState == PeerConnection.IceConnectionState.CONNECTED ||
                newState == PeerConnection.IceConnectionState.COMPLETED
            ) {
                scope.launch { markConnected() }
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    /** مراقبة وثيقة المكالمة: الردّ، القبول، والإنهاء من الطرف الآخر. */
    private fun watchCall(callId: String, fromCaller: Boolean) {
        watchJob?.cancel()
        watchJob = scope.launch {
            CallRepository.callStream(callId).collect { doc ->
                if (doc == null) return@collect
                if (fromCaller && !remoteDescriptionSet && !doc.answerSdp.isNullOrEmpty()) {
                    val answer = SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(doc.answerType ?: "answer"),
                        doc.answerSdp.orEmpty(),
                    )
                    if (peer?.setRemoteAwait(answer) == true) markRemoteReady()
                }
                when (doc.status) {
                    CallStatus.ACCEPTED -> {
                        timeoutJob?.cancel()
                        if (_state.value.phase == CallPhase.Dialing) {
                            _state.update {
                                it.copy(phase = CallPhase.Connecting, note = "جارٍ التوصيل…")
                            }
                        }
                    }

                    CallStatus.DECLINED -> {
                        logOutcome(_state.value, CallOutcome.Declined)
                        finish("رُفضت المكالمة")
                    }

                    CallStatus.MISSED -> {
                        logOutcome(_state.value, CallOutcome.Missed)
                        finish("لم يُجب")
                    }

                    CallStatus.BUSY -> finish("الطرف الآخر مشغول")

                    CallStatus.ENDED -> {
                        logOutcome(
                            _state.value,
                            if (_state.value.connectedAtMs > 0L) {
                                CallOutcome.Answered
                            } else {
                                CallOutcome.Missed
                            },
                        )
                        finish("انتهت المكالمة")
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun listenCandidates(callId: String, fromCaller: Boolean) {
        candidatesJob?.cancel()
        candidatesJob = scope.launch {
            // نراقب مرشّحات الطرف الآخر (عكس دورنا).
            CallRepository.candidatesStream(callId, fromCaller = !fromCaller).collect { list ->
                list.forEach { item ->
                    val candidate = IceCandidate(item.sdpMid, item.sdpMLineIndex, item.candidate)
                    if (remoteDescriptionSet) {
                        runCatching { peer?.addIceCandidate(candidate) }
                    } else {
                        // قبل ضبط الوصف البعيد يرفض WebRTC المرشّحات.
                        pendingRemoteCandidates.add(candidate)
                    }
                }
            }
        }
    }

    private fun markRemoteReady() {
        remoteDescriptionSet = true
        pendingRemoteCandidates.forEach { runCatching { peer?.addIceCandidate(it) } }
        pendingRemoteCandidates.clear()
    }

    private fun markConnected() {
        reconnectJob?.cancel()
        reconnectJob = null
        _state.update {
            when (it.phase) {
                CallPhase.Idle, CallPhase.Ended -> it
                // عاد الاتّصال بعد انقطاع: نمسح سطر «إعادة المحاولة…».
                CallPhase.Active -> if (it.note.isEmpty()) it else it.copy(note = "")
                else -> it.copy(
                    phase = CallPhase.Active,
                    connectedAtMs = System.currentTimeMillis(),
                    note = "",
                )
            }
        }
    }

    /**
     * انقطاع مؤقّت: نعرضه ونمنح الاتّصال مهلة للتعافي — يُلغى فوراً إن عاد
     * (`markConnected`)، وإلّا أُنهيت المكالمة برسالة واضحة.
     */
    private fun armReconnectGrace() {
        val snapshot = _state.value
        if (snapshot.phase != CallPhase.Active && snapshot.phase != CallPhase.Connecting) return
        if (reconnectJob?.isActive == true) return
        _state.update {
            if (it.phase == CallPhase.Idle || it.phase == CallPhase.Ended) {
                it
            } else {
                it.copy(note = "انقطع الاتصال — إعادة المحاولة…")
            }
        }
        reconnectJob = scope.launch {
            delay(RECONNECT_GRACE_MS)
            val current = _state.value
            if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) return@launch
            if (current.callId.isNotEmpty()) writeFinalStatus(current.callId, decline = false)
            finish("انقطع الاتصال")
            scope.launch {
                logOutcome(
                    current,
                    if (current.connectedAtMs > 0L) CallOutcome.Answered else CallOutcome.Missed,
                )
            }
        }
    }

    /**
     * مهلة الرنين عند **المستقبِل** ومراقبة وثيقته طوالها.
     *
     * ⚠️ بلا هذا يبقى الجهاز في طور `Ringing` (أي `busy`) إلى الأبد إن ضاعت
     * دفعة الإلغاء (نوم الجهاز/انقطاع الشبكة): `watchCall` لا تبدأ إلّا بعد
     * القبول، فلا مستمع على الوثيقة قبله.
     */
    private fun armIncomingRing(context: Context, callId: String) {
        val app = context.applicationContext
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(AppConfig.CALL_RING_TIMEOUT_MS)
            val current = _state.value
            if (current.callId == callId && current.phase == CallPhase.Ringing) {
                AdminCallNotifications.cancelIncoming(app)
                // محليّاً فقط: المتّصل وحده من يكتب «فائتة» ويسجّلها.
                finish("لم يُجب")
            }
        }
        ringWatchJob?.cancel()
        ringWatchJob = scope.launch {
            CallRepository.callStream(callId).collect { doc ->
                if (doc == null || !doc.isTerminal) return@collect
                val current = _state.value
                if (current.callId != callId || current.phase != CallPhase.Ringing) return@collect
                AdminCallNotifications.cancelIncoming(app)
                finish(
                    when (doc.status) {
                        CallStatus.DECLINED -> "رُفضت المكالمة"
                        CallStatus.BUSY -> "الطرف الآخر مشغول"
                        CallStatus.MISSED -> "لم يُجب"
                        else -> "انتهت المكالمة"
                    },
                )
            }
        }
    }

    /**
     * كتابة الحالة النهائيّة للوثيقة **بلا أن يتوقّف عليها شيء محليّاً**:
     * تُطلق في مهمّة مستقلّة بمهلة، فتعثّر المعاملة بلا شبكة لا يجمّد الواجهة.
     */
    private fun writeFinalStatus(callId: String, decline: Boolean) {
        if (callId.isEmpty()) return
        scope.launch {
            withTimeoutOrNull(REMOTE_WRITE_TIMEOUT_MS) {
                runCatching {
                    if (decline) CallRepository.decline(callId) else CallRepository.end(callId)
                }
            }
        }
    }

    /** مهلة الرنين عند المتصل: تُقلب المكالمة «فائتة» إن لم يُجَب. */
    private fun armRingTimeout(callId: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(AppConfig.CALL_RING_TIMEOUT_MS)
            if (_state.value.callId == callId && _state.value.phase == CallPhase.Dialing) {
                withTimeoutOrNull(REMOTE_WRITE_TIMEOUT_MS) {
                    runCatching { CallRepository.markMissed(callId) }
                }
                logOutcome(_state.value, CallOutcome.Missed)
                finish("لم يُجب")
            }
        }
    }

    /**
     * انتظار وصول العرض في وثيقة المكالمة (حتى 15 ثانية): إشعار FCM قد
     * يسبق اكتمال كتابة `offer` من المتصل.
     */
    private suspend fun awaitOffer(callId: String): CallDoc? = withTimeoutOrNull(15_000L) {
        CallRepository.callStream(callId).first { doc ->
            doc != null && !doc.offerSdp.isNullOrEmpty()
        }
    }

    /**
     * سجلّ المكالمة في المحادثة — **المتصل وحده يكتبه** (وإلّا ظهرت
     * الرسالة مرّتين لأن الطرفين يريان تغيّر الحالة نفسه).
     */
    private suspend fun logOutcome(snapshot: CallUiState, outcome: CallOutcome) {
        if (outcomeLogged || snapshot.incoming) return
        val peerUid = snapshot.peerUid
        val me = myUid
        if (peerUid.isEmpty() || me.isEmpty()) return
        outcomeLogged = true
        val seconds = if (snapshot.connectedAtMs > 0L) {
            ((System.currentTimeMillis() - snapshot.connectedAtMs) / 1000L).toInt()
        } else {
            0
        }
        runCatching {
            CallRepository.logCallMessage(
                threadId = DmPaths.threadId(me, peerUid),
                otherUid = peerUid,
                outcome = outcome,
                seconds = seconds,
            )
        }
    }

    /** إغلاق موحَّد: يعرض السبب لحظات ثمّ يوقف الخدمة ويصفّر الحالة. */
    private fun finish(note: String) {
        timeoutJob?.cancel()
        ringWatchJob?.cancel()
        reconnectJob?.cancel()
        if (_state.value.phase != CallPhase.Idle) {
            _state.update { it.copy(phase = CallPhase.Ended, note = note) }
        }
        // تُستدعى دائماً: الرفض من الإشعار قد يصل والحالة فارغة أصلاً.
        router?.stopCallService()
        scope.launch {
            delay(1800)
            if (_state.value.phase == CallPhase.Ended) _state.value = CallUiState()
        }
    }
}

// ───────── جسور suspend فوق SdpObserver (الواجهة الأصليّة بردود نداء) ─────────

private fun voiceConstraints() = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
}

private suspend fun PeerConnection.createOfferAwait(): SessionDescription? =
    suspendCancellableCoroutine { cont ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (cont.isActive) cont.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            voiceConstraints(),
        )
    }

private suspend fun PeerConnection.createAnswerAwait(): SessionDescription? =
    suspendCancellableCoroutine { cont ->
        createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (cont.isActive) cont.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            voiceConstraints(),
        )
    }

private suspend fun PeerConnection.setLocalAwait(description: SessionDescription): Boolean =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }

private suspend fun PeerConnection.setRemoteAwait(description: SessionDescription): Boolean =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }
