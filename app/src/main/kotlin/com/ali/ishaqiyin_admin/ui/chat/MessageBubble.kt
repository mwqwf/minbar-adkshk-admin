package com.ali.ishaqiyin_admin.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
 * عرض ذيل الفقاعة البارز خارجها. المساحة **محجوزة دائماً** على الجهة
 * الحاملة للذيل (بذيل أو بلاه) كي تتطابق حواف رسائل السلسلة الواحدة، ولئلّا
 * يُقصّ الذيل عند رسمه.
 */
private val BUBBLE_TAIL = 8.dp

/**
 * مسافات غير قابلة للكسر تُلحَق بآخر سطر نصّ ليحجز مساحة الطابع (الوقت
 * و✓✓) داخل الفقاعة على السطر نفسه — نمط واتساب تماماً. ≈62dp للوارد
 * (وقت فقط) و≈78dp للصادر (وقت + علامة). إن ضاق السطر نزل الطابع سطراً
 * وحده بمحاذاة النهاية تلقائيّاً.
 */
private val NBSP = Char(160).toString()

private fun stampPadFor(mine: Boolean): String =
    NBSP.repeat(if (mine) 21 else 16)

/**
 * شكل فقاعة واتساب: نصف قطر 7.5dp على كلّ الزوايا، وذيل مثلّث بارز **خارج**
 * جسم الفقاعة عند الزاوية **العليا** الخلفيّة (topEnd للصادر، topStart
 * للوارد) — يُرسم على أوّل رسالة من السلسلة فقط [withTail].
 */
@Composable
private fun whatsAppBubbleShape(mine: Boolean, withTail: Boolean): Shape {
    val density = LocalDensity.current
    return remember(mine, withTail, density) {
        val radius = with(density) { 7.5.dp.toPx() }
        val tail = with(density) { BUBBLE_TAIL.toPx() }
        GenericShape { size, layoutDirection ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            // حافة النهاية لرسائلي وحافة البداية لرسائل غيري — بالبكسل.
            val tailOnLeft = if (rtl) mine else !mine
            val left = if (tailOnLeft) tail else 0f
            val right = if (tailOnLeft) size.width else size.width - tail
            addRoundRect(
                RoundRect(
                    Rect(left, 0f, right, size.height),
                    CornerRadius(radius, radius),
                ),
            )
            if (withTail) {
                if (tailOnLeft) {
                    moveTo(left + radius, 0f)
                    lineTo(0f, 0f)
                    lineTo(left, tail)
                } else {
                    moveTo(right - radius, 0f)
                    lineTo(size.width, 0f)
                    lineTo(right, tail)
                }
                close()
            }
        }
    }
}

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
    val hasInlineVoiceAvatar = !msg.deleted && msg.type == ChatMessageType.Voice
    val isAudioBubble = !msg.deleted &&
        (msg.type == ChatMessageType.Audio || msg.type == ChatMessageType.Voice)
    val hasQuote = msg.replyTo != null || msg.fromGroup != null
    // صورة/فيديو بلا تعليق ولا اقتباس: الوسيط من الحافة إلى الحافة والطابع
    // فوقه داخل تدرّج (نمط واتساب).
    val mediaEdgeToEdge = !msg.deleted &&
        msg.attachment != null &&
        !hasQuote &&
        msg.text.isBlank() &&
        (msg.type == ChatMessageType.Image || msg.type == ChatMessageType.Video)
    val hasReactions = msg.reactions.isNotEmpty() && !msg.deleted
    val bubbleColor = if (mine) ChatColors.mineBubble else ChatColors.surface
    val shape = whatsAppBubbleShape(mine = mine, withTail = showSenderHeader)

    // عرض أقصى نسبيّ: الثابت 308dp كان يكسر شاشات 320dp.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = (screenWidth * 0.85f)
        .coerceAtMost(if (isAudioBubble) 308.dp else 280.dp)
    val sidePad = when {
        isAudioBubble -> 8.dp
        mediaEdgeToEdge -> 3.dp
        else -> 10.dp
    }
    val topPad = when {
        isAudioBubble -> 4.dp
        mediaEdgeToEdge -> 3.dp
        else -> 8.dp
    }
    // حشو سفليّ أوسع عند وجود تفاعلات: الكبسولة متراكبة على حافة الفقاعة.
    val bottomPad = when {
        hasReactions -> 12.dp
        isAudioBubble -> 4.dp
        mediaEdgeToEdge -> 3.dp
        else -> 6.dp
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 8.dp,
                // تباعد السلسلة: 2dp بين رسائل المرسِل نفسه و8dp عند تغيّره.
                top = if (showSenderHeader) 8.dp else 2.dp,
                bottom = 2.dp,
            ),
        // مطابق للأصل: في RTL تُحاذى رسائلي إلى النهاية (يسار الشاشة)
        // ورسائل الآخرين إلى البداية (يمين الشاشة).
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!mine && !hasInlineVoiceAvatar) {
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
            // حاوية الفقاعة وشريط التفاعلات المتراكب عليها.
            Box {
                Column(
                    Modifier
                        .widthIn(max = maxBubbleWidth)
                        .shadow(1.dp, shape)
                        .background(bubbleColor, shape)
                        .pointerInput(msg.id) {
                            detectTapGestures(onLongPress = { onLongPress(msg) })
                        }
                        .padding(
                            // مساحة الذيل محجوزة على جهته دائماً.
                            start = sidePad + (if (mine) 0.dp else BUBBLE_TAIL),
                            top = topPad,
                            end = sidePad + (if (mine) BUBBLE_TAIL else 0.dp),
                            bottom = bottomPad,
                        ),
                ) {
                    if (!mine && showSenderHeader) {
                        SenderHeader(msg, sender, Modifier.padding(bottom = 3.dp))
                    }
                    msg.replyTo?.let { ref ->
                        QuoteBlock(ref = ref, mine = mine, onTap = { onReplyTap(ref) })
                    }
                    // اقتباس «ردّ بشكل خاص» — كان يُخزَّن في الوثيقة ولا يُعرَض.
                    msg.fromGroup?.let { ref ->
                        QuoteBlock(ref = ref, mine = mine, fromGroup = true)
                    }
                    // الطابع داخل الفقاعة عند الزاوية السفليّة الخلفيّة على
                    // سطر آخر كلمة — إلّا في الصوت (يرسم طابعه بنفسه) وفي
                    // الوسيط بلا تعليق (يُرسم فوق الصورة).
                    Box {
                        if (msg.deleted) {
                            DeletedBody(msg, members)
                        } else {
                            BubbleBody(
                                msg = msg,
                                sender = sender,
                                readByAll = readByAll,
                                onListened = onListened,
                                mediaEdgeToEdge = mediaEdgeToEdge,
                                // فقاعة الصوت ترسم طابعها بنفسها: لا تحجز له.
                                stampPad = if (isAudioBubble) "" else stampPadFor(mine),
                            )
                        }
                        if (!isAudioBubble && !mediaEdgeToEdge) {
                            BubbleStamp(
                                msg = msg,
                                readByAll = readByAll,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                }
                if (hasReactions) {
                    ReactionsBar(
                        msg = msg,
                        bubbleColor = bubbleColor,
                        onReactionsTap = onReactionsTap,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(y = (-10).dp),
                    )
                }
            }
        }
    }
}

/**
 * الوقت و✓✓ — 11sp بلون [ChatColors.metaText]، والعلامة 16dp×11dp بعد
 * 4dp من الوقت (نمط واتساب). [onDark] عند الرسم فوق صورة داخل تدرّج داكن.
 */
@Composable
private fun BubbleStamp(
    msg: ChatMessage,
    readByAll: Boolean,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val tint = if (onDark) Color.White.copy(alpha = 0.92f) else ChatColors.metaText
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            timeFormat.format(Date(msg.createdAtMs)),
            fontSize = 11.sp,
            color = tint,
        )
        if (msg.isMine) {
            Spacer(Modifier.width(4.dp))
            if (msg.pending) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(width = 16.dp, height = 11.dp),
                )
            } else {
                Icon(
                    if (readByAll) Icons.Filled.DoneAll else Icons.Filled.Done,
                    contentDescription = null,
                    // ✓✓ زرقاء: قرأها الجميع.
                    tint = if (readByAll) ChatColors.readBlue else tint,
                    modifier = Modifier.size(width = 16.dp, height = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SenderHeader(msg: ChatMessage, sender: ChatMember?, modifier: Modifier = Modifier) {
    val isOwner = sender?.isOwner == true
    val isMod = !isOwner && sender?.isChatModerator == true
    Row(
        modifier,
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

/** أيقونة نوع الاقتباس (13dp قبل نصّ المعاينة، ونفسها داخل المصغّرة). */
private fun quoteTypeIcon(type: ChatMessageType): ImageVector? = when (type) {
    ChatMessageType.Text -> null
    ChatMessageType.Image -> Icons.Filled.Image
    ChatMessageType.Video -> Icons.Filled.Videocam
    ChatMessageType.Audio -> Icons.Filled.MusicNote
    ChatMessageType.Voice -> Icons.Filled.Mic
    ChatMessageType.File -> Icons.AutoMirrored.Filled.InsertDriveFile
    ChatMessageType.Call -> Icons.Filled.Call
}

/**
 * اقتباس الردّ داخل الفقاعة (نمط واتساب): شريط عموديّ مصمت 4dp على حافة
 * البداية بلون اسم المرسِل (ولون [ChatColors.quoteBarMine] لاقتباس رسالتي)،
 * خلفيّة حسب صاحب الفقاعة، نصف قطر 6dp، والمعاينة **سطر واحد** مع أيقونة
 * النوع، ومصغّرة 48dp على حافة النهاية عند وجود مرفق.
 *
 * [fromGroup] = اقتباس رسالة مجموعة داخل محادثة خاصّة («ردّ بشكل خاص»):
 * غير قابل للنقر لأنّ الرسالة الأصليّة في محادثة أخرى.
 */
@Composable
private fun QuoteBlock(
    ref: ChatReplyRef,
    mine: Boolean,
    fromGroup: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val barColor = if (mine) ChatColors.quoteBarMine else senderColor(ref.senderId)
    val nameColor = if (fromGroup) ChatColors.accentDark else barColor
    val typeIcon = quoteTypeIcon(ref.type)
    val base = Modifier
        .padding(bottom = 4.dp)
        .fillMaxWidth()
        // IntrinsicSize.Min كي يمتدّ الشريط الجانبيّ بكامل ارتفاع الاقتباس.
        .height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(6.dp))
        .background(if (mine) ChatColors.quoteBgMine else ChatColors.quoteBgOther)
    Row(if (onTap != null) base.clickable(onClick = onTap) else base) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(barColor))
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fromGroup) {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        tint = nameColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (fromGroup) "من المجموعة — ${ref.senderName}" else ref.senderName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                )
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (typeIcon != null) {
                    Icon(
                        typeIcon,
                        contentDescription = null,
                        tint = ChatColors.metaText,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    ref.preview.ifEmpty { chatTypeLabel(ref.type) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.8.sp,
                    color = ChatColors.metaText,
                )
            }
        }
        if (typeIcon != null && ref.type != ChatMessageType.Call) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    typeIcon,
                    contentDescription = null,
                    tint = ChatColors.metaText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DeletedBody(msg: ChatMessage, members: Map<String, ChatMember>) {
    val byOther = msg.deletedBy.isNotEmpty() && msg.deletedBy != msg.senderId
    val deleter = members[msg.deletedBy]
    // ⚠️ `deleter?.isOwner == true` لا `!= false`: الحاذف الغائب عن خريطة
    // الأعضاء (مشرف مُزال، أو أوّل إطار قبل وصول التدفّق) كان يُنسب حذفه
    // للمالك 👑 زوراً — فله الآن فرع محايد.
    val label = when {
        !byOther -> "تم حذف هذه الرسالة"
        deleter?.isOwner == true -> "حذفها المالك 👑"
        deleter == null -> "حذفها مشرف"
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
    readByAll: Boolean,
    onListened: ((ChatMessage) -> Unit)?,
    mediaEdgeToEdge: Boolean = false,
    stampPad: String = "",
) {
    val att = msg.attachment
    val caption = msg.text.trim()
    // وسيط بلا تعليق: الطابع يُرسم فوق الصورة داخل تدرّج بدل أسفلها. الوسيط
    // غير المنزَّل يعرضه بلون داكن فوق بطاقة التنزيل الفاتحة (Boolean = فوق داكن).
    val overlayStamp: (@Composable (Boolean) -> Unit)? = if (mediaEdgeToEdge) {
        { onDark -> BubbleStamp(msg = msg, readByAll = readByAll, onDark = onDark) }
    } else {
        null
    }
    Column {
        when (msg.type) {
            // الروابط والبُرد والأرقام قابلة للنقر (نمط واتساب).
            ChatMessageType.Text -> ChatLinkText(msg.text + stampPad)

            ChatMessageType.Image ->
                if (att == null) {
                    MissingAttachment(stampPad)
                } else {
                    ImageBubble(att, overlayStamp)
                }

            ChatMessageType.Video ->
                if (att == null) {
                    MissingAttachment(stampPad)
                } else {
                    VideoBubble(att, overlayStamp)
                }

            // فقاعة الصوت تدير التنزيل بنفسها (زرّ تنزيل ← تشغيل محلّي).
            ChatMessageType.Audio, ChatMessageType.Voice ->
                if (att == null) {
                    MissingAttachment(stampPad)
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
                        messageTime = timeFormat.format(Date(msg.createdAtMs)),
                        pending = msg.pending,
                        readByAll = readByAll,
                        onListened = onListened?.let { cb -> { cb(msg) } },
                    )
                }

            // 📞 سجلّ مكالمة صوتيّة (نمط واتساب): الفائتة/المرفوضة بالوردي.
            ChatMessageType.Call -> CallLogBody(msg.text, stampPad)

            ChatMessageType.File ->
                if (att == null) {
                    MissingAttachment(stampPad)
                } else {
                    FileTile(att)
                    // بطاقة الملفّ عريضة: يُترك للطابع شريط سفليّ خاصّ به
                    // بدل أن يتراكب على اسم الملفّ وحجمه.
                    if (caption.isEmpty()) Spacer(Modifier.height(12.dp))
                }
        }
        // سجلّ المكالمة نصّه هو المحتوى نفسه — لا يُكرَّر كتعليق مرفق.
        if (
            msg.type != ChatMessageType.Text &&
            msg.type != ChatMessageType.Call &&
            caption.isNotEmpty()
        ) {
            Spacer(Modifier.height(6.dp))
            // تعليق المرفق يدعم الروابط أيضاً (ويحجز مساحة الطابع في آخره).
            ChatLinkText(
                caption + stampPad,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            )
        }
    }
}

@Composable
private fun MissingAttachment(stampPad: String = "") {
    Text("مرفق غير متاح$stampPad", color = ChatColors.textMuted, fontSize = 12.sp)
}

/**
 * 📞 فقاعة سجلّ مكالمة: أيقونة هاتف + نصّ الرسالة كما كتبه المحرّك
 * («مكالمة صوتيّة — 4:32» / «فائتة» / «مرفوضة»)، والفائتة أو المرفوضة
 * بلون [ChatColors.rose] (نمط واتساب).
 */
@Composable
private fun CallLogBody(text: String, stampPad: String = "") {
    val unanswered = text.contains("فائتة") || text.contains("مرفوضة")
    val tint = if (unanswered) ChatColors.rose else ChatColors.accentDark
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (unanswered) Icons.AutoMirrored.Filled.CallMissed else Icons.Filled.Call,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text.ifBlank { "مكالمة صوتيّة" } + stampPad,
            color = tint,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * تدرّج شفّاف→أسود بارتفاع 32dp أسفل الوسيط يحمل الوقت و✓✓ بالأبيض
 * (نمط واتساب). يُرسم بـ`matchParentSize` فلا يزيد حجم الفقاعة.
 */
@Composable
private fun BoxScope.MediaStampScrim(stamp: @Composable () -> Unit) {
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(32.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                    ),
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) { stamp() }
        }
    }
}

/** بطاقة تنزيل الوسيط مع الطابع أسفلها بلون فاتح (قبل التنزيل). */
@Composable
private fun PendingMedia(
    att: ChatAttachment,
    status: com.ali.ishaqiyin_admin.data.MediaStatus,
    stamp: (@Composable (Boolean) -> Unit)?,
) {
    if (stamp == null) {
        MediaDownloadOverlay(att, status)
        return
    }
    Box(contentAlignment = Alignment.BottomEnd) {
        MediaDownloadOverlay(att, status)
        Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { stamp(false) }
    }
}

@Composable
private fun ImageBubble(att: ChatAttachment, stamp: (@Composable (Boolean) -> Unit)? = null) {
    val status by ChatMediaStore.statusOf(att).collectAsState()
    var viewing by remember { mutableStateOf<File?>(null) }
    viewing?.let { ImageViewerDialog(it, att.name) { viewing = null } }
    if (status.isReady) {
        Box(Modifier.clip(RoundedCornerShape(6.dp))) {
            AsyncImage(
                model = status.file,
                contentDescription = att.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 240.dp, height = 200.dp)
                    .clickable { viewing = status.file },
            )
            if (stamp != null) MediaStampScrim { stamp(true) }
        }
    } else {
        PendingMedia(att, status, stamp)
    }
}

@Composable
private fun VideoBubble(att: ChatAttachment, stamp: (@Composable (Boolean) -> Unit)? = null) {
    val status by ChatMediaStore.statusOf(att).collectAsState()
    var playing by remember { mutableStateOf<File?>(null) }
    playing?.let { VideoPlayerDialog(it, att.name) { playing = null } }
    if (status.isReady) {
        Box(
            Modifier
                .size(width = 240.dp, height = 140.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black, RoundedCornerShape(6.dp))
                .border(1.dp, ChatColors.border, RoundedCornerShape(6.dp))
                .clickable { playing = status.file },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp),
            )
            if (stamp != null) MediaStampScrim { stamp(true) }
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
        PendingMedia(att, status, stamp)
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

/**
 * كبسولة التفاعلات **المتراكبة** على الحافّة السفليّة للفقاعة (نمط واتساب):
 * بيضاء مصمتة بنصف قطر كامل وحدّ بلون الفقاعة وظلّ خفيف، تدخل بمقياس
 * 0.6→1. الحدّ يبقى بلون التمييز إن كان أحد التفاعلات تفاعلي أنا.
 */
@Composable
private fun ReactionsBar(
    msg: ChatMessage,
    bubbleColor: Color,
    onReactionsTap: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val counts = msg.reactions.values.groupingBy { it }.eachCount()
    val mine = msg.reactions[myUid]
    var appeared by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id) { appeared = true }
    val scale by animateFloatAsState(
        if (appeared) 1f else 0.6f,
        label = "reactionScale",
    )
    Row(
        modifier
            .scale(scale)
            .shadow(1.dp, CircleShape)
            .background(ChatColors.surface, CircleShape)
            .border(1.dp, if (mine != null) ChatColors.accentDark else bubbleColor, CircleShape)
            .clickable { onReactionsTap(msg) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        counts.forEach { (emoji, count) ->
            Text(emoji, fontSize = 13.sp)
            if (count > 1) {
                Spacer(Modifier.width(2.dp))
                Text("$count", fontSize = 12.sp, color = ChatColors.metaText)
            }
            Spacer(Modifier.width(3.dp))
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
