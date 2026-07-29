package com.ali.ishaqiyin_admin.call

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.R
import com.ali.ishaqiyin_admin.data.AdminCallNotifications
import com.ali.ishaqiyin_admin.data.AdminChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 🎙️ خدمة أماميّة تُبقي المكالمة حيّة بعد مغادرة الشاشة.
 *
 * تملك دورة حياة المكالمة: تشغّل [CallEngine] (صادرة أو قبول واردة)،
 * وتضبط `AudioManager` على وضع الاتصال وتوجيه مكبّر الصوت، وتعرض إشعاراً
 * مستمرّاً، وتحرّر كلّ شيء عند الإنهاء.
 *
 * ⚠️ تُبدأ دائماً والتطبيق في المقدّمة (من [CallActivity])، فقيد
 * «منع بدء خدمة أماميّة من الخلفيّة» على Android 12+ لا ينطبق.
 */
class CallService : Service(), CallAudioRouter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioManager: AudioManager? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var foregrounded = false
    private var audioFocusRequest: AudioFocusRequest? = null

    /** لا نتصرّف بفقد البؤرة (المكالمة أولى)، لكنّ النظام يشترط مستمعاً. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        CallEngine.attachRouter(this)
        acquireAudioFocusAndMode()
        // الإشعار يتتبّع الحالة، وانتهاء المكالمة من الطرف الآخر يوقف الخدمة.
        scope.launch {
            CallEngine.state.collectLatest { state ->
                if (state.phase == CallPhase.Idle) {
                    stopSelfSafely()
                    return@collectLatest
                }
                runCatching { notifyOngoing(state) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ أمرٌ جديد يُلغي أيّ إيقاف سابق: `onCreate` قد يكون أوقف الخدمة
        // (الحالة فارغة بعد إعادة تشغيل العمليّة) قبل وصول نيّة الرفض،
        // وبلا هذا التصفير تبقى الخدمة أماميّة إلى الأبد.
        stopping = false
        // الإشعار أوّلاً: النظام يقتل الخدمة إن تأخّر startForeground.
        startForegroundSafely()
        val action = intent?.action
        // فشل الإشعار الأماميّ ⇒ الخدمة تُوقَف الآن وقد صُفِّر المحرّك، فبدء
        // مكالمة هنا كان سيعمل بحالة فارغة. الإنهاء/الرفض تفكيكٌ يُنفَّذ دائماً.
        if (!foregrounded && action != ACTION_STOP && action != ACTION_DECLINE) {
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_OUTGOING -> CallEngine.runOutgoing(this)
            ACTION_ACCEPT -> CallEngine.runAccept(this)
            ACTION_STOP -> {
                CallEngine.hangUp()
                stopSelfSafely()
            }
            // رفض من زرّ الإشعار: معرّف المكالمة يأتي معه لأن العمليّة قد
            // تكون أُعيد تشغيلها ولا حالة محفوظة في الذاكرة.
            ACTION_DECLINE -> {
                // الإشعار `ongoing` لا يُمسح بنقر زرّه — يجب إلغاؤه صراحةً
                // وإلّا بقي «مكالمة واردة» ظاهراً بعد الرفض.
                AdminCallNotifications.cancelIncoming(this)
                CallEngine.declineIncoming(intent?.getStringExtra(EXTRA_CALL_ID).orEmpty())
                stopSelfSafely()
            }
            // إعادة تشغيل بلا نيّة (قتل النظام): لا مكالمة لنستأنفها.
            else -> if (intent == null) stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallEngine.detachRouter(this)
        releaseEngine()
        restoreAudio()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    // ───────────────────────── CallAudioRouter ─────────────────────────

    override fun applySpeaker(on: Boolean) {
        runCatching { audioManager?.isSpeakerphoneOn = on }
    }

    override fun stopCallService() {
        stopSelfSafely()
    }

    // ───────────────────────────── داخليّ ─────────────────────────────

    /**
     * ⚠️ [CallEngine.releaseAll] وحدها **لا تمسّ الحالة**: إن ماتت الخدمة
     * والمكالمة قائمة (قتل النظام، فشل الإشعار الأماميّ) بقي المحرّك `busy`
     * إلى الأبد فيموت زرّ الاتصال ويُتجاهَل كلّ وارد. هنا نكتب الحالة
     * النهائيّة للوثيقة (بلا انتظار) ثمّ نُصفّر المحرّك.
     */
    private fun releaseEngine() {
        val phase = CallEngine.state.value.phase
        if (phase == CallPhase.Idle || phase == CallPhase.Ended) {
            CallEngine.releaseAll()
            return
        }
        CallEngine.abandonRemoteState()
        CallEngine.forceIdle()
    }

    private fun stopSelfSafely() {
        stopping = true
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregrounded = false
        stopSelf()
    }

    /**
     * وضع الاتصال يمنع خلط صوت المكالمة بقناة الوسائط ويفعّل معالجة
     * الصدى في العتاد؛ ونحفظ الوضع السابق لنعيده بعد الإنهاء.
     */
    private fun acquireAudioFocusAndMode() {
        val manager = audioManager ?: return
        runCatching {
            previousMode = manager.mode
            previousSpeaker = manager.isSpeakerphoneOn
            // بلا طلب البؤرة فعليّاً تستمرّ موسيقى التطبيقات الأخرى فوق المكالمة.
            requestAudioFocus(manager)
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            manager.isSpeakerphoneOn = CallEngine.state.value.speaker
        }
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MinbarAdmin:call",
            )?.apply {
                // ساعتان: نصف ساعة كانت تسقط في المكالمات الطويلة فينام المعالج.
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    /** بؤرة صوتيّة حصريّة مؤقّتة — نمط المكالمات (تُسكِت غيرنا لا تخفضه). */
    private fun requestAudioFocus(manager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    private fun restoreAudio() {
        val manager = audioManager ?: return
        runCatching {
            manager.isSpeakerphoneOn = previousSpeaker
            manager.mode = previousMode
        }
        // كلّ لمسة لـ AudioFocusRequest داخل فحص الإصدار (النوع من API 26).
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        }
    }

    /**
     * ⚠️ إن فشل `startForeground` (إذن ميكروفون مسحوب على Android 14+)
     * يجب إيقاف الخدمة فوراً: تركها قيد التشغيل بلا إشعار أماميّ يُسقط
     * التطبيق بعد خمس ثوانٍ.
     */
    private fun startForegroundSafely() {
        if (foregrounded) return
        val notification = buildNotification(CallEngine.state.value)
        val ok = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        }.isSuccess
        if (ok) {
            foregrounded = true
        } else {
            // بلا إشعار أماميّ لا مكالمة: نُصفّر المحرّك أيضاً وإلّا بقي `busy`
            // وطوره نشط، فلا `startOutgoing` يعمل ولا وارد يُقبل.
            releaseEngine()
            stopSelfSafely()
        }
    }

    private fun notifyOngoing(state: CallUiState) {
        if (!foregrounded || stopping) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: CallUiState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            CallActivity.ongoingIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state.phase) {
            CallPhase.Active -> "مكالمة جارية — اضغط للعودة"
            CallPhase.Ended -> state.note.ifEmpty { "انتهت المكالمة" }
            else -> state.note.ifEmpty { "جارٍ الاتصال…" }
        }
        return NotificationCompat.Builder(this, AdminChannels.CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.peerName.ifEmpty { "مكالمة صوتيّة" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // الإشعار المستمرّ لا يرنّ: الرنين من إشعار المكالمة الواردة وحده.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "إنهاء", hangUp)
            .build()
    }

    companion object {
        const val ACTION_OUTGOING = "com.ali.ishaqiyin_admin.call.OUTGOING"
        const val ACTION_ACCEPT = "com.ali.ishaqiyin_admin.call.ACCEPT"
        const val ACTION_STOP = "com.ali.ishaqiyin_admin.call.STOP"
        const val ACTION_DECLINE = "com.ali.ishaqiyin_admin.call.DECLINE"
        const val EXTRA_CALL_ID = "callId"

        private const val NOTIFICATION_ID = 90_210
        private const val REQUEST_OPEN = 9021
        private const val REQUEST_STOP = 9022

        fun start(context: Context, action: String) {
            val intent = Intent(context, CallService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
