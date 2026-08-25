package com.ali.ishaqiyin_admin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DoDisturbOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.ChatAttachment
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.SupportKind
import com.ali.ishaqiyin_admin.data.SupportMessage
import com.ali.ishaqiyin_admin.data.SupportRepository
import com.ali.ishaqiyin_admin.data.SupportThread
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.ui.chat.ChatColors
import com.ali.ishaqiyin_admin.ui.chat.DateChip
import com.ali.ishaqiyin_admin.ui.chat.InputBar
import com.ali.ishaqiyin_admin.ui.chat.MessageBubble
import com.ali.ishaqiyin_admin.ui.chat.VoiceRecorderBar
import com.ali.ishaqiyin_admin.ui.chat.WhatsAppChatBackground
import com.ali.ishaqiyin_admin.ui.chat.rememberVoiceRecorderState
import com.ali.ishaqiyin_admin.ui.chat.sameDay
import com.ali.ishaqiyin_admin.util.copyUriToCache
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 💬 محادثة مستخدم واحد مع المالك — بنية دردشة الإدارة نفسها حرفاً بحرف:
 * خلفيّة واتساب، وفقاعات [MessageBubble] بمشغّل الصوت وعارض الصور فيها،
 * وشريط الإدخال [InputBar] بمسجّله الصوتيّ. لا نظير جديد لشيء موجود.
 *
 * ⛔ للمالك وحده: مرفقات الخيوط لا تُقرأ إلّا له ولصاحبها، فشاشة يفتحها
 * مشرف ستُظهر صوتاً لا يعمل وصوراً لا تظهر.
 */
@Composable
fun SupportThreadScreen(
    threadId: String,
    userUid: String,
    userName: String,
    kindKey: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val kind = SupportKind.of(kindKey)

    // ⚠️ مستمع الخيوط نفسه الذي تقرؤه القائمة — لا مستمع ثانٍ لوثيقة واحدة.
    val threads by rememberSupportFlow(emptyList<SupportThread>()) {
        SupportRepository.watchThreads()
    }
    val thread = threads.firstOrNull { it.id == threadId }
    val messages by rememberSupportFlow(emptyList<SupportMessage>()) {
        SupportRepository.watchMessages(threadId)
    }

    var sending by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }

    // فتح المحادثة = قراءتها: الشارة تسقط فور الدخول لا بعد الردّ.
    LaunchedEffect(threadId) { SupportRepository.markRead(threadId) }

    // وصف الجهاز: من الخيط إن كُتب فيه، وإلّا من أوّل رسالة — العقد يسمح
    // بالموضعين، والمشرف يحتاجه في بلاغ العطل قبل أن يسأل عنه.
    val deviceInfo = thread?.deviceInfo?.takeIf { it.isNotBlank() }
        ?: messages.firstOrNull { it.deviceInfo.isNotBlank() }?.deviceInfo.orEmpty()

    // ترجمة مسارات التخزين إلى روابط عرض **مرّة واحدة** لكلّ مسار (مخزون
    // يوم في المستودع): إنترنت المشرف ضعيف فلا يُطلب الرابط مع كلّ تركيب.
    val bubbles by produceState(emptyList<ChatMessage>(), messages, userName) {
        value = withContext(Dispatchers.Default) { messages.toBubbles(userUid, userName) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex)
    }

    /** إرسال مرفق: يُرفع أوّلاً فيصير له مسار، ثمّ تُنادى دالّة الردّ به. */
    fun sendMedia(file: File, contentType: String, deleteAfter: Boolean) {
        if (sending) return
        sending = true
        scope.launch {
            try {
                val path = SupportRepository.uploadReplyMedia(
                    userUid = userUid,
                    threadId = threadId,
                    file = file,
                    contentType = contentType,
                )
                if (contentType.startsWith("image/")) {
                    SupportRepository.reply(threadId, imagePaths = listOf(path))
                } else {
                    SupportRepository.reply(threadId, audioPath = path)
                }
            } catch (e: Exception) {
                snack("تعذّر الإرسال: ${e.arabicReason()}")
            }
            if (deleteAfter) runCatching { withContext(Dispatchers.IO) { file.delete() } }
            sending = false
        }
    }

    val voice = rememberVoiceRecorderState(
        // بادئة مستقلّة: لا تختلط ملفّات هذه الشاشة بمسجّل المجموعة أو الخاصّ.
        prefix = "support_voice",
        onNotice = { snack(it) },
        onSend = { file, _, _ ->
            sendMedia(file, "audio/mp4", deleteAfter = true)
        },
    )

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = runCatching { context.copyUriToCache(uri, "صورة.jpg") }.getOrNull()
            if (copied == null) {
                snack("تعذّرت قراءة الصورة.")
                return@launch
            }
            sendMedia(copied, "image/jpeg", deleteAfter = true)
        }
    }

    if (confirmClose) {
        ConfirmDialog(
            title = "إغلاق المحادثة",
            body = "ستُغلق المحادثة مع $userName ولن يستطيع الكتابة فيها. " +
                "تبقى الرسائل كما هي ولا يُحذف منها شيء.",
            confirmLabel = "إغلاق",
            onConfirm = {
                confirmClose = false
                scope.launch {
                    try {
                        SupportRepository.close(threadId)
                        snack("أُغلقت المحادثة.")
                    } catch (e: Exception) {
                        snack("تعذّر الإغلاق: ${e.arabicReason()}")
                    }
                }
            },
            onDismiss = { confirmClose = false },
        )
    }

    val blocked = thread?.blocked == true
    if (confirmBlock) {
        ConfirmDialog(
            title = if (blocked) "رفع الحظر" else "حظر المرسِل",
            body = if (blocked) {
                "سيعود $userName قادراً على مراسلتك."
            } else {
                "لن يستطيع $userName إرسال رسائل جديدة إليك."
            },
            confirmLabel = if (blocked) "رفع الحظر" else "حظر",
            confirmColor = if (blocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            onConfirm = {
                confirmBlock = false
                scope.launch {
                    try {
                        SupportRepository.blockUser(userUid, !blocked)
                        snack(if (blocked) "رُفع الحظر." else "حُظر المرسِل.")
                    } catch (e: Exception) {
                        snack("تعذّر التنفيذ: ${e.arabicReason()}")
                    }
                }
            },
            onDismiss = { confirmBlock = false },
        )
    }

    AdminScaffold(
        title = userName.ifBlank { "مستخدم" },
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { confirmClose = true },
                enabled = thread?.closed != true,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.DoDisturbOn, contentDescription = "إغلاق المحادثة")
            }
            IconButton(onClick = { confirmBlock = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Block,
                    contentDescription = if (blocked) "رفع الحظر" else "حظر المرسِل",
                )
            }
        },
    ) { padding ->
        WhatsAppChatBackground(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                KindHeader(kind, thread)
                if (deviceInfo.isNotBlank()) DeviceInfoBox(deviceInfo)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    supportBubbles(bubbles)
                }
                when {
                    thread?.closed == true -> ClosedNotice("المحادثة مغلقة — لا يمكن الردّ.")
                    blocked -> ClosedNotice("المرسِل محظور — ارفع الحظر لتعود المراسلة.")
                    voice.showsBar -> VoiceRecorderBar(voice)
                    else -> InputBar(
                        text = text,
                        onTextChange = { text = it },
                        sending = sending,
                        hint = "اكتب ردّك…",
                        onAttach = { picker.launch("image/*") },
                        onSend = {
                            val body = text.trim()
                            if (body.isEmpty() || sending) return@InputBar
                            sending = true
                            text = ""
                            scope.launch {
                                try {
                                    SupportRepository.reply(threadId, text = body)
                                } catch (e: Exception) {
                                    snack("تعذّر الإرسال: ${e.arabicReason()}")
                                }
                                sending = false
                            }
                        },
                        voice = voice,
                    )
                }
            }
        }
    }
}

/**
 * فاصل اليوم فوق أوّل رسالة منه — نفس [DateChip] المستعمل في دردشة الإدارة.
 */
private fun LazyListScope.supportBubbles(
    bubbles: List<ChatMessage>,
) {
    itemsIndexed(
        items = bubbles,
        key = { _, msg -> msg.id },
    ) { index, msg ->
        val previous = bubbles.getOrNull(index - 1)
        if (previous == null || !sameDay(previous.createdAtMs, msg.createdAtMs)) {
            DateChip(msg.createdAtMs)
        }
        MessageBubble(
            msg = msg,
            // رأس المرسِل على أوّل رسالة من كلّ متتالية (كما في المجموعة).
            showSenderHeader = previous == null || previous.senderId != msg.senderId,
            members = emptyMap(),
            readByAll = false,
            // لا تفاعلات ولا ردّ مقتبس في صندوق الرسائل: بساطةً مقصودة.
            onLongPress = {},
            onReplyTap = {},
            onReactionsTap = {},
        )
    }
}

/** شريط النوع وحالة الخيط أعلى المحادثة. */
@Composable
private fun KindHeader(kind: SupportKind, thread: SupportThread?) {
    val color = kindColor(kind)
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChatColors.surface)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            kindIcon(kind),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(kind.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.weight(1f))
        Text(
            supportTimeLabel(thread?.createdAtMs ?: 0L),
            fontSize = 11.sp,
            color = ChatColors.textMuted,
        )
    }
}

/**
 * 🐞 صندوق وصف الجهاز — يظهر في بلاغ العطل وحده (لا يُرسله غيره).
 * صغير ومقروء: المشرف يحتاجه ليفهم العطل، لا ليقرأه كنصّ طويل.
 */
@Composable
private fun DeviceInfoBox(info: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(ChatColors.surfaceAlt, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = ChatColors.textMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                "جهاز المرسِل",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.textMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                info,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = ChatColors.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** بديل شريط الإدخال حين لا يصحّ الردّ (مغلقة أو محظور). */
@Composable
private fun ClosedNotice(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(ChatColors.surface)
            .heightIn(min = 56.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = ChatColors.textMuted)
    }
}

/**
 * تحويل رسائل الصندوق إلى فقاعات الدردشة — فبنية العرض واحدة لا اثنتان.
 *
 * رسالةٌ بصورٍ عدّة تُفكّ إلى فقاعة لكلّ صورة (الفقاعة تحمل مرفقاً واحداً)،
 * ونصّها يبقى على الأولى وحدها فلا يتكرّر.
 */
private suspend fun List<SupportMessage>.toBubbles(
    userUid: String,
    userName: String,
): List<ChatMessage> {
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val out = mutableListOf<ChatMessage>()
    forEach { m ->
        val senderId = if (m.fromOwner) myUid else userUid
        val senderName = if (m.fromOwner) "أنت" else userName.ifBlank { "مستخدم" }

        fun base(
            id: String,
            type: ChatMessageType,
            text: String,
            attachment: ChatAttachment?,
        ) = ChatMessage(
            id = id,
            senderId = senderId,
            senderName = senderName,
            senderPhoto = "",
            type = type,
            text = text,
            attachment = attachment,
            replyTo = null,
            fromGroup = null,
            createdAtMs = m.createdAtMs,
            sentAtMs = m.createdAtMs,
            deleted = false,
            deletedBy = "",
            hiddenFor = emptyList(),
            reactions = emptyMap(),
            pending = false,
        )

        if (m.audioPath.isNotEmpty()) {
            val url = runCatching { SupportRepository.mediaUrl(m.audioPath) }.getOrDefault("")
            if (url.isNotEmpty()) {
                out += base(
                    id = "${m.id}_a",
                    type = ChatMessageType.Voice,
                    text = "",
                    attachment = ChatAttachment(
                        url = url,
                        path = m.audioPath,
                        name = "رسالة صوتيّة",
                        size = 0L,
                        contentType = "audio/mp4",
                    ),
                )
            }
        }
        m.imagePaths.forEachIndexed { index, path ->
            val url = runCatching { SupportRepository.mediaUrl(path) }.getOrDefault("")
            if (url.isEmpty()) return@forEachIndexed
            out += base(
                id = "${m.id}_i$index",
                type = ChatMessageType.Image,
                // التعليق على الصورة الأولى وحدها كي لا يتكرّر النصّ.
                text = if (index == 0 && m.audioPath.isEmpty()) m.text else "",
                attachment = ChatAttachment(
                    url = url,
                    path = path,
                    name = "صورة",
                    size = 0L,
                    contentType = "image/jpeg",
                ),
            )
        }
        // النصّ المجرّد (أو النصّ المرافق لصوت) يبقى فقاعةً مستقلّة.
        val textUsed = m.imagePaths.isNotEmpty() && m.audioPath.isEmpty()
        if (m.text.isNotBlank() && !textUsed) {
            out += base(
                id = "${m.id}_t",
                type = ChatMessageType.Text,
                text = m.text,
                attachment = null,
            )
        }
    }
    return out.sortedBy { it.createdAtMs }
}
