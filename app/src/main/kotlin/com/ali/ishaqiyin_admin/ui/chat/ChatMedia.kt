package com.ali.ishaqiyin_admin.ui.chat

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.ChatAttachment
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.MediaState
import com.ali.ishaqiyin_admin.data.MediaStatus
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.util.openLocalFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** بادئة مفاتيح معاينة التسجيل الجاري (ملفّ مؤقّت لا يُستأنف). */
const val PREVIEW_KEY_PREFIX = "preview:"

/**
 * مشغّل صوت مشترك — مشغّل واحد فقط يعمل في أيّ لحظة (مثل واتساب).
 * يشغّل **ملفّاً محليّاً** دائماً (بعد التنزيل) فلا يتأثّر بالشبكة إطلاقاً.
 */
object SharedAudioPlayer {
    private const val TAG = "SharedAudioPlayer"
    private const val MAX_SAVED_POSITIONS = 50
    private var player: ExoPlayer? = null

    private val _activeKey = MutableStateFlow<String?>(null)
    val activeKey: StateFlow<String?> = _activeKey

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    /** سرعة التشغيل (تُطبَّق على المقطع الحالي والتالي — مثل واتساب). */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    /**
     * موضع كلّ مقطع لم يكتمل — يُستأنف منه عند إعادة تشغيله (نمط واتساب)،
     * ويُحذف عند الإكمال الطبيعيّ.
     */
    private val savedPositions = mutableMapOf<String, Long>()

    /**
     * يُستدعى بمفتاح المقطع فور انتهائه — تستعمله شاشة الدردشة لتشغيل
     * الرسالة الصوتيّة التالية تلقائياً (نمط واتساب).
     */
    @Volatile
    var onCompleted: ((String) -> Unit)? = null

    /** آخر خطأ تشغيل — تعرضه الفقاعة بدل الصمت المُربك. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    private fun ensure(context: Context): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context.applicationContext)
            // ⚠️ إلزاميّ لتوافق الأجهزة: بلا سمات صوت صريحة وطلب البؤرة
            // الصوتيّة، تخفض بعض واجهات المصنّعين (شاومي/أوبو/فيفو) صوت
            // التطبيق أو تكتمه إذا كان تطبيق آخر يحتفظ بالبؤرة — فيبدو
            // للمشرف أنّ «الصوت لا يعمل» بينما التشغيل جارٍ فعلاً.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // إيقاف تلقائي عند نزع السمّاعة بدل بثّ الصوت على مكبّر الجهاز.
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }

                override fun onPlayerError(error: PlaybackException) {
                    // كان الفشل صامتاً تماماً: لا تشغيل ولا رسالة، فيظنّ
                    // المشرف أنّ التطبيق معطّل. الآن يُعلَن سببه.
                    Log.w(TAG, "playback failed: ${error.errorCodeName}", error)
                    _error.value = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            "الملفّ الصوتي غير موجود — أعد تنزيله."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                        ->
                            "هذا التسجيل تالف ولا يمكن تشغيله."
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        ->
                            "جهازك لا يدعم ترميز هذا المقطع."
                        else -> "تعذّر تشغيل المقطع."
                    }
                    stop()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _durationMs.value = created.duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) {
                        val finished = _activeKey.value
                        // ⚠️ الحذف **بعد** stop()، فهي تبدأ بـstashPosition التي
                        // تعيد كتابة المفتاح (duration تعود TIME_UNSET عند الانتهاء
                        // فيمرّ شرط الحفظ) — فكان المقطع يُستأنف من نهايته.
                        stop()
                        if (finished != null) {
                            savedPositions.remove(finished)
                            onCompleted?.invoke(finished)
                        }
                    }
                }
            },
        )
        // سرعة التشغيل المحفوظة من الجلسة السابقة (نمط واتساب).
        _speed.value = runCatching { AppPrefs.audioSpeed }.getOrDefault(1f)
        runCatching { created.setPlaybackSpeed(_speed.value) }
        player = created
        return created
    }

    /**
     * يحفظ موضع المقطع المغادَر إن لم يكتمل، ويمحوه إن كان في أوّله أو
     * قارب نهايته — فالاستئناف يفيد المقاطع الطويلة وحدها.
     */
    private fun stashPosition() {
        val key = _activeKey.value ?: return
        // معاينة تسجيل جارٍ: ملفّها مؤقّت باسم لا يتكرّر ويُحذف بعد الإرسال،
        // فحفظ موضعها نفاية دائمة لا تُستهلك أبداً.
        if (key.startsWith(PREVIEW_KEY_PREFIX)) return
        val p = player ?: return
        val pos = runCatching { p.currentPosition }.getOrDefault(0L)
        val dur = runCatching { p.duration }.getOrDefault(0L)
        if (pos > 1_500L && (dur <= 0L || pos < dur - 1_500L)) {
            // سقف بسيط كي لا تنمو الخريطة بلا حدّ في جلسة طويلة.
            if (savedPositions.size >= MAX_SAVED_POSITIONS) {
                savedPositions.keys.firstOrNull()?.let(savedPositions::remove)
            }
            savedPositions[key] = pos
        } else {
            savedPositions.remove(key)
        }
    }

    fun playFile(context: Context, key: String, file: File) {
        val p = ensure(context)
        _error.value = null
        if (_activeKey.value == key) {
            p.play()
            return
        }
        // المقطع المغادَر يحتفظ بموضعه قبل استبداله.
        stashPosition()
        _activeKey.value = key
        _positionMs.value = 0
        _durationMs.value = 0
        p.stop()
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        p.setPlaybackSpeed(_speed.value)
        p.prepare()
        // استئناف من حيث توقّف المشرف آخر مرّة.
        savedPositions.remove(key)?.let {
            runCatching { p.seekTo(it) }
            _positionMs.value = it
        }
        p.play()
    }

    fun setSpeed(v: Float) {
        _speed.value = v
        runCatching { AppPrefs.audioSpeed = v }
        runCatching { player?.setPlaybackSpeed(v) }
    }

    fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms) }
        _positionMs.value = ms
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun stop() {
        stashPosition()
        _activeKey.value = null
        _playing.value = false
        _positionMs.value = 0
        runCatching { player?.stop() }
    }

    /** تحديث الموضع دوريّاً أثناء التشغيل (يستدعيه شريط الفقاعة). */
    fun syncPosition() {
        val p = player ?: return
        _positionMs.value = p.currentPosition.coerceAtLeast(0L)
        if (p.duration > 0) _durationMs.value = p.duration
    }
}

/** عدد أعمدة الموجة المعروضة (مطابق لما يُسجَّل مع المرفق). */
private const val WAVE_BARS = 40

/**
 * موجة حتميّة للرسائل القديمة التي لا تحمل بيانات موجة: نفس المعرّف يعطي
 * نفس الشكل دائماً، فلا يتبدّل المنظر عند كلّ إعادة تركيب.
 *
 * ليست ضجيجاً منتظماً (كان يعطي موجة متعرّجة قبيحة لا تشبه الكلام)، بل
 * مشية عشوائيّة بغلاف حديث: مقاطع نشطة متدرّجة تتخلّلها سكتات قصيرة، ثم
 * تنعيم ثلاثيّ النقاط — فتبدو موجة صوت بشريّ كموجات واتساب.
 */
private fun fallbackWaveform(seed: String): List<Int> {
    var h = seed.hashCode().toLong() and 0xFFFFFFFFL
    if (h == 0L) h = 0x9E3779B9L
    fun next(): Long {
        h = (h * 6364136223846793005L + 1442695040888963407L) ushr 1
        return h
    }
    val raw = IntArray(WAVE_BARS)
    var level = 45 + (next() % 25L).toInt()
    var pause = 0
    for (i in 0 until WAVE_BARS) {
        if (pause > 0) {
            pause--
            level = 12 + (next() % 8L).toInt()
        } else {
            level = (level + (next() % 37L).toInt() - 18).coerceIn(22, 96)
            if (next() % 9L == 0L) pause = 1 + (next() % 2L).toInt()
        }
        raw[i] = level
    }
    return List(WAVE_BARS) { i ->
        val a = raw[(i - 1).coerceAtLeast(0)]
        val b = raw[i]
        val c = raw[(i + 1).coerceAtMost(WAVE_BARS - 1)]
        (a + 2 * b + c) / 4
    }
}

/**
 * فقاعة صوت بنمط واتساب: صورة المرسل بشارة ميكروفون، زرّ تشغيل بلا خلفيّة،
 * موجة قابلة للسحب، ومدّة واحدة (المدّة عند السكون والزمن المنقضي أثناء
 * التشغيل — رقمان في سطر واحد ينعكسان بصريّاً في RTL). التنزيل أوّلاً ثم
 * تشغيل من الملفّ المحلّي، فيعمل دون إنترنت بعد التنزيل.
 */
@Composable
fun AudioBubblePlayer(
    attachment: ChatAttachment,
    isVoice: Boolean,
    messageId: String = "",
    senderUid: String = "",
    senderName: String = "",
    senderPhoto: String = "",
    mine: Boolean = false,
    listened: Boolean = false,
    onListened: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember(attachment) { ChatMediaStore.keyOf(attachment) }
    val status by ChatMediaStore.statusOf(attachment).collectAsState()
    val activeKey by SharedAudioPlayer.activeKey.collectAsState()
    val playing by SharedAudioPlayer.playing.collectAsState()
    val positionMs by SharedAudioPlayer.positionMs.collectAsState()
    val durationMs by SharedAudioPlayer.durationMs.collectAsState()
    val speed by SharedAudioPlayer.speed.collectAsState()

    val active = activeKey == key && status.isReady
    val playbackError by SharedAudioPlayer.error.collectAsState()

    LaunchedEffect(active, playing) {
        while (active && playing) {
            SharedAudioPlayer.syncPosition()
            delay(300)
        }
    }

    val fallbackDuration = attachment.durationMs ?: 0L
    val total = if (active && durationMs > 0) durationMs else fallbackDuration
    val position = if (active) positionMs else 0L
    val progress = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f

    // موجة المرفق إن وُجدت، وإلّا موجة حتميّة (رسائل ما قبل الميزة).
    val bars = remember(attachment, messageId) {
        attachment.waveform?.takeIf { it.isNotEmpty() }
            ?: fallbackWaveform(messageId.ifEmpty { attachment.url })
    }

    // شارة الاستماع تُسجَّل مرّة واحدة عند أوّل تشغيل فعليّ لرسالة ليست لي.
    // مقيَّدة بالرسائل الصوتيّة: الشارة لا تُرسم أصلاً لمرفقات الصوت العامّة،
    // فكتابتها لها ضربٌ في Firestore بلا أيّ أثر مرئيّ.
    var reported by remember(messageId) { mutableStateOf(false) }
    fun reportListened() {
        if (isVoice && !mine && !listened && !reported) {
            reported = true
            onListened?.invoke()
        }
    }

    Row(
        Modifier.width(262.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1) صورة المرسل بشارة ميكروفون — تحلّ محلّها رقاقة السرعة أثناء
        //    التشغيل (سلوك واتساب الحرفيّ).
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                active -> SpeedChip(speed)
                isVoice -> VoiceAvatar(senderUid, senderName, senderPhoto, listened)
                // ملفّ صوتيّ عامّ: نوتة موسيقيّة بلا صورة مرسل.
                else -> Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = ChatColors.accentDark,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // 2) زرّ تشغيل/إيقاف بلا خلفيّة دائريّة.
        Box(
            Modifier
                .size(38.dp)
                .clickable {
                    scope.launch {
                        SharedAudioPlayer.clearError()
                        when {
                            status.state == MediaState.Downloading -> Unit
                            status.state == MediaState.Failed -> {
                                val f = ChatMediaStore.retry(attachment)
                                if (f != null) {
                                    reportListened()
                                    SharedAudioPlayer.playFile(context, key, f)
                                }
                            }
                            !status.isReady -> {
                                val f = ChatMediaStore.download(attachment)
                                if (f != null) {
                                    reportListened()
                                    SharedAudioPlayer.playFile(context, key, f)
                                }
                            }

                            active && playing -> SharedAudioPlayer.pause()
                            else -> status.file?.let {
                                reportListened()
                                SharedAudioPlayer.playFile(context, key, it)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (status.state == MediaState.Downloading) {
                CircularProgressIndicator(
                    progress = { (status.progress / 100).toFloat() },
                    strokeWidth = 2.4.dp,
                    color = ChatColors.accentDark,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    when {
                        !status.isReady -> Icons.Filled.Download
                        active && playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = ChatColors.accentDark,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            // 3) الموجة بديلة الشريط: نقر/سحب = تقديم داخل المقطع النشط.
            WaveformSeekBar(
                bars = bars,
                progress = progress,
                played = if (mine) ChatColors.accentDark else ChatColors.accent,
                // رمادي محايد كغير-المسموع في واتساب — الأزرق المخضرّ الباهت
                // السابق كان يذوب في خلفيّة الفقاعة فتبدو الموجة ممسوحة.
                rest = Color(0xFFBFC8CE),
                enabled = active && total > 0,
                playing = active && playing,
                onSeek = { fraction -> SharedAudioPlayer.seekTo((fraction * total).toLong()) },
            )
            Spacer(Modifier.height(3.dp))
            // 4) سطر الحالة: رقم واحد لا رقمان (يزول التباس اتجاه RTL).
            //    خطأ التشغيل يخصّ المقطع النشط وحده فيُعرض مكانه.
            val showError = playbackError != null && activeKey == key
            Text(
                if (showError) {
                    playbackError.orEmpty()
                } else {
                    statusLine(status, position, total, attachment)
                },
                fontSize = 10.5.sp,
                color = if (showError) ChatColors.rose else ChatColors.textMuted,
            )
        }
    }
}

/** صورة المرسل الدائريّة بشارة ميكروفون (تزرقّ بعد الاستماع). */
@Composable
private fun VoiceAvatar(uid: String, name: String, photo: String, listened: Boolean) {
    Box(Modifier.size(44.dp)) {
        MemberAvatar(uid = uid, name = name, photo = photo, radius = 20)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(16.dp)
                .background(ChatColors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = if (listened) ChatColors.readBlue else ChatColors.textMuted,
                // 12dp لا 11: أزرق واتساب أفتح من الأزرق السابق، فيحتاج
                // مساحة مصمتة أكبر قليلاً ليبقى واضحاً على الدائرة البيضاء.
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** رقاقة سرعة التشغيل 1×/1.5×/2× (تحلّ محلّ الصورة أثناء التشغيل). */
@Composable
private fun SpeedChip(speed: Float) {
    Box(
        Modifier
            .background(ChatColors.surfaceAlt, RoundedCornerShape(10.dp))
            .clickable {
                val steps = listOf(1f, 1.5f, 2f)
                val next = steps[(steps.indexOf(speed) + 1) % steps.size]
                SharedAudioPlayer.setSpeed(next)
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            "${if (speed % 1f == 0f) speed.toInt().toString() else speed}×",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.accentDark,
        )
    }
}

/**
 * موجة صوتيّة قابلة للسحب بنمط واتساب الحديث: أعمدة نحيفة بفجوات واضحة
 * وزوايا دائريّة، المسموع منها بلون بارز، والعمود الجاري أثناء التشغيل
 * ([playing]) أعلى قليلاً وبعتامة كاملة فيبدو كمؤشّر حيّ. ونقطة سحب عند
 * موضع التقدّم. مرآتيّة في RTL كي يسير التقدّم يميناً←يساراً.
 *
 * عند اكتمال التشغيل يعود [progress] إلى صفر (المشغّل يفرّغ مقطعه النشط)،
 * فترجع الموجة كاملةً بلون واحد لا نصف ملوّنة.
 */
@Composable
private fun WaveformSeekBar(
    bars: List<Int>,
    progress: Float,
    played: Color,
    rest: Color,
    enabled: Boolean,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    if (w > 0f) onSeek(seekFraction(offset.x, w, rtl))
                }
            }
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    val w = size.width.toFloat()
                    if (w > 0f) onSeek(seekFraction(change.position.x, w, rtl))
                }
            },
    ) {
        val count = bars.size.coerceAtLeast(1)
        // كثافة واتساب الحقيقيّة: فجوة ضيّقة وعمود بعرضها تقريباً — موجة
        // متّصلة بصريّاً لا أعمدة متناثرة. (لا عمود «متضخّم» عند المؤشّر:
        // واتساب يكتفي بنقطة السحب علامةً للموضع.)
        val gap = 1.8.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.dp.toPx())
        val minHeight = 3.dp.toPx()
        val playedBars = (progress * count).toInt()
        bars.forEachIndexed { i, value ->
            val ratio = value.coerceIn(0, 100) / 100f
            val h = (size.height * (0.14f + 0.86f * ratio)).coerceAtLeast(minHeight)
            val left = if (rtl) {
                size.width - (i + 1) * barWidth - i * gap
            } else {
                i * (barWidth + gap)
            }
            drawRoundRect(
                color = if (i < playedBars) played else rest,
                topLeft = Offset(left, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
        if (enabled) {
            val r = 5.5.dp.toPx()
            val x = if (rtl) size.width - progress * size.width else progress * size.width
            drawCircle(
                color = played,
                radius = r,
                center = Offset(x.coerceIn(r, (size.width - r).coerceAtLeast(r)), size.height / 2f),
            )
        }
    }
}

/** موضع النقر/السحب كنسبة 0..1 مع مراعاة اتجاه الواجهة. */
private fun seekFraction(x: Float, width: Float, rtl: Boolean): Float {
    val f = (x / width).coerceIn(0f, 1f)
    return if (rtl) 1f - f else f
}

private fun fmt(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun statusLine(
    status: MediaStatus,
    position: Long,
    total: Long,
    attachment: ChatAttachment,
): String = when {
    status.waitingForNetwork && status.state != MediaState.Downloaded ->
        "بانتظار الشبكة… ${status.progress.toInt()}% (سيُستأنف تلقائياً)"
    status.state == MediaState.Downloading -> "جارٍ التنزيل… ${status.progress.toInt()}%"
    status.state == MediaState.Failed -> status.error ?: "تعذّر التنزيل"
    !status.isReady -> if (attachment.size > 0) {
        "اضغط للتنزيل • ${formatBytes(attachment.size)}"
    } else {
        "اضغط للتنزيل"
    }

    // رقم واحد فقط: الزمن المنقضي أثناء التشغيل، والمدّة عند السكون.
    else -> fmt(if (position > 0L) position else total)
}

/**
 * غلاف تنزيل موحَّد للصور/الفيديو/الملفّات: يعرض المحتوى إن كان منزَّلاً،
 * وإلّا زرّ تنزيل دائريّ فوق معاينة رماديّة — تماماً كواتساب.
 */
@Composable
fun MediaDownloadOverlay(
    attachment: ChatAttachment,
    status: MediaStatus,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val downloading = status.state == MediaState.Downloading
    val failed = status.state == MediaState.Failed
    Column(
        Modifier
            .size(
                width = if (compact) 56.dp else 220.dp,
                height = if (compact) 56.dp else 150.dp,
            )
            .background(
                ChatColors.surfaceAlt,
                RoundedCornerShape(if (compact) 10.dp else 12.dp),
            )
            .border(
                1.dp,
                ChatColors.border,
                RoundedCornerShape(if (compact) 10.dp else 12.dp),
            )
            .clickable(enabled = !downloading) {
                scope.launch {
                    if (failed) {
                        ChatMediaStore.retry(attachment)
                    } else {
                        ChatMediaStore.download(attachment)
                    }
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (compact) 32.dp else 44.dp)
                .background(if (failed) ChatColors.rose else ChatColors.accentDark, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    progress = { (status.progress / 100).toFloat() },
                    strokeWidth = 2.4.dp,
                    color = Color.White,
                    modifier = Modifier.size(if (compact) 16.dp else 22.dp),
                )
            } else {
                Icon(
                    if (failed) Icons.Filled.Refresh else Icons.Filled.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 18.dp else 24.dp),
                )
            }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    downloading -> "${status.progress.toInt()}%"
                    failed -> status.error ?: "تعذّر التنزيل — أعد المحاولة"
                    attachment.size > 0 -> "اضغط للتنزيل • ${formatBytes(attachment.size)}"
                    else -> "اضغط للتنزيل"
                },
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = if (failed) ChatColors.rose else ChatColors.textMuted,
            )
        }
    }
}

/** عارض صورة بملء الشاشة من ملفّ محلّي. */
@Composable
fun ImageViewerDialog(file: File, name: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = file,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
                }
                Text(
                    name,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** صفحة تشغيل فيديو بملء الشاشة — من ملفّ محلّي (يعمل دون إنترنت). */
@Composable
fun VideoPlayerDialog(file: File, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        // إيقاف أيّ صوت جارٍ قبل تشغيل الفيديو.
        SharedAudioPlayer.stop()
        onDispose { player.release() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
                }
                Text(
                    name,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { context.openLocalFile(file) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "فتح بتطبيق آخر",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
