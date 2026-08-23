package com.ali.ishaqiyin_admin.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.data.AdminCallNotifications
import com.ali.ishaqiyin_admin.ui.MinbarAdminTheme

/**
 * 📞 شاشة المكالمة الصوتيّة (واردة/صادرة/جارية) — تظهر فوق شاشة القفل
 * وتوقظ الشاشة عند الرنين، بنمط واتساب.
 *
 * لا تملك منطق المكالمة: [CallEngine] هو مصدر الحقيقة و[CallService]
 * يبقيها حيّة، فإغلاق الشاشة لا يقطع المكالمة.
 */
class CallActivity : ComponentActivity() {
    private var pendingAction: (() -> Unit)? = null

    /**
     * ⚠️ حوار إذن الميكروفون معروض الآن والمحرّك ما يزال `Idle`.
     *
     * بلا هذا العلَم كانت **أوّل مكالمة صادرة تفشل صامتة**: `handle(intent)`
     * يطلق الحوار قبل `setContent`، فيرى أوّلُ تركيب لـ[CallScreen] الطورَ
     * `Idle` فيستدعي `onClose` فينتهي النشاط وهو ينتظر الردّ — فيضيع الردّ
     * ولا تُنشأ وثيقة مكالمة أصلاً.
     */
    private var awaitingPermission = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        awaitingPermission = false
        if (granted) {
            action?.invoke()
            // لم تبدأ أيّ مكالمة رغم الإذن (مشغول بأخرى مثلاً): لا شاشة
            // لنعرضها، والطور لم يتغيّر فلن يُعيد `LaunchedEffect` الإغلاق.
            if (!CallEngine.state.value.busy) finish()
        } else {
            // بلا ميكروفون لا مكالمة — نرفض الواردة بدل تركها ترنّ.
            val state = CallEngine.state.value
            if (state.incoming && state.callId.isNotEmpty()) {
                AdminCallNotifications.cancelIncoming(this)
                CallEngine.declineIncoming(state.callId)
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // شاشة المكالمة داكنة بملء الشاشة ⇒ أيقونات الشريطين فاتحة.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        showOverLockScreen()
        // ⛔ الرجوع من شاشة الرنين بلا رفض كان يُنهي النشاط والحالة تبقى
        // `Ringing` (أي `busy`)، ولا تُعاد الشاشة لأنّ المعرّف صار في
        // openedIncomingIds ⇒ المستقبِل عالق «مشغولاً» بلا واجهة ولا يقدر
        // على مكالمة. الرجوع الآن يرفض المكالمة كزرّ «رفض» تماماً.
        onBackPressedDispatcher.addCallback(this) {
            val current = CallEngine.state.value
            if (current.incoming &&
                current.phase == CallPhase.Ringing &&
                current.callId.isNotEmpty()
            ) {
                AdminCallNotifications.cancelIncoming(this@CallActivity)
                CallEngine.declineIncoming(current.callId)
            }
            finish()
        }
        handle(intent)
        setContent {
            MinbarAdminTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val state by CallEngine.state.collectAsState()
                    CallScreen(
                        state = state,
                        onAccept = {
                            withMic {
                                AdminCallNotifications.cancelIncoming(this)
                                CallEngine.acceptIncoming(this, state.callId)
                            }
                        },
                        onDecline = {
                            AdminCallNotifications.cancelIncoming(this)
                            CallEngine.declineIncoming(state.callId)
                            finish()
                        },
                        onHangUp = { CallEngine.hangUp() },
                        onToggleMute = { CallEngine.toggleMute() },
                        onToggleSpeaker = { CallEngine.toggleSpeaker() },
                        // لا نُغلق ونحن ننتظر ردّ إذن الميكروفون: الطور
                        // `Idle` هنا مؤقّت، والإغلاق كان يُجهض المكالمة.
                        onClose = { if (!awaitingPermission) finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(source: Intent?) {
        val data = source
        if (data == null) {
            // إعادة إنشاء بلا نيّة: لا نعرض شاشة فارغة.
            if (!CallEngine.state.value.busy) finish()
            return
        }
        val mode = data.getStringExtra(EXTRA_MODE).orEmpty()
        val peerName = data.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val peerPhoto = data.getStringExtra(EXTRA_PEER_PHOTO).orEmpty()
        val peerUid = data.getStringExtra(EXTRA_PEER_UID).orEmpty()
        when (mode) {
            MODE_OUTGOING -> {
                if (CallEngine.state.value.busy) return
                if (peerUid.isEmpty()) {
                    finish()
                    return
                }
                withMic {
                    CallEngine.startOutgoing(
                        context = this,
                        calleeUid = peerUid,
                        calleeName = peerName,
                        calleePhoto = peerPhoto,
                    )
                }
                consumeIntent()
            }

            // شاشة الرنين (نيّة كامل الشاشة)، أو قبول مباشر من زرّ الإشعار.
            MODE_INCOMING, MODE_ANSWER -> {
                val callId = data.getStringExtra(EXTRA_CALL_ID).orEmpty()
                if (callId.isEmpty()) {
                    finish()
                    return
                }
                val adopted = CallEngine.prepareIncoming(
                    context = this,
                    callId = callId,
                    callerUid = peerUid,
                    callerName = peerName,
                    callerPhoto = peerPhoto,
                )
                // مشغول بمكالمة أخرى: أُسقطت الواردة تلقائيّاً، وتبقى الشاشة
                // على المكالمة القائمة (القبول هنا كان يخطفها).
                if (!adopted) return
                if (mode == MODE_ANSWER) {
                    withMic {
                        AdminCallNotifications.cancelIncoming(this)
                        CallEngine.acceptIncoming(this, callId)
                    }
                    consumeIntent()
                }
            }

            // MODE_ONGOING: عرض الحالة القائمة فقط.
            else -> if (!CallEngine.state.value.busy) finish()
        }
    }

    /**
     * ⚠️ نيّة «ابدأ/ردّ» تُستهلك مرّة واحدة: النشاط بلا `configChanges`، فأيّ
     * تدوير أو تغيير إعداد كان يُعيد `onCreate` بالنيّة الأصليّة فيُعاد
     * تنفيذها على مكالمة قائمة. بعدها تكفي «مكالمة جارية» لعرض الحالة.
     */
    private fun consumeIntent() {
        // ما دام حوار الميكروفون معروضاً فالمكالمة لم تبدأ: تبقى النيّة الأصليّة.
        if (!awaitingPermission) setIntent(ongoingIntent(this))
    }

    /** إذن الميكروفون شرط لبدء أو قبول أيّ مكالمة (نفس نمط التسجيل). */
    private fun withMic(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingAction = action
            // ⚠️ العلَم **قبل** إطلاق الحوار: أوّل تركيب للشاشة قد يسبق ردّ
            // المستخدم، وبلاه يُغلق نفسه ويُجهض المكالمة قبل أن تبدأ.
            awaitingPermission = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PEER_UID = "peerUid"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_PEER_PHOTO = "peerPhoto"

        const val MODE_OUTGOING = "outgoing"
        const val MODE_INCOMING = "incoming"
        const val MODE_ANSWER = "answer"
        const val MODE_ONGOING = "ongoing"

        private fun base(context: Context) = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        /** مكالمة صادرة من شريط المحادثة الخاصّة. */
        fun outgoingIntent(
            context: Context,
            peerUid: String,
            peerName: String,
            peerPhoto: String,
        ): Intent = base(context)
            .putExtra(EXTRA_MODE, MODE_OUTGOING)
            .putExtra(EXTRA_PEER_UID, peerUid)
            .putExtra(EXTRA_PEER_NAME, peerName)
            .putExtra(EXTRA_PEER_PHOTO, peerPhoto)

        /**
         * شاشة مكالمة واردة. [answerNow] للزرّ «ردّ» في الإشعار (يقبل
         * مباشرة بلا نقرة ثانية)، وإلّا شاشة رنين بزرّي قبول/رفض.
         */
        fun incomingIntent(
            context: Context,
            callId: String,
            callerUid: String,
            callerName: String,
            callerPhoto: String,
            answerNow: Boolean = false,
        ): Intent = base(context)
            .putExtra(EXTRA_MODE, if (answerNow) MODE_ANSWER else MODE_INCOMING)
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_PEER_UID, callerUid)
            .putExtra(EXTRA_PEER_NAME, callerName)
            .putExtra(EXTRA_PEER_PHOTO, callerPhoto)

        /** العودة لمكالمة جارية من الإشعار المستمرّ. */
        fun ongoingIntent(context: Context): Intent =
            base(context).putExtra(EXTRA_MODE, MODE_ONGOING)
    }
}
