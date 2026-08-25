package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** قفزة الترجيع/التقديم السريع في المعاينة (بالملّي ثانية). */
private const val SKIP_MS = 30_000L

/** السرعات المتاحة للمراجعة السريعة — درس أربعين دقيقة يُسمع في عشرين. */
private val PREVIEW_SPEEDS = listOf(1f, 1.5f, 2f)

/**
 * مشغّل معاينة (نظير just_audio في شاشات المساهمات والدروس المشبوهة):
 * مقطع واحد في كلّ لحظة، ويتوقّف عند الخروج من الشاشة. ومعه موضع التشغيل
 * ومدّته وسرعته كي يقفز المشرف داخل الدرس بدل سماعه كاملاً أو الحكم بلا سماع.
 */
class PreviewPlayerState internal constructor(private val player: ExoPlayer) {
    var playingId by mutableStateOf("")
        private set
    var playing by mutableStateOf(false)
        internal set

    /** موضع التشغيل الحالي بالملّي ثانية (يُحدَّث دوريّاً أثناء التشغيل). */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** مدّة المقطع الجاري — صفر ما دامت غير معروفة بعد. */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** سرعة التشغيل الحالية (تبقى كما اختارها المشرف بين المقاطع). */
    var speed by mutableFloatStateOf(1f)
        private set

    fun toggle(id: String, url: String) {
        if (url.isEmpty()) return
        if (playingId == id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        playingId = id
        positionMs = 0L
        durationMs = 0L
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.play()
    }

    fun stop() {
        player.stop()
        playingId = ""
        playing = false
        positionMs = 0L
        durationMs = 0L
    }

    /** قفز إلى موضع محدّد (يُقصّ داخل حدود المقطع). */
    fun seekTo(targetMs: Long) {
        if (playingId.isEmpty()) return
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val safe = targetMs.coerceIn(0L, max)
        player.seekTo(safe)
        positionMs = safe
    }

    /** ترجيع/تقديم بمقدار ثابت (±٣٠ ثانية). */
    fun skip(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /**
     * 🎯 قفز إلى **نسبة** من المقطع (0 = البداية، 0.5 = الوسط…).
     *
     * ⚠️ الغرض توفير بيانات المشرف: المشغّل يبثّ ولا ينزّل الدرس كاملاً،
     * و`seekTo` على مصدر متدرّج يطلب من الخادم البايتات **من الموضع الجديد
     * وحده** (طلب مدى)، فلا يُعاد التحميل من الصفر. لذلك يُقاس الحكم على
     * ثلاث نقاط بثوانٍ معدودة بدل تنزيل درسٍ كامل ليُرفض بعد دقيقة.
     *
     * لا يعمل قبل معرفة المدّة — والزرّ نفسه معطَّل حتى تصل.
     */
    fun seekFraction(fraction: Float) {
        val total = durationMs
        if (total <= 0L) return
        seekTo((total * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** تدوير السرعة بين 1× و1.5× و2×. */
    fun cycleSpeed() {
        val index = PREVIEW_SPEEDS.indexOf(speed).takeIf { it >= 0 } ?: 0
        applySpeed(PREVIEW_SPEEDS[(index + 1) % PREVIEW_SPEEDS.size])
    }

    // الاسم `applySpeed` لا `setSpeed`: خاصيّة `speed` تولّد بنفسها
    // `setSpeed(F)V` على الـJVM، فدالّة بالاسم نفسه تصطدم بها.
    fun applySpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
    }

    /** قراءة الموضع والمدّة من المشغّل (يُستدعى من المؤقّت الدوري). */
    internal fun refresh() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        val d = player.duration
        durationMs = if (d > 0) d else 0L
    }

    internal fun attach(): Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) refresh()
            if (state == Player.STATE_ENDED) {
                playingId = ""
                playing = false
                positionMs = 0L
                durationMs = 0L
            }
        }
    }
}

@Composable
fun rememberPreviewPlayer(): PreviewPlayerState {
    val context = LocalContext.current
    val player = remember {
        // نفس سمات مشغّل الدردشة: بلا طلب البؤرة الصوتيّة تكتم بعض واجهات
        // المصنّعين الصوتَ إن كان تطبيق آخر يحتفظ بها.
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    val state = remember { PreviewPlayerState(player) }
    DisposableEffect(Unit) {
        val listener = state.attach()
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // 🔋 مؤقّت خفيف يعمل ما دام التشغيل **فعليّاً** جارياً: كانت الحلقة
    // تستمرّ كلّ 400 م.ث والمقطع موقوف مؤقّتاً، فتستنزف البطاريّة بلا فائدة
    // (الموضع لا يتغيّر أصلاً وهو متوقّف). تحديثةٌ واحدة عند التوقّف تكفي.
    LaunchedEffect(state.playingId, state.playing) {
        if (state.playingId.isEmpty()) return@LaunchedEffect
        state.refresh()
        while (state.playing) {
            delay(400)
            state.refresh()
        }
    }
    return state
}

/** «د:ثث» — أو «س:دد:ثث» للدروس التي تتجاوز الساعة. */
private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
    }
}

/** «1×» / «1.5×» / «2×» بلا أصفار زائدة. */
private fun formatSpeed(value: Float): String {
    val text = if (value % 1f == 0f) value.toInt().toString() else value.toString()
    return "$text×"
}

/**
 * شريط تحكّم المعاينة: تقدّم قابل للسحب + قفز ±٣٠ث + سرعة 1.5×/2×.
 * يظهر تحت بطاقة المقطع الجاري وحده ([id] = معرّف البطاقة نفسه الممرَّر
 * إلى [PreviewPlayerState.toggle])، فيخدم شاشات المراجعة كلّها بمكوّن واحد.
 */
@Composable
fun PreviewPlayerBar(
    state: PreviewPlayerState,
    id: String,
    modifier: Modifier = Modifier,
) {
    if (state.playingId != id) return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs
    val maxValue = if (duration > 0) duration.toFloat() else 1f
    val shown = if (dragging) {
        dragValue
    } else {
        val pos = state.positionMs
        (if (duration > 0 && pos > duration) duration else pos).toFloat()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Slider(
            value = shown.coerceIn(0f, maxValue),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                state.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..maxValue,
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (duration > 0) {
                    "${formatClock(shown.toLong())} / ${formatClock(duration)}"
                } else {
                    "${formatClock(shown.toLong())} — جارٍ تحميل المدّة…"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { state.skip(-SKIP_MS) },
                // هدف لمس 48dp (كان 34dp) — الأيقونة تبقى 20dp.
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Replay30,
                    contentDescription = "ترجيع 30 ثانية",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { state.skip(SKIP_MS) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Forward30,
                    contentDescription = "تقديم 30 ثانية",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(2.dp))
            val fast = state.speed != 1f
            Text(
                formatSpeed(state.speed),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (fast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        if (fast) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { state.cycleSpeed() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        // 🎯 ثلاث نقاط تكفي للحكم: أوّل الدرس (صحّة الصوت والمقدّمة)، ووسطه
        // (المتن)، وقبل آخره (ألصق الخاتمة). أقلّ ما يُحمَّل وأسرع ما يُقرَّر.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JumpChip("البداية", duration > 0) { state.seekFraction(0f) }
            Spacer(Modifier.size(6.dp))
            JumpChip("الوسط", duration > 0) { state.seekFraction(0.5f) }
            Spacer(Modifier.size(6.dp))
            // 0.9 لا 1.0: النهاية التامّة تُنهي المقطع بلا صوت يُسمع.
            JumpChip("قبل النهاية", duration > 0) { state.seekFraction(0.9f) }
        }
    }
}

/** كبسولة قفز واحدة — هدف لمسها 48dp كبقيّة أزرار الشريط. */
@Composable
private fun JumpChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (enabled) scheme.primary else scheme.onSurfaceVariant
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .background(
                if (enabled) tint.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}
