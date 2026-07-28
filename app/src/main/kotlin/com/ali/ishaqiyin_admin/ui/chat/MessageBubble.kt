package com.ali.ishaqiyin_admin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.ChatAttachment
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.ChatReplyRef
import com.ali.ishaqiyin_admin.data.MediaState
import com.ali.ishaqiyin_admin.data.chatTypeLabel
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.util.openLocalFile
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

/**
 * فقاعة رسالة بنمط واتساب: يمين للمرسِل، يسار للبقيّة، مع صورة واسم
 * المرسِل (وسام 👑 للمالك)، معاينة الردّ، المحتوى، التفاعلات، الوقت
 * وعلامات القراءة ✓✓.
 */
@Composable
fun MessageBubble(
    msg: ChatMessage,
    showSenderHeader: Boolean,
    members: Map<String, ChatMember>,
    readByAll: Boolean,
    onLongPress: (ChatMessage) -> Unit,
    onReplyTap: (ChatReplyRef) -> Unit,
    onReactionsTap: (ChatMessage) -> Unit,
    // يُستدعى مرّة واحدة عند أوّل تشغيل لرسالة صوتيّة ليست لي (شارة الاستماع).
    onListened: ((ChatMessage) -> Unit)? = null,
) {
    // رسائل النظام (إن وُجدت مستقبلاً) — بطاقة وسطيّة مميّزة.
    if (msg.senderId == "system") {
        SystemCard(msg, onLongPress)
        return
    }

    val sender = members[msg.senderId]
    val mine = msg.isMine
    val bubbleColor = if (mine) ChatColors.mineBubble else ChatColors.surface
    val shape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (mine) 14.dp else 3.dp,
        bottomEnd = if (mine) 3.dp else 14.dp,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        // مطابق للأصل: في RTL تُحاذى رسائلي إلى النهاية (يسار الشاشة)
        // ورسائل الآخرين إلى البداية (يمين الشاشة).
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!mine) {
                if (showSenderHeader) {
                    MemberAvatar(
                        uid = msg.senderId,
                        name = sender?.displayName ?: msg.senderName,
                        photo = sender?.displayPhoto ?: msg.senderPhoto,
                        radius = 15,
                    )
                } else {
                    Spacer(Modifier.width(30.dp))
                }
            }
            Column(
                Modifier
                    .widthIn(max = 280.dp)
                    .background(bubbleColor, shape)
                    .border(
                        1.dp,
                        if (mine) ChatColors.mineBubbleBorder else ChatColors.border,
                        shape,
                    )
                    .pointerInput(msg.id) {
                        detectTapGestures(onLongPress = { onLongPress(msg) })
                    }
                    .padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 6.dp),
            ) {
                if (!mine && showSenderHeader) SenderHeader(msg, sender)
                msg.replyTo?.let { ReplyPreview(it, onReplyTap) }
                // اقتباس «ردّ بشكل خاص» — كان يُخزَّن في الوثيقة ولا يُعرَض.
                msg.fromGroup?.let { GroupQuotePreview(it) }
                if (msg.deleted) {
                    DeletedBody(msg, members)
                } else {
                    BubbleBody(msg, sender, onListened)
                }
                Spacer(Modifier.height(2.dp))
                BubbleFooter(msg, readByAll)
            }
        }
        if (msg.reactions.isNotEmpty() && !msg.deleted) {
            ReactionsBar(msg, onReactionsTap)
        }
    }
}

@Composable
private fun SenderHeader(msg: ChatMessage, sender: ChatMember?) {
    val isOwner = sender?.isOwner == true
    val isMod = !isOwner && sender?.isChatModerator == true
    Row(
        Modifier.padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            sender?.displayName ?: msg.senderName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isOwner) ChatColors.amber else senderColor(msg.senderId),
        )
        if (isOwner) {
            Spacer(Modifier.width(4.dp))
            Text("👑", fontSize = 11.sp)
            Spacer(Modifier.width(2.dp))
            Text("المالك", fontSize = 10.sp, color = ChatColors.amber)
        } else if (isMod) {
            Spacer(Modifier.width(4.dp))
            Text("⭐", fontSize = 10.sp)
            Spacer(Modifier.width(2.dp))
            Text("مشرف", fontSize = 10.sp, color = ChatColors.accent)
        }
    }
}

@Composable
private fun ReplyPreview(ref: ChatReplyRef, onReplyTap: (ChatReplyRef) -> Unit) {
    Column(
        Modifier
            .padding(bottom = 6.dp)
            .background(Color.Black.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .clickable { onReplyTap(ref) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            ref.senderName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = senderColor(ref.senderId),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            ref.preview.ifEmpty { chatTypeLabel(ref.type) },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.5.sp,
            color = ChatColors.textMuted,
        )
    }
}

/**
 * اقتباس رسالة من المجموعة في محادثة خاصّة («ردّ بشكل خاص» — نمط واتساب).
 * غير قابل للنقر: الرسالة الأصليّة في مجموعة أخرى.
 */
@Composable
private fun GroupQuotePreview(ref: ChatReplyRef) {
    Column(
        Modifier
            .padding(bottom = 6.dp)
            .background(ChatColors.highlight, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Groups,
                contentDescription = null,
                tint = ChatColors.accentDark,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "من المجموعة — ${ref.senderName}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.accentDark,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            ref.preview.ifEmpty { chatTypeLabel(ref.type) },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.5.sp,
            color = ChatColors.textMuted,
        )
    }
}

@Composable
private fun DeletedBody(msg: ChatMessage, members: Map<String, ChatMember>) {
    val byOther = msg.deletedBy.isNotEmpty() && msg.deletedBy != msg.senderId
    val deleter = members[msg.deletedBy]
    val label = when {
        !byOther -> "تم حذف هذه الرسالة"
        deleter?.isOwner != false -> "حذفها المالك 👑"
        else -> "حذفها مشرف المجموعة ⭐"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Block,
            contentDescription = null,
            tint = ChatColors.textMuted,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = ChatColors.textMuted,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun BubbleBody(
    msg: ChatMessage,
    sender: ChatMember?,
    onListened: ((ChatMessage) -> Unit)?,
) {
    val att = msg.attachment
    val caption = msg.text.trim()
    Column {
        when (msg.type) {
            // الروابط والبُرد والأرقام قابلة للنقر (نمط واتساب).
            ChatMessageType.Text -> ChatLinkText(msg.text)

            ChatMessageType.Image -> if (att == null) MissingAttachment() else ImageBubble(att)

            ChatMessageType.Video -> if (att == null) MissingAttachment() else VideoBubble(att)

            // فقاعة الصوت تدير التنزيل بنفسها (زرّ تنزيل ← تشغيل محلّي).
            ChatMessageType.Audio, ChatMessageType.Voice ->
                if (att == null) {
                    MissingAttachment()
                } else {
                    val myUid = com.google.firebase.auth.FirebaseAuth
                        .getInstance().currentUser?.uid.orEmpty()
                    // رسالتي: تزرقّ الشارة إن استمع إليها أحد غيري.
                    // رسالة غيري: تزرقّ بعد استماعي أنا.
                    val listened = if (msg.isMine) {
                        msg.listenedBy.any { it != myUid }
                    } else {
                        myUid.isNotEmpty() && myUid in msg.listenedBy
                    }
                    AudioBubblePlayer(
                        attachment = att,
                        isVoice = msg.type == ChatMessageType.Voice,
                        messageId = msg.id,
                        senderUid = msg.senderId,
                        senderName = sender?.displayName ?: msg.senderName,
                        senderPhoto = sender?.displayPhoto ?: msg.senderPhoto,
                        mine = msg.isMine,
                        listened = listened,
                        onListened = onListened?.let { cb -> { cb(msg) } },
                    )
                }

            ChatMessageType.File -> if (att == null) MissingAttachment() else FileTile(att)
        }
        if (msg.type != ChatMessageType.Text && caption.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // تعليق المرفق يدعم الروابط أيضاً.
            ChatLinkText(caption, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
        }
    }
}

@Composable
private fun MissingAttachment() {
    Text("مرفق غير متاح", color = ChatColors.textMuted, fontSize = 12.sp)
}

@Composable
private fun ImageBubble(att: ChatAttachment) {
    val status by ChatMediaStore.statusOf(att).collectAsState()
    var viewing by remember { mutableStateOf<File?>(null) }
    viewing?.let { ImageViewerDialog(it, att.name) { viewing = null } }
    if (status.isReady) {
        AsyncImage(
            model = status.file,
            contentDescription = att.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 240.dp, height = 200.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { viewing = status.file },
        )
    } else {
        MediaDownloadOverlay(att, status)
    }
}

@Composable
private fun VideoBubble(att: ChatAttachment) {
    val status by ChatMediaStore.statusOf(att).collectAsState()
    var playing by remember { mutableStateOf<File?>(null) }
    playing?.let { VideoPlayerDialog(it, att.name) { playing = null } }
    if (status.isReady) {
        Box(
            Modifier
                .size(width = 240.dp, height = 140.dp)
                .background(Color.Black, RoundedCornerShape(10.dp))
                .border(1.dp, ChatColors.border, RoundedCornerShape(10.dp))
                .clickable { playing = status.file },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                att.name + if (att.size > 0) " • ${formatBytes(att.size)}" else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.5.sp,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    } else {
        MediaDownloadOverlay(att, status)
    }
}

/** بطاقة ملفّ: تنزيل ثم فتح بتطبيق النظام (يعمل دون إنترنت بعد التنزيل). */
@Composable
private fun FileTile(att: ChatAttachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ChatMediaStore.statusOf(att).collectAsState()
    val downloading = status.state == MediaState.Downloading
    Row(
        Modifier
            .width(244.dp)
            .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .clickable(enabled = !downloading) {
                scope.launch {
                    val f = if (status.isReady) status.file else ChatMediaStore.download(att)
                    if (f != null) context.openLocalFile(f, att.contentType)
                }
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (downloading) {
                CircularProgressIndicator(
                    progress = { (status.progress / 100).toFloat() },
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    if (status.isReady) {
                        Icons.AutoMirrored.Filled.InsertDriveFile
                    } else {
                        Icons.Filled.DownloadForOffline
                    },
                    contentDescription = null,
                    tint = if (status.isReady) ChatColors.amber else ChatColors.accentDark,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                att.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    downloading -> "جارٍ التنزيل… ${status.progress.toInt()}%"
                    status.state == MediaState.Failed -> status.error ?: "تعذّر التنزيل"
                    status.isReady -> "مُنزَّل • اضغط للفتح"
                    else -> "اضغط للتنزيل" +
                        if (att.size > 0) " • ${formatBytes(att.size)}" else ""
                },
                fontSize = 11.sp,
                color = if (status.state == MediaState.Failed) {
                    ChatColors.rose
                } else {
                    ChatColors.textMuted
                },
            )
        }
    }
}

/** شريط التفاعلات أسفل الفقاعة (مجمَّع بالإيموجي مع العدد). */
@Composable
private fun ReactionsBar(msg: ChatMessage, onReactionsTap: (ChatMessage) -> Unit) {
    val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val counts = msg.reactions.values.groupingBy { it }.eachCount()
    val mine = msg.reactions[myUid]
    Box(
        Modifier
            .padding(top = 2.dp)
            .background(ChatColors.surfaceAlt, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (mine != null) ChatColors.accentDark else ChatColors.border,
                RoundedCornerShape(12.dp),
            )
            .clickable { onReactionsTap(msg) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            counts.entries.joinToString("  ") { (emoji, count) ->
                if (count > 1) "$emoji $count" else emoji
            },
            fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun BubbleFooter(msg: ChatMessage, readByAll: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            timeFormat.format(Date(msg.createdAtMs)),
            fontSize = 10.sp,
            color = ChatColors.textMuted,
        )
        if (msg.isMine) {
            Spacer(Modifier.width(4.dp))
            if (msg.pending) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = ChatColors.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            } else {
                Icon(
                    if (readByAll) Icons.Filled.DoneAll else Icons.Filled.Done,
                    contentDescription = null,
                    // ✓✓ زرقاء: قرأها الجميع.
                    tint = if (readByAll) ChatColors.readBlue else ChatColors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SystemCard(msg: ChatMessage, onLongPress: (ChatMessage) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .background(ChatColors.highlight, RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    ChatColors.accentDark.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp),
                )
                .pointerInput(msg.id) {
                    detectTapGestures(onLongPress = { onLongPress(msg) })
                }
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = ChatColors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "منبر",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatColors.accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (msg.deleted) "تم حذف هذه الرسالة" else msg.text,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                timeFormat.format(Date(msg.createdAtMs)),
                fontSize = 9.5.sp,
                color = ChatColors.textMuted,
            )
        }
    }
}

/** شارة تاريخ بين الرسائل (اليوم/أمس/التاريخ). */
@Composable
fun DateChip(millis: Long) {
    val label = remember(millis) { dateChipLabel(millis) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .background(ChatColors.surfaceAlt, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(label, fontSize = 11.sp, color = ChatColors.textMuted)
        }
    }
}

private val dayFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US)

fun dateChipLabel(millis: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.clone() as java.util.Calendar
    today.set(java.util.Calendar.HOUR_OF_DAY, 0)
    today.set(java.util.Calendar.MINUTE, 0)
    today.set(java.util.Calendar.SECOND, 0)
    today.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = today.timeInMillis
    val startOfYesterday = startOfToday - 86_400_000L
    return when {
        millis >= startOfToday -> "اليوم"
        millis >= startOfYesterday -> "أمس"
        else -> dayFormat.format(Date(millis))
    }
}

fun sameDay(a: Long, b: Long): Boolean {
    val c1 = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val c2 = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR) &&
        c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR)
}
