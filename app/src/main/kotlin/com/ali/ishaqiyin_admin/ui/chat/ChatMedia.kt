package com.ali.ishaqiyin_admin.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
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

/**
 * مشغّل صوت مشترك — مشغّل واحد فقط يعمل في أيّ لحظة (مثل واتساب).
 * يشغّل **ملفّاً محليّاً** دائماً (بعد التنزيل) فلا يتأثّر بالشبكة إطلاقاً.
 */
object SharedAudioPlayer {
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
     * يُستدعى بمفتاح المقطع فور انتهائه — تستعمله شاشة الدردشة لتشغيل
     * الرسالة الصوتيّة التالية تلقائياً (نمط واتساب).
     */
    @Volatile
    var onCompleted: ((String) -> Unit)? = null

    private fun ensure(context: Context): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context.applicationContext).build()
        created.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _durationMs.value = created.duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) {
                        val finished = _activeKey.value
                        stop()
                        if (finished != null) onCompleted?.invoke(finished)
                    }
                }
            },
        )
        player = created
        return created
    }

    fun playFile(context: Context, key: String, file: File) {
        val p = ensure(context)
        if (_activeKey.value == key) {
            p.play()
            return
        }
        _activeKey.value = key
        _positionMs.value = 0
        _durationMs.value = 0
        p.stop()
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        p.setPlaybackSpeed(_speed.value)
        p.prepare()
        p.play()
    }

    fun setSpeed(v: Float) {
        _speed.value = v
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

/**
 * فقاعة صوت بنمط واتساب: زرّ تنزيل أوّلاً، ثم تشغيل من الملفّ المحلّي مع
 * شريط تقدّم وسرعة تشغيل — ويعمل دون إنترنت بعد التنزيل.
 */
@Composable
fun AudioBubblePlayer(attachment: ChatAttachment, isVoice: Boolean) {
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

    LaunchedEffect(active, playing) {
        while (active && playing) {
            SharedAudioPlayer.syncPosition()
            delay(300)
        }
    }

    val fallbackDuration = attachment.durationMs ?: 0L
    val total = if (active && durationMs > 0) durationMs else fallbackDuration
    val position = if (active) positionMs else 0L

    Row(
        Modifier.width(236.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(ChatColors.accentDark, CircleShape)
                .clickable {
                    scope.launch {
                        when {
                            status.state == MediaState.Downloading -> Unit
                            status.state == MediaState.Failed -> {
                                val f = ChatMediaStore.retry(attachment)
                                if (f != null) SharedAudioPlayer.playFile(context, key, f)
                            }
                            !status.isReady -> {
                                val f = ChatMediaStore.download(attachment)
                                if (f != null) SharedAudioPlayer.playFile(context, key, f)
                            }

                            active && playing -> SharedAudioPlayer.pause()
                            else -> status.file?.let {
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
                    color = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    when {
                        !status.isReady -> Icons.Filled.Download
                        active && playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Slider(
                value = if (total > 0) position.toFloat().coerceIn(0f, total.toFloat()) else 0f,
                onValueChange = { if (active) SharedAudioPlayer.seekTo(it.toLong()) },
                valueRange = 0f..(if (total > 0) total.toFloat() else 1f),
                enabled = active,
                colors = SliderDefaults.colors(
                    activeTrackColor = ChatColors.accent,
                    inactiveTrackColor = ChatColors.border,
                    thumbColor = ChatColors.accent,
                ),
                modifier = Modifier.height(24.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusLine(status, position, total, attachment),
                    fontSize = 10.5.sp,
                    color = ChatColors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                if (active) {
                    Box(
                        Modifier
                            .background(ChatColors.surfaceAlt, RoundedCornerShape(8.dp))
                            .clickable {
                                val steps = listOf(1f, 1.5f, 2f)
                                val next = steps[(steps.indexOf(speed) + 1) % steps.size]
                                SharedAudioPlayer.setSpeed(next)
                            }
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "${if (speed % 1f == 0f) speed.toInt().toString() else speed}×",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ChatColors.accentDark,
                        )
                    }
                }
            }
        }
        Icon(
            if (isVoice) Icons.Filled.Mic else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = ChatColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
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

    total > 0 -> "${fmt(position)} / ${fmt(total)}"
    else -> fmt(position)
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
        ExoPlayer.Builder(context).build().apply {
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
