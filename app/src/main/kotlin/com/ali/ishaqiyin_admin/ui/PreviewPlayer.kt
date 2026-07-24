package com.ali.ishaqiyin_admin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * مشغّل معاينة بسيط (نظير just_audio في شاشتَي المساهمات والدروس المشبوهة):
 * مقطع واحد في كلّ لحظة، ويتوقّف عند الخروج من الشاشة.
 */
class PreviewPlayerState internal constructor(private val player: ExoPlayer) {
    var playingId by mutableStateOf("")
        private set
    var playing by mutableStateOf(false)
        internal set

    fun toggle(id: String, url: String) {
        if (url.isEmpty()) return
        if (playingId == id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        playingId = id
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
        playingId = ""
        playing = false
    }

    internal fun attach(): Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                playingId = ""
                playing = false
            }
        }
    }
}

@Composable
fun rememberPreviewPlayer(): PreviewPlayerState {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val state = remember { PreviewPlayerState(player) }
    DisposableEffect(Unit) {
        val listener = state.attach()
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    return state
}
