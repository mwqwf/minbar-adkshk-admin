package com.ali.ishaqiyin_admin.call

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.formatCallDuration
import com.ali.ishaqiyin_admin.ui.RemoteImage
import com.ali.ishaqiyin_admin.ui.kDanger
import com.ali.ishaqiyin_admin.ui.kTeal
import com.ali.ishaqiyin_admin.ui.kTealDark
import kotlinx.coroutines.delay

/** أخضر القبول (نمط واتساب). */
private val kAccept = Color(0xFF27AE60)

/**
 * 📞 شاشة المكالمة: خلفيّة داكنة متدرّجة من [kTealDark] إلى [kTeal]،
 * بواجهتين — **واردة** (صورة واسم كبيران + قبول/رفض) و**جارية** (عدّاد
 * المدّة + كتم/مكبّر صوت/إنهاء).
 */
@Composable
fun CallScreen(
    state: CallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onClose: () -> Unit,
) {
    // انتهت المكالمة وصُفِّرت الحالة ← تُغلق الشاشة وحدها.
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.Idle) onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(kTealDark, kTeal))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            CallAvatar(state.peerPhoto, state.peerName)
            Spacer(Modifier.height(22.dp))
            Text(
                state.peerName.ifEmpty { "مشرف" },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                statusLine(state),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    "مكالمة بين المشرفين",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.phase == CallPhase.Ringing && state.incoming) {
                IncomingControls(onAccept = onAccept, onDecline = onDecline)
            } else {
                OngoingControls(
                    state = state,
                    onHangUp = onHangUp,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * سطر الحالة: مدّة متزايدة أثناء الاتّصال، وإلّا ملاحظة المرحلة.
 * ⚠️ بلا خروج مبكّر قبل `remember`: عدد خانات التركيب يجب أن يبقى ثابتاً.
 */
@Composable
private fun statusLine(state: CallUiState): String {
    val counting = state.phase == CallPhase.Active && state.connectedAtMs > 0L
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(counting, state.connectedAtMs) {
        while (counting) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    if (counting) {
        val elapsed = ((now - state.connectedAtMs) / 1000L).coerceAtLeast(0L).toInt()
        return formatCallDuration(elapsed)
    }
    return state.note.ifEmpty {
        when (state.phase) {
            CallPhase.Dialing -> "جارٍ الاتصال…"
            CallPhase.Ringing -> "مكالمة صوتيّة واردة"
            CallPhase.Connecting -> "جارٍ التوصيل…"
            CallPhase.Ended -> "انتهت المكالمة"
            else -> ""
        }
    }
}

@Composable
private fun CallAvatar(photo: String, name: String) {
    Box(
        Modifier
            .size(148.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(2.dp, Color.White.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo.isNotEmpty()) {
            RemoteImage(photo, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Text(
                name.trim().firstOrNull()?.toString() ?: "؟",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun IncomingControls(onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallButton(
            icon = Icons.Filled.CallEnd,
            label = "رفض",
            background = kDanger,
            size = 72,
            onClick = onDecline,
        )
        CallButton(
            icon = Icons.Filled.Call,
            label = "قبول",
            background = kAccept,
            size = 72,
            onClick = onAccept,
        )
    }
}

@Composable
private fun OngoingControls(
    state: CallUiState,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val enabled = state.phase != CallPhase.Ended
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallButton(
            icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = if (state.muted) "مكتوم" else "كتم",
            background = if (state.muted) Color.White else Color.White.copy(alpha = 0.16f),
            tint = if (state.muted) kTealDark else Color.White,
            size = 58,
            enabled = enabled,
            onClick = onToggleMute,
        )
        CallButton(
            icon = Icons.Filled.CallEnd,
            label = "إنهاء",
            background = kDanger,
            size = 72,
            enabled = enabled,
            onClick = onHangUp,
        )
        CallButton(
            icon = if (state.speaker) {
                Icons.AutoMirrored.Filled.VolumeUp
            } else {
                Icons.AutoMirrored.Filled.VolumeDown
            },
            label = "مكبّر الصوت",
            background = if (state.speaker) Color.White else Color.White.copy(alpha = 0.16f),
            tint = if (state.speaker) kTealDark else Color.White,
            size = 58,
            enabled = enabled,
            onClick = onToggleSpeaker,
        )
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    background: Color,
    size: Int,
    onClick: () -> Unit,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(if (enabled) background else background.copy(alpha = 0.35f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size((size * 0.42f).dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}
