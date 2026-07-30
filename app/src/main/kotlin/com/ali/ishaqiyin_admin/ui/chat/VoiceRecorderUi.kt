package com.ali.ishaqiyin_admin.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.util.AudioRecorderController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.abs

/** عتبة السحب (إلغاء أفقيّاً / قفل رأسيّاً) — نفس إحساس واتساب. */
private const val SWIPE_THRESHOLD_DP = 72

/** أطوار تجربة التسجيل الصوتي. */
enum class VoicePhase {
    /** لا تسجيل — شريط الإدخال العاديّ. */
    Idle,

    /** الإصبع على زرّ الميكروفون (اضغط-مع-الاستمرار). */
    Holding,

    /** مقفول: التسجيل مستمرّ بلا إصبع. */
    Locked,

    /** انتهى التسجيل ويُعايَن قبل الإرسال. */
    Preview,
}

/**
 * 🎙️ حالة تسجيل الرسائل الصوتيّة — مصدر واحد لشاشتَي المجموعة والخاصّ
 * (كان المنطق مكرّراً حرفيّاً فيهما فيتباعد سلوكهما مع كلّ إصلاح).
 *
 * تُنشأ عبر [rememberVoiceRecorderState] وتُقاد من زرّ الميكروفون بإيماءتين
 * متعايشتين كما في واتساب الحديث: **نقرة قصيرة** = بدء تسجيل مقفول فوراً،
 * و**ضغط مطوّل** = تسجيل ما دام الإصبع (سحب جانبيّ = إلغاء، سحب للأعلى =
 * قفل، وإفلات = إرسال).
 */
@Stable
class VoiceRecorderState internal constructor(
    private val recorder: AudioRecorderController,
    private val appContext: Context,
    private val prefix: String,
) {
    var phase by mutableStateOf(VoicePhase.Idle)
        private set

    var seconds by mutableIntStateOf(0)
        private set

    var paused by mutableStateOf(false)
        private set

    /**
     * إزاحة الإصبع الخام بالبكسل — يتبعها مؤشّر «اسحب للإلغاء» كما هي،
     * فالاتجاه معكوس في RTL والمقارنة تجري على القيمة المطلقة.
     */
    var dragX by mutableFloatStateOf(0f)
        private set

    var dragY by mutableFloatStateOf(0f)
        private set

    internal var onNotice: (String) -> Unit = {}
    internal var onSendRecording: (File, Long, List<Int>?) -> Unit = { _, _, _ -> }
    internal var requestPermission: () -> Unit = {}

    private var previewFile: File? = null
    private var previewWave: List<Int>? = null
    private var previewMs = 0L

    /** مدّة المقطع المعايَن بالثواني. */
    var previewSeconds by mutableIntStateOf(0)
        private set

    /** عيّنات السعة الحيّة أثناء التسجيل (0..100). */
    val amplitudes: StateFlow<List<Int>> get() = recorder.amplitudes

    /** الشريط المستقلّ يحلّ محلّ شريط الإدخال في القفل والمعاينة فقط. */
    val showsBar: Boolean get() = phase == VoicePhase.Locked || phase == VoicePhase.Preview

    internal val recordingNow: Boolean
        get() = phase == VoicePhase.Holding || phase == VoicePhase.Locked

    /** مفتاح تشغيل مؤقّت للمعاينة — لا يخصّ أيّ رسالة فلا يصطدم بها. */
    val previewKey: String get() = PREVIEW_KEY_PREFIX + previewFile?.absolutePath.orEmpty()

    val previewWaveform: List<Int> get() = previewWave.orEmpty()

    // ⚠️ المدّة تُشتقّ من ساعة أحاديّة لا من عدّاد ثوانٍ: مؤقّت الواجهة
    // يُعاد تشغيله عند كلّ تغيّر طور (قفل/إيقاف مؤقّت) فتضيع أجزاء الثانية،
    // وتصبح المدّة المرسَلة مع المرفق أقصر من الصوت الفعليّ.
    private var startedAtMs = 0L
    private var accumulatedMs = 0L

    /** المدّة الفعليّة للتسجيل بالمللي ثانية (تستثني فترات الإيقاف المؤقّت). */
    private val elapsedMs: Long
        get() = accumulatedMs +
            if (recordingNow && !paused) SystemClock.elapsedRealtime() - startedAtMs else 0L

    internal fun tick() {
        seconds = (elapsedMs / 1000L).toInt()
    }

    /**
     * بدء التسجيل (بعد ثبوت الضغط المطوّل، أو فوراً عند النقرة القصيرة عبر
     * [beginLocked]). إذن الميكروفون خطر ويجب طلبه وقت التشغيل — وبلا طلبه
     * كان التسجيل يفشل بصمت على أوّل تثبيت. ويُرفض أيّ بدء ثانٍ فوق تسجيل
     * جارٍ (`phase != Idle`).
     */
    internal fun beginHold(): Boolean {
        if (phase != VoicePhase.Idle) return false
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermission()
            return false
        }
        return runCatching {
            recorder.start(appContext, prefix)
            seconds = 0
            startedAtMs = SystemClock.elapsedRealtime()
            accumulatedMs = 0L
            paused = false
            dragX = 0f
            dragY = 0f
            phase = VoicePhase.Holding
            true
        }.onFailure { onNotice("تعذّر بدء التسجيل: ${it.message ?: it}") }.getOrDefault(false)
    }

    internal fun onDrag(x: Float, y: Float) {
        if (phase != VoicePhase.Holding) return
        dragX = x
        dragY = y
    }

    /** قفل التسجيل: يستمرّ بلا إصبع (سحب للأعلى). */
    internal fun lock() {
        if (phase != VoicePhase.Holding) return
        dragX = 0f
        dragY = 0f
        phase = VoicePhase.Locked
    }

    /**
     * نقرة واحدة على الميكروفون = تسجيل فوريّ **مقفول** (سلوك واتساب
     * الحديث): يبدأ التسجيل ويستمرّ بعد رفع الإصبع، وتُدار بقيّته من أزرار
     * شريط التسجيل (حذف/إيقاف مؤقّت/إنهاء/إرسال). يمرّ بـ[beginHold] فيرث
     * منها طلب إذن الميكروفون ومنع التسجيل المتزامن.
     */
    internal fun beginLocked(): Boolean {
        if (!beginHold()) return false
        lock()
        return true
    }

    /** إفلات الإصبع = إرسال (ما لم يكن مقفولاً أو ملغى). */
    internal fun releaseHold() {
        if (phase != VoicePhase.Holding) return
        stopAndSend()
    }

    /** إيقاف مؤقّت/استئناف في الوضع المقفول. */
    fun togglePause() {
        if (phase != VoicePhase.Locked) return
        if (paused) {
            if (recorder.resume()) {
                startedAtMs = SystemClock.elapsedRealtime()
                paused = false
            } else {
                onNotice("تعذّر استئناف التسجيل.")
            }
        } else {
            if (recorder.pause()) {
                accumulatedMs = elapsedMs
                paused = true
            } else {
                onNotice("الإيقاف المؤقّت غير مدعوم على هذا الجهاز.")
            }
        }
    }

    /** إيقاف التسجيل في الوضع المقفول ⇒ معاينة قبل الإرسال (نمط واتساب). */
    fun stopForPreview() {
        if (phase != VoicePhase.Locked) return
        // المدّة تُلتقط قبل الإيقاف من الساعة الأحاديّة (أدقّ من عدّاد الواجهة).
        val ms = elapsedMs
        val elapsed = (ms / 1000L).toInt()
        val file = recorder.stop()
        val wave = file?.let { runCatching { recorder.waveform() }.getOrNull() }
        if (elapsed < 1) {
            onNotice("التسجيل قصير جدّاً.")
            runCatching { file?.delete() }
            reset()
            return
        }
        if (file == null) {
            onNotice("تعذّر حفظ التسجيل على هذا الجهاز — أعد المحاولة.")
            reset()
            return
        }
        previewFile = file
        previewWave = wave
        previewSeconds = elapsed
        previewMs = ms
        paused = false
        phase = VoicePhase.Preview
    }

    /** تشغيل/متابعة المقطع المعايَن عبر المشغّل المشترك. */
    fun playPreview(context: Context) {
        val file = previewFile ?: return
        SharedAudioPlayer.playFile(context, previewKey, file)
    }

    /** إرسال الآن — من الوضع المقفول مباشرة أو بعد المعاينة. */
    fun sendNow() {
        when (phase) {
            VoicePhase.Locked -> stopAndSend()
            VoicePhase.Preview -> {
                val file = previewFile
                stopPreviewPlayback()
                val wave = previewWave
                val ms = previewMs
                previewFile = null
                previewWave = null
                previewSeconds = 0
                previewMs = 0L
                reset()
                if (file != null) onSendRecording(file, ms, wave)
            }

            else -> Unit
        }
    }

    /** حذف التسجيل — جارياً كان أو معايَناً. */
    fun discard() {
        stopPreviewPlayback()
        recorder.cancel()
        runCatching { previewFile?.delete() }
        previewFile = null
        previewWave = null
        previewSeconds = 0
        reset()
    }

    private fun stopAndSend() {
        val ms = elapsedMs
        val elapsed = (ms / 1000L).toInt()
        // ⚠️ بلا سقوط إلى الملفّ الخام: المسجّل يعيد null للتسجيل التالف
        // عمداً، وإرساله يُنتج «رسالة صوتيّة لا تعمل عند أحد».
        val file = recorder.stop()
        val wave = file?.let { runCatching { recorder.waveform() }.getOrNull() }
        reset()
        if (elapsed < 1) {
            onNotice("التسجيل قصير جدّاً.")
            runCatching { file?.delete() }
            return
        }
        if (file == null) {
            onNotice("تعذّر حفظ التسجيل على هذا الجهاز — أعد المحاولة.")
            return
        }
        onSendRecording(file, ms, wave)
    }

    private fun stopPreviewPlayback() {
        if (SharedAudioPlayer.activeKey.value == previewKey) SharedAudioPlayer.stop()
    }

    private fun reset() {
        phase = VoicePhase.Idle
        seconds = 0
        startedAtMs = 0L
        accumulatedMs = 0L
        paused = false
        dragX = 0f
        dragY = 0f
    }

    internal fun release() {
        stopPreviewPlayback()
        if (recordingNow) {
            // تسجيل جارٍ عند مغادرة الشاشة ⇒ يُلغى ويُحذف ملفّه.
            recorder.cancel()
        } else {
            // ⚠️ لا cancel() هنا: بعد الإرسال يبقى الملفّ مرجعاً للرفع
            // الجاري في ChatUploader، وحذفه يُفشِل الرفع.
            recorder.release()
        }
        runCatching { previewFile?.delete() }
        previewFile = null
        previewWave = null
        previewSeconds = 0
        reset()
    }
}

/**
 * ينشئ حالة التسجيل ويربطها بمؤقّتها وإذن الميكروفون ودورة حياة الشاشة.
 *
 * [prefix] بادئة اسم الملفّ المؤقّت (`voice` للمجموعة و`dm_voice` للخاصّ)،
 * و[onSend] يُستدعى بملفّ صالح للتشغيل فقط مع مدّته وموجته.
 */
@Composable
fun rememberVoiceRecorderState(
    prefix: String,
    onNotice: (String) -> Unit,
    onSend: (file: File, durationMs: Long, waveform: List<Int>?) -> Unit,
): VoiceRecorderState {
    val context = LocalContext.current
    val state = remember(prefix) {
        VoiceRecorderState(
            recorder = AudioRecorderController(),
            appContext = context.applicationContext,
            prefix = prefix,
        )
    }

    // إذن الميكروفون خطر: يُطلب وقت التشغيل، ولا يبدأ أيّ تسجيل قبل منحه.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onNotice(
            if (granted) {
                "تمّ السماح — اضغط على الميكروفون لبدء التسجيل."
            } else {
                "لم يُسمح باستخدام الميكروفون."
            },
        )
    }

    SideEffect {
        state.onNotice = onNotice
        state.onSendRecording = onSend
        state.requestPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    LaunchedEffect(state.phase, state.paused) {
        while (state.recordingNow && !state.paused) {
            delay(1000)
            state.tick()
        }
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }
    return state
}

/**
 * إيماءتان تتعايشان على زرّ الميكروفون كما في واتساب الحديث:
 * - **نقرة قصيرة** (انتهت قبل مهلة الضغط المطوّل) = بدء التسجيل فوراً في
 *   الوضع المقفول، فيرفع المستخدم إصبعه ويستمرّ التسجيل في شريطه.
 * - **ضغط مطوّل** = تسجيل ما دام الإصبع: سحب جانبيّ (بقيمته المطلقة فيصحّ
 *   في RTL) = إلغاء، سحب للأعلى = قفل، وإفلات = إرسال.
 */
private fun Modifier.voiceHoldGesture(
    state: VoiceRecorderState,
    haptic: HapticFeedback,
): Modifier = pointerInput(Unit) {
    val threshold = SWIPE_THRESHOLD_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var released = false
        val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            if (waitForUpOrCancellation() != null) released = true
        } == null
        if (!longPressed) {
            // نقرة قصيرة = تسجيل فوريّ مقفول (واتساب الحديث). شرط [released]
            // يستثني الإيماءة الملغاة (تمرير/سحب ابتلعه أبٌ) فلا تبدأ تسجيلاً
            // لم يطلبه أحد.
            if (released && state.beginLocked()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            return@awaitEachGesture
        }
        if (!state.beginHold()) return@awaitEachGesture
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        var offset = Offset.Zero
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            offset += change.positionChange()
            change.consume()
            if (state.phase != VoicePhase.Holding) continue
            // المحور الغالب هو الذي يحسم: سحبة إلغاء سريعة تنحرف للأعلى
            // كانت تُفسَّر قفلاً (الفرع الرأسي أوّلاً)، فيرفع المستخدم إصبعه
            // ظانّاً أنّه ألغى بينما التسجيل مستمرّ في شريط مقفول.
            val up = -offset.y
            val side = abs(offset.x)
            when {
                up > threshold && up >= side -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.lock()
                }

                side > threshold -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.discard()
                    cancelled = true
                }

                else -> state.onDrag(offset.x, offset.y)
            }
            if (cancelled) break
        }
        if (!cancelled) state.releaseHold()
    }
}

/**
 * زرّ الميكروفون — يحلّ محلّ زرّ الإرسال بلا نصّ. نقرة = تسجيل فوريّ مقفول،
 * وضغط مطوّل = تسجيل بالإصبع مع السحب للإلغاء/القفل (واتساب الحديث).
 */
@Composable
fun VoiceMicButton(state: VoiceRecorderState, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val holding = state.phase == VoicePhase.Holding
    val scale by animateFloatAsState(if (holding) 1.3f else 1f, label = "micScale")
    Surface(
        color = if (holding) ChatColors.rose else ChatColors.accentDark,
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(48.dp)
            .scale(scale)
            .voiceHoldGesture(state, haptic),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "تسجيل رسالة صوتيّة — اضغط للبدء",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * طبقة التسجيل أثناء الضغط المطوّل — تُرسم **فوق** شريط الإدخال بلا أي
 * تغيير في التخطيط.
 *
 * ⚠️ إلزاميّ: أيّ إزاحة لزرّ الميكروفون أثناء الإيماءة (إخفاء حقل النصّ أو
 * إغلاق لوحة المفاتيح أو إضافة صفّ فوقه) تُترجَم إلى سحبٍ وهميّ في
 * الإحداثيّات المحليّة فتُطلق القفل أو الإلغاء زوراً. لذلك لا شيء هنا يغيّر
 * أبعاد الشريط.
 */
@Composable
fun VoiceHoldOverlay(state: VoiceRecorderState, modifier: Modifier = Modifier) {
    val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val cancelProgress = (abs(state.dragX) / thresholdPx).coerceIn(0f, 1f)
    val lockProgress = (-state.dragY / thresholdPx).coerceIn(0f, 1f)
    Row(
        modifier
            .background(ChatColors.surface)
            // الطرف محجوز لزرّ الميكروفون: لا تغطِّه وإلّا حجبت إيماءته.
            .padding(start = 14.dp, end = 62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(active = true)
        Spacer(Modifier.width(8.dp))
        Text(
            timeLabel(state.seconds),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Row(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = state.dragX * 0.7f
                    alpha = 1f - cancelProgress * 0.75f
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = ChatColors.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text("اسحب للإلغاء", color = ChatColors.textMuted, fontSize = 12.5.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = ChatColors.textMuted.copy(alpha = 0.4f + lockProgress * 0.6f),
                modifier = Modifier.size(14.dp),
            )
            Icon(
                Icons.Filled.Lock,
                contentDescription = "اسحب للأعلى للقفل",
                tint = if (lockProgress >= 1f) ChatColors.accentDark else ChatColors.textMuted,
                modifier = Modifier.size((15 + 5 * lockProgress).dp),
            )
        }
    }
}

/**
 * شريط التسجيل المقفول أو المعاينة — يحلّ محلّ شريط الإدخال كاملاً
 * (مشترك بين المجموعة والخاصّ).
 */
@Composable
fun VoiceRecorderBar(state: VoiceRecorderState) {
    Surface(color = ChatColors.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(
                icon = Icons.Filled.Delete,
                description = "حذف التسجيل",
                tint = ChatColors.rose,
                onClick = { state.discard() },
            )
            if (state.phase == VoicePhase.Preview) {
                VoicePreviewContent(state, Modifier.weight(1f))
            } else {
                VoiceLockedContent(state, Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            FloatingActionButton(
                onClick = { state.sendNow() },
                containerColor = ChatColors.accentDark,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "إرسال الرسالة الصوتيّة",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceLockedContent(state: VoiceRecorderState, modifier: Modifier = Modifier) {
    val amplitudes by state.amplitudes.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(active = !state.paused)
        Spacer(Modifier.width(6.dp))
        Text(timeLabel(state.seconds), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        LiveWave(
            values = if (state.paused) amplitudes.map { 0 } else amplitudes,
            modifier = Modifier.weight(1f).height(26.dp),
        )
        Spacer(Modifier.width(4.dp))
        BarIcon(
            icon = if (state.paused) Icons.Filled.Mic else Icons.Filled.Pause,
            description = if (state.paused) "استئناف التسجيل" else "إيقاف مؤقّت",
            tint = ChatColors.accentDark,
            onClick = { state.togglePause() },
        )
        BarIcon(
            icon = Icons.Filled.Stop,
            description = "إنهاء التسجيل ومعاينته",
            tint = ChatColors.accentDark,
            onClick = { state.stopForPreview() },
        )
    }
}

@Composable
private fun VoicePreviewContent(state: VoiceRecorderState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activeKey by SharedAudioPlayer.activeKey.collectAsState()
    val playing by SharedAudioPlayer.playing.collectAsState()
    val positionMs by SharedAudioPlayer.positionMs.collectAsState()
    val mine = activeKey == state.previewKey
    LaunchedEffect(mine, playing) {
        while (mine && playing) {
            SharedAudioPlayer.syncPosition()
            delay(200)
        }
    }
    val totalMs = (state.previewSeconds * 1000L).coerceAtLeast(1L)
    val progress = if (mine) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BarIcon(
            icon = if (mine && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = "معاينة التسجيل",
            tint = ChatColors.accentDark,
            onClick = {
                if (mine && playing) SharedAudioPlayer.pause() else state.playPreview(context)
            },
        )
        Spacer(Modifier.width(4.dp))
        SeekWave(
            values = state.previewWaveform,
            progress = progress,
            modifier = Modifier.weight(1f).height(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        // رقم واحد فقط: «الموضع / الإجمالي» في سطر واحد ينقلب بصريّاً في RTL.
        Text(
            timeLabel(if (mine) (positionMs / 1000L).toInt() else state.previewSeconds),
            fontSize = 12.sp,
            color = ChatColors.textMuted,
        )
    }
}

/** نقطة تسجيل حمراء نابضة (تسكن عند الإيقاف المؤقّت). */
@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "recDotAlpha",
    )
    Icon(
        Icons.Filled.FiberManualRecord,
        contentDescription = null,
        tint = ChatColors.rose.copy(alpha = if (active) pulse else 0.3f),
        modifier = Modifier.size(12.dp),
    )
}

/** موجة حيّة من عيّنات السعة — الأحدث عند طرف الشريط. */
@Composable
private fun LiveWave(values: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.isEmpty() || size.width <= 0f) return@Canvas
        val barWidth = 3.dp.toPx()
        val step = barWidth + 2.dp.toPx()
        val capacity = (size.width / step).toInt().coerceAtLeast(1)
        val shown = values.takeLast(capacity)
        var x = size.width - shown.size * step
        shown.forEach { value ->
            val h = (size.height * (0.14f + 0.86f * (value.coerceIn(0, 100) / 100f)))
                .coerceAtLeast(barWidth)
            drawRoundRect(
                color = ChatColors.accent,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
            x += step
        }
    }
}

/** موجة ثابتة مع تقدّم التشغيل (للمعاينة). */
@Composable
private fun SeekWave(values: List<Int>, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (size.width <= 0f) return@Canvas
        val barWidth = 3.dp.toPx()
        val step = barWidth + 2.dp.toPx()
        val capacity = (size.width / step).toInt().coerceAtLeast(1)
        val source = values.ifEmpty { List(capacity) { 42 } }
        val shown = if (source.size > capacity) source.takeLast(capacity) else source
        shown.forEachIndexed { index, value ->
            val h = (size.height * (0.14f + 0.86f * (value.coerceIn(0, 100) / 100f)))
                .coerceAtLeast(barWidth)
            val played = (index + 1f) / shown.size <= progress
            drawRoundRect(
                color = if (played) ChatColors.accentDark else ChatColors.border,
                topLeft = Offset(index * step, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** زرّ دائريّ مضغوط داخل شريط التسجيل (أضيق من IconButton القياسيّ). */
@Composable
private fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(21.dp))
    }
}

private fun timeLabel(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)
