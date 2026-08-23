package com.ali.ishaqiyin_admin.ui.chat

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** بادئة مفاتيح معاينة التسجيل الجاري (ملفّ مؤقّت لا يُستأنف). */
const val PREVIEW_KEY_PREFIX = "preview:"

/**
 * خطأ تشغيل مقروناً **بمفتاح المقطع الذي فشل** — لا بالمقطع النشط: المشغّل
 * يفرغ مفتاحه النشط فور الفشل، فربط الرسالة بالمفتاح النشط كان يجعلها
 * غير قابلة للظهور أصلاً.
 */
data class PlaybackError(val key: String, val message: String)

/**
 * مشغّل صوت مشترك — مشغّل واحد فقط يعمل في أيّ لحظة (مثل واتساب).
 * يشغّل **ملفّاً محليّاً** دائماً (بعد التنزيل) فلا يتأثّر بالشبكة إطلاقاً.
 */
object SharedAudioPlayer {
    private const val TAG = "SharedAudioPlayer"
    private const val MAX_SAVED_POSITIONS = 50
    private var player: ExoPlayer? = null

    /**
     * نطاق المشغّل نفسه — تتابع «نزّل ثمّ شغّل» لا يموت بموت الفقاعة، ويبقى
     * على الخيط الرئيسيّ لأنّ ExoPlayer يُقاد من خيط إنشائه.
     */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    /**
     * آخر خطأ تشغيل مع مفتاح صاحبه — تعرضه فقاعته بدل الصمت المُربك، ويبقى
     * ظاهراً بعد [stop] لأنّه لا يعتمد على [activeKey] إطلاقاً.
     */
    private val _error = MutableStateFlow<PlaybackError?>(null)
    val error: StateFlow<PlaybackError?> = _error

    fun clearError() {
        _error.value = null
    }

    /**
     * نافذة التشغيل التلقائي: تُفتح لحظة انتهاء مقطع ويستمرّ فيها التنزيل
     * المؤجَّل للمقطع التالي. أيّ [playFile] يصل داخلها بينما اختار المشرف
     * مقطعاً آخر (أو أوقف عمداً) يُهمَل — فلا يُخطف ما يسمعه بعد انتظار طويل.
     */
    private const val AUTO_WINDOW_MS = 20_000L

    @Volatile
    private var autoWindowUntil = 0L

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
                    // ⚠️ المفتاح يُلتقط **قبل** stop(): هي تفرّغ _activeKey،
                    // وربط الرسالة بالمفتاح النشط كان يجعل شرط عرضها
                    // (activeKey == key) خاطئاً دائماً — ففشل التشغيل كان
                    // صامتاً تماماً بلا أيّ رسالة رغم وجود نصوصها.
                    val failedKey = _activeKey.value
                    Log.w(TAG, "playback failed: ${error.errorCodeName}", error)
                    val message = when (error.errorCode) {
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
                    // بعد stop() كي لا تمحوه، ومقروناً بمفتاح الرسالة الفاشلة
                    // فتظهر على فقاعتها هي وحدها.
                    if (failedKey != null) _error.value = PlaybackError(failedKey, message)
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
                            // نافذة يُحرَس فيها التشغيل التلقائي المؤجَّل.
                            autoWindowUntil = SystemClock.elapsedRealtime() + AUTO_WINDOW_MS
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
        // ⚠️ حارس التشغيل التلقائي المؤجَّل — داخل المشغّل لا في الشاشات، كي
        // يغطّي مستدعيَيه (شاشة المجموعة والخاصّ) بمنطق واحد: إن وصل الطلب
        // بعد تنزيل طويل داخل نافذة الانتقال التلقائي، وكان المشرف قد بدأ
        // مقطعاً آخر أو أوقفه عمداً أثناء الانتظار، يُهمَل الطلب.
        val current = _activeKey.value
        if (SystemClock.elapsedRealtime() < autoWindowUntil && current != null && current != key) {
            Log.d(TAG, "auto-play ignored: user moved to $current")
            autoWindowUntil = 0L
            return
        }
        val p = ensure(context)
        _error.value = null
        if (current == key) {
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

    /**
     * تحرير كامل عند مغادرة آخر شاشة دردشة: [stop] وحدها تُبقي مثيل ExoPlayer
     * وخيط تشغيله حيّين طوال عمر العمليّة. الترتيب مقصود — [stop] أوّلاً كي
     * يُحفظ موضع المقطع المغادَر قبل التحرير، و[ensure] تعيد الإنشاء لاحقاً.
     */
    fun release() {
        stop()
        runCatching { player?.release() }
        player = null
        host = null
    }

    /**
     * الشاشة المضيفة الحاليّة للمشغّل — رمز هويّة لا أكثر.
     *
     * ⛔ سببه: `onDispose { release() }` كان غير مشروط، والانتقال «ردّ بشكل
     * خاص» يُركّب الوجهة الجديدة **قبل** إتلاف السابقة، فتحرّر الشاشةُ
     * المغادِرة مشغّلاً بدأته الشاشة القادمة ⇒ ينقطع الصوت.
     */
    @Volatile
    var host: Any? = null
        private set

    /** تسجيل الشاشة الحاليّة مضيفةً (يُنادى عند دخول شاشة الدردشة). */
    fun claimHost(owner: Any) {
        host = owner
    }

    /** تحرير مشروط بالهويّة — نظير شرط [onCompleted] تماماً. */
    fun releaseIfHost(owner: Any) {
        if (host === owner) release()
    }

    /**
     * «نزّل ثمّ شغّل» في نطاق المشغّل لا نطاق الفقاعة: خروج الفقاعة من الشاشة
     * أثناء التنزيل كان يلغي الكوروتين فيكتمل الملفّ ولا يبدأ التشغيل أبداً.
     */
    fun downloadThenPlay(context: Context, att: ChatAttachment): Job = ownScope.launch {
        ChatMediaStore.download(att)?.let { playFile(context, ChatMediaStore.keyOf(att), it) }
    }

    /** نظيرها بعد فشل سابق (زرّ إعادة المحاولة). */
    fun retryThenPlay(context: Context, att: ChatAttachment): Job = ownScope.launch {
        ChatMediaStore.retry(att)?.let { playFile(context, ChatMediaStore.keyOf(att), it) }
    }

    /** تحديث الموضع دوريّاً أثناء التشغيل (يستدعيه شريط الفقاعة). */
    fun syncPosition() {
        val p = player ?: return
        _positionMs.value = p.currentPosition.coerceAtLeast(0L)
        if (p.duration > 0) _durationMs.value = p.duration
    }
}

/**
 * ارتفاع «سطر الصوت» الواحد: الصورة وزرّ التشغيل والموجة كلّها موسَّطة
 * داخل شريط بهذا الارتفاع، فتستقيم مراكزها على خطّ أفقيّ واحد كما في
 * واتساب. صفّ الوقت والمدّة يأتي **تحت** هذا الشريط لا داخله.
 */
private val AUDIO_LINE = 48.dp

/** كثافة قريبة من موجة واتساب على عرض الفقاعة، مع دعم الرسائل القديمة. */
private const val WAVE_BARS = 48
private const val DISPLAY_WAVE_BARS = 48

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
    return raw.toList()
}

/** يوحّد كثافة الموجات القديمة والجديدة من دون تغيير ترتيب قممها. */
private fun resampleWaveform(source: List<Int>, targetSize: Int): List<Int> {
    if (source.isEmpty()) return List(targetSize) { 30 }
    if (source.size == targetSize) return source.map { it.coerceIn(0, 100) }
    if (source.size == 1) return List(targetSize) { source.first().coerceIn(0, 100) }
    return List(targetSize) { index ->
        val position = index.toFloat() * (source.lastIndex.toFloat() / (targetSize - 1))
        val left = position.toInt().coerceIn(0, source.lastIndex)
        val right = (left + 1).coerceAtMost(source.lastIndex)
        val fraction = position - left
        (source[left] + (source[right] - source[left]) * fraction).toInt().coerceIn(0, 100)
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
    messageTime: String = "",
    pending: Boolean = false,
    readByAll: Boolean = false,
    onListened: (() -> Unit)? = null,
) {
    val context = LocalContext.current
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

    // شارة الاستماع تُكتب مرّة واحدة عند **تشغيل فعليّ**، لا عند ضغطة الزرّ:
    // الضغطة قد لا تُنتج صوتاً (ملفّ تالف أو ترميز غير مدعوم) فتُكتب
    // listenedBy زوراً، وقواعد Firestore تمنع إزالتها فلا تراجع عنها.
    // مقيَّدة بالرسائل الصوتيّة: الشارة لا تُرسم أصلاً لمرفقات الصوت العامّة،
    // فكتابتها لها ضربٌ في Firestore بلا أيّ أثر مرئيّ.
    var reported by remember(messageId) { mutableStateOf(false) }
    fun reportListened() {
        if (isVoice && !mine && !listened && !reported) {
            reported = true
            onListened?.invoke()
        }
    }

    // حدث التشغيل الفعليّ من المشغّل (أو أوّل تقدّم في الموضع) هو ما يسجّل.
    LaunchedEffect(active, playing, positionMs > 0L) {
        if (active && (playing || positionMs > 0L)) reportListened()
    }

    val displayBars = remember(bars) { resampleWaveform(bars, DISPLAY_WAVE_BARS) }
    // الخطأ مقرون بمفتاح رسالته، فيظهر على فقاعتها هي وحدها ولا يمحوه stop().
    val errorText = playbackError?.takeIf { it.key == key }?.message
    val showError = errorText != null

    // انتظار التنزيل الجاري — يلغيه زرّ ✕ داخل حلقة التقدّم فيتوقّف التشغيل
    // المؤجَّل بعده. (النقل نفسه يديره ChatMediaStore بنطاقه الخاصّ، ولا واجهة
    // لإلغائه اليوم — يبقى الملفّ الجزئي محفوظاً ويُستأنف لاحقاً كما هو.)
    var downloadJob by remember(key) { mutableStateOf<Job?>(null) }

    val controlClick: () -> Unit = {
        SharedAudioPlayer.clearError()
        when {
            status.state == MediaState.Downloading -> Unit
            // ⚠️ تتابع «نزّل ثمّ شغّل» في نطاق المشغّل لا نطاق الفقاعة: خروجها
            // من الشاشة أثناء التنزيل كان يلغي الكوروتين، فيكتمل الملفّ ولا
            // يبدأ التشغيل أبداً. و`downloadJob` يبقى ليُلغيه زرّ ✕.
            status.state == MediaState.Failed -> {
                downloadJob = SharedAudioPlayer.retryThenPlay(context, attachment)
            }
            !status.isReady -> {
                downloadJob = SharedAudioPlayer.downloadThenPlay(context, attachment)
            }
            active && playing -> SharedAudioPlayer.pause()
            else -> {
                status.file?.let { file -> SharedAudioPlayer.playFile(context, key, file) }
            }
        }
    }

    // ⛔ الترتيب الصحيح (المواصفة بند ١) في RTL من اليمين إلى اليسار:
    //     [▶ تشغيل] [ــ الموجة ــ] [🎤 + نقطة] [صورة المرسِل]
    // أي أنّ زرّ التشغيل على الطرف **المقابل** للصورة لا ملاصقاً لها.
    //
    // ⚠️ الصفّ مفروض عليه Ltr (لأجل اتجاه الموجة وحساب السحب)، فمعنى
    // «البداية/النهاية» ينقلب: أوّل ابن يظهر على **يسار** الشاشة وآخرُه على
    // يمينها. لذلك التسلسل هنا: صورة (يسار) ← موجة ← تشغيل (يمين) — وهو
    // بالضبط ترتيب المواصفة بعد القلب. والصادرة مثلها (بلا صورة مرسِل
    // منطقيّاً، لكنّها تحمل كبسولة السرعة وشارة «سمعها المتلقّي» في الموضع
    // نفسه فيتطابق تخطيط الاتجاهين).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            // عرض مرن لا 292dp ثابتة: كانت الفقاعة تملأ شاشة 320dp حتى
            // تلامس حوافّها.
            Modifier.fillMaxWidth(),
            // ⚠️ **Top لا CenterVertically**: الموجة تسكن عموداً يحمل تحتها
            // صفّ الوقت والمدّة، فتوسيط ذلك العمود كان يرفع الموجة نحو
            // ثمانية بكسلات فوق مركز زرّ التشغيل — يبدو الشريط مائلاً إلى
            // الأعلى وينكسر الخطّ المستقيم الذي في واتساب. الآن تُحاذى
            // الأبناء الثلاثة من الأعلى، ويُوسَّط كلّ منها داخل شريط واحد
            // بارتفاع [AUDIO_LINE]، فتقع مراكزها على خطّ واحد تماماً.
            verticalAlignment = Alignment.Top,
        ) {
            AudioIdentity(
                active = active,
                speed = speed,
                isVoice = isVoice,
                senderUid = senderUid,
                senderName = senderName,
                senderPhoto = senderPhoto,
                listened = listened,
                mine = mine,
            )
            Spacer(Modifier.width(6.dp))

            AudioWaveformTrack(
                bars = displayBars,
                progress = progress,
                // واتساب يقبل النقر والسحب على مقطع منزَّل لم يُشغَّل بعد
                // ويبدأ منه؛ الشرط القديم (active) كان يجمّد الموجة.
                enabled = status.isReady && total > 0,
                messageTime = messageTime,
                pending = pending,
                readByAll = readByAll,
                mine = mine,
                statusText = errorText ?: statusLine(status, position, total, attachment),
                statusIsError = showError,
                onSeek = { fraction ->
                    val target = (fraction * total).toLong()
                    if (!active) {
                        status.file?.let { SharedAudioPlayer.playFile(context, key, it) }
                    }
                    SharedAudioPlayer.seekTo(target)
                    reportListened()
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(2.dp))
            AudioControl(
                status = status,
                active = active,
                playing = playing,
                onClick = controlClick,
                onCancel = {
                    downloadJob?.cancel()
                    downloadJob = null
                },
            )
        }
    }
}

@Composable
private fun AudioIdentity(
    active: Boolean,
    speed: Float,
    isVoice: Boolean,
    senderUid: String,
    senderName: String,
    senderPhoto: String,
    listened: Boolean,
    mine: Boolean,
) {
    Box(Modifier.size(AUDIO_LINE), contentAlignment = Alignment.Center) {
        when {
            active -> SpeedChip(speed, mine)
            isVoice -> VoiceAvatar(senderUid, senderName, senderPhoto, listened, mine)
            else -> Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = ChatColors.controlIcon,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
private fun AudioWaveformTrack(
    bars: List<Int>,
    progress: Float,
    enabled: Boolean,
    messageTime: String,
    pending: Boolean,
    readByAll: Boolean,
    mine: Boolean,
    statusText: String,
    statusIsError: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // الموجة موسَّطة داخل شريط بارتفاع زرّ التشغيل نفسه: بهذا يقع
        // مركزها على مركزَي الزرّ والصورة تماماً (خطّ مستقيم كواتساب).
        Box(
            Modifier.fillMaxWidth().height(AUDIO_LINE),
            contentAlignment = Alignment.Center,
        ) {
            WaveformSeekBar(
                bars = bars,
                progress = progress,
                enabled = enabled,
                onSeek = onSeek,
            )
        }
        // صفّ الميتا كما في المواصفة: **المدّة على حافة البداية (يمين RTL)**
        // ووقت الرسالة ثمّ ✓✓ على حافة النهاية (يسار). والصفّ مفروض Ltr، فأوّل
        // ابن هو اليسار: الوقت والعلامة أوّلاً ثمّ المدّة موزونة إلى اليمين.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(messageTime, fontSize = 12.sp, color = ChatColors.metaText)
            if (mine) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    when {
                        pending -> Icons.Filled.Schedule
                        readByAll -> Icons.Filled.DoneAll
                        else -> Icons.Filled.Done
                    },
                    contentDescription = null,
                    tint = if (readByAll && !pending) {
                        ChatColors.readBlue
                    } else {
                        ChatColors.metaText
                    },
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // نصوص الحالة عربيّة («جارٍ التنزيل… 40%»، «اضغط للتنزيل • 2.3MB»)
            // والصفّ كلّه مفروض LTR، فتُعاد إليها RTL وحدها كي تستقرّ النقطة
            // والنسبة في موضعهما الصحيح، وتُحاذى Start = يمين الصفّ. (الوزن
            // يُحسب في نطاق الصفّ نفسه: محتوى CompositionLocalProvider ليس
            // RowScope.)
            val statusModifier = Modifier.weight(1f)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    text = statusText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    fontSize = 12.sp,
                    color = if (statusIsError) ChatColors.rose else ChatColors.metaText,
                    modifier = statusModifier,
                )
            }
        }
    }
}

/**
 * زرّ التشغيل بنمط واتساب: أيقونة 24dp بلا خلفيّة داخل مساحة لمس 40dp،
 * وأثناء التنزيل حلقة تقدّم بداخلها ✕ للإلغاء لا حلقة صامتة.
 */
@Composable
private fun AudioControl(
    status: MediaStatus,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.size(AUDIO_LINE),
        contentAlignment = Alignment.Center,
    ) {
        if (status.state == MediaState.Downloading) {
            // ⚠️ هدف اللمس كان 24dp داخل صفّ 48dp: النقر يُوسَّع إلى كامل
            // الـ48dp والحلقة تبقى 24dp بصريّاً (لا تغيّر في المظهر).
            Box(
                Modifier.size(AUDIO_LINE).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { (status.progress / 100).toFloat() },
                    strokeWidth = 2.dp,
                    color = ChatColors.controlIcon,
                    trackColor = ChatColors.waveRest,
                    modifier = Modifier.size(24.dp),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "إلغاء التنزيل",
                    tint = ChatColors.controlIcon,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else {
            // نفس العلّة: 40dp ⇒ 48dp للّمس، والأيقونة تبقى 24dp.
            Box(
                Modifier.size(AUDIO_LINE).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        !status.isReady -> Icons.Filled.Download
                        active && playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = ChatColors.controlIcon,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * صورة المرسل الدائريّة مع ميكروفون بمعنى واتساب الدقيق — والمعنى **مختلف**
 * بين الاتجاهين، فلا تصحّ قاعدة واحدة لهما:
 * - رسالتي: أزرق إن سمعها المتلقّي، ورماديّ قبل ذلك.
 * - رسالة غيري: **أخضر** ما لم أسمعها بعد (مؤشّر «غير مسموعة»، وهو غرض
 *   الميزة كلّه)، ورماديّ بعد سماعي.
 */
@Composable
private fun VoiceAvatar(
    uid: String,
    name: String,
    photo: String,
    listened: Boolean,
    mine: Boolean,
) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        MemberAvatar(uid = uid, name = name, photo = photo, radius = 22)
        // الشارة ملاصقة للصورة من **جهة الموجة** دائماً (الصفّ مفروض Ltr،
        // فجهة الموجة هي BottomEnd في الاتجاهين) — لا BottomStart للصادرة.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(19.dp)
                .background(
                    if (mine) ChatColors.mineBubble else ChatColors.otherBubble,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = when {
                    mine && listened -> ChatColors.readBlue
                    mine -> ChatColors.micIdle
                    !listened -> ChatColors.micUnheard
                    else -> ChatColors.micIdle
                },
                modifier = Modifier.size(15.dp),
            )
        }
        // نقطة «غير مسموعة» الخضراء المصمتة (المواصفة بند ٢): ترافق
        // الميكروفون الأخضر ما دامت الرسالة الواردة لم تُسمع، وتختفي بعده.
        if (!mine && !listened) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .background(ChatColors.micUnheard, CircleShape),
            )
        }
    }
}

/**
 * رقاقة سرعة التشغيل 1x/1.5x/2x (تحلّ محلّ الصورة أثناء التشغيل): كبسولة
 * رماديّة محايدة فوق الوارد وخضراء باهتة فوق الصادر — لا تركوازيّ السمة.
 * منطق الدورة وحفظ السرعة في AppPrefs كما هما (مطابقان لواتساب).
 */
@Composable
private fun SpeedChip(speed: Float, mine: Boolean) {
    Box(
        Modifier
            .background(
                if (mine) ChatColors.speedChipMine else ChatColors.speedChipOther,
                RoundedCornerShape(10.dp),
            )
            .clickable {
                val steps = listOf(1f, 1.5f, 2f)
                val next = steps[(steps.indexOf(speed) + 1) % steps.size]
                SharedAudioPlayer.setSpeed(next)
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            "${if (speed % 1f == 0f) speed.toInt().toString() else speed}x",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.wavePlayed,
        )
    }
}

/**
 * موجة صوتيّة قابلة للسحب بنمط واتساب: أعمدة **ثابتة العرض 2dp** بفجوة 2dp
 * وزوايا دائريّة — عددها يُحسب من العرض المتاح لا مثبّتاً على 48 عموداً
 * (فكان العمود يتضخّم على الشاشات العريضة حتى تبدو الموجة أعمدة عريضة
 * متباعدة). لونان رماديّان محايدان ونقطة سحب مصمتة بلونهما: الأزرق محجوز
 * لعلامات القراءة وميكروفون «سمعها المتلقّي» فلا يُربك معناه هنا.
 *
 * عند اكتمال التشغيل يعود [progress] إلى صفر (المشغّل يفرّغ مقطعه النشط)،
 * فترجع الموجة كاملةً بلون واحد لا نصف ملوّنة.
 */
@Composable
private fun WaveformSeekBar(
    bars: List<Int>,
    progress: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
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
        val barWidth = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val step = barWidth + gap
        val count = ((size.width + gap) / step).toInt().coerceIn(1, 128)
        val minHeight = 3.dp.toPx()
        val playedBars = (progress * count).toInt()
        val lastSource = bars.lastIndex.coerceAtLeast(0)
        for (i in 0 until count) {
            // عيّنة الموجة تُختار بالتناسب مهما اختلف عدد الأعمدة عن عدد العيّنات.
            val source = if (count == 1) {
                0
            } else {
                (i.toFloat() * lastSource / (count - 1)).roundToInt().coerceIn(0, lastSource)
            }
            val ratio = bars.getOrElse(source) { 30 }.coerceIn(0, 100) / 100f
            val h = (size.height * (0.14f + 0.86f * ratio)).coerceAtLeast(minHeight)
            val left = if (rtl) size.width - (i + 1) * step + gap else i * step
            drawRoundRect(
                color = if (i < playedBars) ChatColors.wavePlayed else ChatColors.waveRest,
                topLeft = Offset(left, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
        // تبقى نقطة البداية ظاهرة قبل التشغيل؛ enabled تتحكم بالسحب فقط.
        val r = 2.5.dp.toPx()
        val x = if (rtl) size.width - progress * size.width else progress * size.width
        drawCircle(
            color = ChatColors.wavePlayed,
            radius = r,
            center = Offset(x.coerceIn(r, (size.width - r).coerceAtLeast(r)), size.height / 2f),
        )
    }
}

/** موضع النقر/السحب كنسبة 0..1 مع مراعاة اتجاه الواجهة. */
private fun seekFraction(x: Float, width: Float, rtl: Boolean): Float {
    val f = (x / width).coerceIn(0f, 1f)
    return if (rtl) 1f - f else f
}

private fun fmt(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(java.util.Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
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
    !status.isReady && total > 0L -> fmt(total)
    !status.isReady && attachment.size > 0 -> {
        "اضغط للتنزيل • ${formatBytes(attachment.size)}"
    }
    !status.isReady -> "اضغط للتنزيل"

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
