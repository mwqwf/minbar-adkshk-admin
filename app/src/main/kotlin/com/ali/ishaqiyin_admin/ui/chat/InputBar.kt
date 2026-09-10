package com.ali.ishaqiyin_admin.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.automirrored.filled.ForwardToInbox
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.ChatReplyRef
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.chatTypeForMime
import com.ali.ishaqiyin_admin.data.chatTypeLabel
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.data.isVideoMime
import com.ali.ishaqiyin_admin.ui.ClipboardImageSuggestion
import com.ali.ishaqiyin_admin.ui.RecentScreenshotChip
import com.ali.ishaqiyin_admin.ui.CountBadge
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.RemoteImage
import com.ali.ishaqiyin_admin.ui.Routes
import com.ali.ishaqiyin_admin.ui.adminFieldColors
import com.ali.ishaqiyin_admin.ui.arabicCount
import com.ali.ishaqiyin_admin.util.PickedFile
import com.ali.ishaqiyin_admin.util.openExternalUri
import com.ali.ishaqiyin_admin.util.pickedFileFrom
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* شريط الإدخال — بقي بعد إلغاء دردشة الإدارة (2026-09-10) لأن صندوق «رسائل
   المستخدمين» يستعمله في ردود المالك. */

/**
 * شريط الإدخال بنمط واتساب: **كبسولة بيضاء واحدة بلا حدود** (إيموجي +
 * الحقل + المشبك والكاميرا) وخارجها زرّ دائريّ 48dp يتبدّل بين الميكروفون
 * والإرسال، وخلفيّة الصفّ شفّافة فوق الخلفيّة المزخرفة.
 * والميكروفون «اضغط-مع-الاستمرار»: سحب جانبيّ يلغي، وسحب للأعلى يقفل،
 * والإفلات يرسل.
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    hint: String = "اكتب رسالة…",
    onAttach: () -> Unit,
    onSend: () -> Unit,
    voice: VoiceRecorderState,
) {
    val hasText = text.trim().isNotEmpty()
    var showEmoji by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                color = ChatColors.surface,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 1.dp,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { showEmoji = true }) {
                        Icon(
                            Icons.Filled.EmojiEmotions,
                            contentDescription = "إيموجي",
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = { Text(hint) },
                        minLines = 1,
                        maxLines = 5,
                        // لا adminFieldColors هنا: حدود ظاهرة داخل الكبسولة.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            cursorColor = ChatColors.accentDark,
                        ),
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.weight(1f),
                    )
                    // الإرفاق متاح دائماً: كلّ رفع عمليّة مستقلّة في ChatUploader.
                    IconButton(onClick = onAttach) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "إرفاق",
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // الكاميرا تفتح قائمة الإرفاق نفسها (فيها «صورة»).
                    IconButton(onClick = onAttach) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "صورة",
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (hasText) {
                FloatingActionButton(
                    onClick = { if (!sending) onSend() },
                    containerColor = ChatColors.accentDark,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                VoiceMicButton(voice)
            }
        }
        // طبقة التسجيل تُرسم فوق الشريط بلا تغيير أبعاده (انظر تعليقها).
        if (voice.phase == VoicePhase.Holding) {
            VoiceHoldOverlay(voice, Modifier.matchParentSize())
            // كبسولة القفل **فوق** الشريط تماماً عند طرف الميكروفون —
            // بإزاحة سالبة فلا تزيد ارتفاع الشريط ولا تُربك الإيماءة.
            VoiceLockPill(
                voice,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp)
                    .offset(y = (-74).dp),
            )
        }
    }
}

/** حوار تأكيد الإرسال مع تعليق اختياري (مثل واتساب). */
