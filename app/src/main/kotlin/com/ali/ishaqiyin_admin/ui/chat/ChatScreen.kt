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
import androidx.compose.material.icons.filled.Videocam
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
import com.ali.ishaqiyin_admin.data.ChatGroupMeta
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatReplyRef
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.ChatUploadTarget
import com.ali.ishaqiyin_admin.data.ChatUploader
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.ONLINE_WINDOW_MS
import com.ali.ishaqiyin_admin.data.QUICK_REACTIONS
import com.ali.ishaqiyin_admin.data.TYPING_WINDOW_MS
import com.ali.ishaqiyin_admin.data.chatTypeForMime
import com.ali.ishaqiyin_admin.data.chatTypeFromString
import com.ali.ishaqiyin_admin.data.chatTypeLabel
import com.ali.ishaqiyin_admin.data.chatTypeToString
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.ui.ClipboardImageSuggestion
import com.ali.ishaqiyin_admin.ui.CountBadge
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.RemoteImage
import com.ali.ishaqiyin_admin.ui.Routes
import com.ali.ishaqiyin_admin.ui.adminFieldColors
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

private val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)

private val emojiPalette = listOf(
    "👍", "👎", "❤️", "💚", "💙", "🤍", "💔", "❣️",
    "😂", "🤣", "😊", "😍", "🥰", "😘", "😉", "😎",
    "😮", "😱", "🤯", "😳", "🥺", "😢", "😭", "😤",
    "😡", "🤬", "😴", "🤔", "🤨", "🙄", "😬", "🤐",
    "🙏", "👏", "🤝", "💪", "✌️", "🤞", "👌", "🫡",
    "🔥", "⭐", "🌟", "✨", "⚡", "💯", "✅", "❌",
    "🎉", "🎊", "🏆", "🥇", "🎯", "📌", "📚", "📖",
    "☝️", "🖐️", "🫶", "🌹", "🌸", "☀️", "🌙", "☕",
)

/**
 * حافظ مرجع الاقتباس (ردّ / ردّ خاصّ) عبر تدوير الشاشة — يسلسل حقول
 * [ChatReplyRef] كقائمة نصوص. القيمة null تُحفظ كقائمة فارغة فيعود المُهيّئ.
 */
internal val chatReplyRefSaver = listSaver<ChatReplyRef?, String>(
    save = { ref ->
        if (ref == null) {
            emptyList()
        } else {
            listOf(
                ref.messageId,
                ref.senderId,
                ref.senderName,
                ref.preview,
                chatTypeToString(ref.type),
            )
        }
    },
    restore = { values ->
        if (values.size < 5) {
            null
        } else {
            ChatReplyRef(
                messageId = values[0],
                senderId = values[1],
                senderName = values[2],
                preview = values[3],
                type = chatTypeFromString(values[4]),
            )
        }
    },
)

/**
 * دردشة إدارة منبر ادكصهك الجماعيّة — بنمط واتساب، على Firestore (زمن
 * حقيقي) و Storage للمرفقات. العضويّة تلقائيّة، والمجموعة خاصّة بتطبيق
 * الإدارة — لا يراها تطبيق منبر العامّ إطلاقاً.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(isOwner: Boolean, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    var limit by remember { mutableIntStateOf(60) }
    // المسودّة ومرجع الردّ يصمدان أمام التدوير (كانا يضيعان مع كلّ دوران).
    var replyTo by rememberSaveable(stateSaver = chatReplyRefSaver) {
        mutableStateOf<ChatReplyRef?>(null)
    }
    var sending by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }

    // الرفع يعمل في ChatUploader (نطاق مستقلّ) فلا يلغيه التدوير ولا الرجوع.
    val uploads by ChatUploader.uploads.collectAsState()
    val myUploads = uploads.filter { it.target is ChatUploadTarget.Group }

    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var highlightedId by remember { mutableStateOf("") }
    var lastTypingSentMs by remember { mutableLongStateOf(0L) }
    var lastReadMarkMs by remember { mutableLongStateOf(0L) }

    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var showEmojiPicker by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var infoFor by remember { mutableStateOf<ChatMessage?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingUpload by remember { mutableStateOf<PickedFile?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }

    // مفتاح المقطع الصوتيّ العامل الآن — يُعطَّل عليه سحب الردّ كي لا يتنازع
    // مع سحب الموجة داخل فقاعة الصوت.
    val activeAudioKey by SharedAudioPlayer.activeKey.collectAsState()

    val listState = rememberLazyListState()
    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val meta by remember { ChatRepository.metaStream() }
        .collectAsState(initial = ChatGroupMeta.fallback)
    val membersList by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    val members = remember(membersList) { membersList.associateBy { it.uid } }
    // ⚠️ القائمة حالة مستقلّة لا `collectAsState(emptyList())`: توسيع النافذة
    // يعيد الربط، وتصفير القائمة كان يومض بشاشة فارغة حتى أوّل انبعاث.
    var allMessages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var rawPageSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(limit) {
        ChatRepository.messagesStream(limit.toLong()).collect { page ->
            allMessages = page.messages
            rawPageSize = page.rawSize
        }
    }
    val dmUnread by remember { DmRepository.unreadThreadsStream() }
        .collectAsState(initial = 0)

    // أوّل دفعة رسائل لم تصل بعد (كاش أو خادم) — للتمييز عن محادثة فارغة.
    var loadingFirstBatch by remember { mutableStateOf(true) }
    LaunchedEffect(allMessages) {
        if (allMessages.isNotEmpty()) loadingFirstBatch = false
    }
    LaunchedEffect(Unit) {
        delay(6000)
        loadingFirstBatch = false
    }

    val canModerate = isOwner || members[myUid]?.isChatModerator == true

    DisposableEffect(Unit) {
        ChatNotifications.isChatOpen = true
        onDispose {
            ChatNotifications.isChatOpen = false
            SharedAudioPlayer.stop()
        }
    }

    // الانضمام التلقائي + نبضات الحضور والقراءة.
    LaunchedEffect(Unit) {
        ChatRepository.upsertSelf(role = if (isOwner) "owner" else "supervisor")
        ChatRepository.presenceTick()
        ChatRepository.markRead()
        while (true) {
            delay(45_000)
            ChatRepository.presenceTick()
        }
    }

    // تحديث مؤشّر قراءتي عند وصول رسائل أحدث (مقنَّن).
    LaunchedEffect(allMessages.firstOrNull()?.sentAtMs) {
        val newest = allMessages.firstOrNull()?.sentAtMs ?: 0L
        if (newest > lastReadMarkMs) {
            lastReadMarkMs = newest
            ChatRepository.markRead()
        }
    }

    // ListView معكوس: نهاية التمرير = أقدم الرسائل → حمّل المزيد.
    // شرط `rawPageSize >= limit`: لا نوسّع النافذة إلّا إذا امتلأ الاستعلام
    // فعلاً، وإلّا تصاعد الحدّ 60←2000 في المحادثات القصيرة. والمقارنة بحجم
    // الصفحة الخام لا بالمرشَّحة، وإلّا جمّدت رسالةٌ مخفيّة واحدة الترقيم.
    LaunchedEffect(listState) {
        snapshotFlowOfLastVisible(listState) { lastIndex, total ->
            if (
                total > 0 &&
                lastIndex >= total - 5 &&
                limit < 2000 &&
                rawPageSize >= limit
            ) {
                limit += 60
            }
        }
    }

    // انتهت رسالة صوتيّة؟ شغّل التالية تلقائياً (نمط واتساب).
    DisposableEffect(allMessages) {
        SharedAudioPlayer.onCompleted = { finishedKey ->
            val index = allMessages.indexOfFirst {
                it.attachment?.let(ChatMediaStore::keyOf) == finishedKey
            }
            val next = if (index > 0) allMessages.getOrNull(index - 1) else null
            val att = next?.attachment
            if (
                att != null &&
                (next.type == ChatMessageType.Voice || next.type == ChatMessageType.Audio)
            ) {
                scope.launch {
                    ChatMediaStore.download(att)?.let { file ->
                        SharedAudioPlayer.playFile(context, ChatMediaStore.keyOf(att), file)
                    }
                }
            }
        }
        onDispose { SharedAudioPlayer.onCompleted = null }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) pendingUpload = context.pickedFileFrom(uri)
    }

    // أخطاء الرفع تصل من النطاق المستقلّ (لا من نطاق الشاشة).
    LaunchedEffect(Unit) {
        ChatUploader.errors.collect { snack(it) }
    }

    fun sendAttachment(
        file: PickedFile,
        caption: String,
        type: ChatMessageType,
        contentType: String,
        durationMs: Long? = null,
        waveform: List<Int>? = null,
        deleteAfter: File? = null,
    ) {
        val reply = replyTo
        replyTo = null
        ChatUploader.enqueue(
            target = ChatUploadTarget.Group,
            file = file,
            type = type,
            contentType = contentType,
            caption = caption,
            durationMs = durationMs,
            waveform = waveform,
            replyTo = reply,
            deleteAfter = deleteAfter,
        )
    }

    // التسجيل كلّه (الإذن والمؤقّت والقفل والمعاينة) في المكوّن المشترك
    // VoiceRecorderUi — كان مكرّراً حرفيّاً هنا وفي DmScreen فيتباعد سلوكهما.
    val voice = rememberVoiceRecorderState(
        prefix = "voice",
        onNotice = { snack(it) },
        onSend = { file, durationMs, waveform ->
            val name = "رسالة صوتيّة ${fileTimeFormat.format(Date())}.m4a"
            sendAttachment(
                file = PickedFile(android.net.Uri.fromFile(file), name, file.length()),
                caption = "",
                type = ChatMessageType.Voice,
                contentType = "audio/mp4",
                durationMs = durationMs,
                waveform = waveform,
                deleteAfter = file,
            )
        },
    )

    fun react(msg: ChatMessage, emoji: String) {
        scope.launch {
            runCatching {
                // اختيار نفس الإيموجي مجدّداً يزيله (مثل واتساب).
                val current = msg.reactions[myUid]
                ChatRepository.setReaction(msg.id, if (current == emoji) null else emoji)
            }.onFailure { snack("تعذّر التفاعل: ${it.message ?: it}") }
        }
    }

    // ترشيح البحث (داخل المحمَّل) — نمط واتساب.
    val query = searchQuery.trim().lowercase()
    val messages = remember(allMessages, query) { filterChatMessages(allMessages, query) }

    /**
     * القائمة **المعروضة الآن** — تُقرأ من الحالة الحيّة داخل الكوروتين، فلا
     * تتجمّد على لقطة إعادة التركيب التي أُطلق منها القفز (مسح البحث يجب أن
     * ينعكس فوراً على الفهرس المحسوب).
     */
    fun displayedNow(): List<ChatMessage> =
        filterChatMessages(allMessages, searchQuery.trim().lowercase())

    /**
     * القفز إلى الرسالة الأصليّة عند نقر الاقتباس أو شريط التثبيت (واتساب).
     * ⚠️ الفهرس يُحسب من القائمة المعروضة فعلاً لا من كامل المحمَّل: أثناء
     * بحث نشط كان النقر يقفز صامتاً إلى مكان خاطئ ويُضيء رسالة غير مرسومة.
     * فإن كانت الرسالة خارج نتائج البحث مُسح البحث أوّلاً ثمّ قُفز إليها.
     */
    fun jumpTo(ref: ChatReplyRef) {
        scope.launch {
            if (
                searchQuery.isNotBlank() &&
                displayedNow().none { it.id == ref.messageId }
            ) {
                searching = false
                searchQuery = ""
                // مهلة قصيرة كي تُبنى القائمة الكاملة قبل حساب الفهرس.
                delay(80)
            }
            repeat(5) {
                val index = displayedNow().indexOfFirst { it.id == ref.messageId }
                if (index >= 0) {
                    listState.animateScrollToItem(index)
                    highlightedId = ref.messageId
                    delay(1600)
                    highlightedId = ""
                    return@launch
                }
                // بلغنا سقف النافذة ولم نجدها: رسالة صريحة بدل صمت.
                if (limit >= 2000) {
                    snack("الرسالة الأصليّة أقدم من المحمَّل حاليّاً.")
                    return@launch
                }
                limit += 200
                delay(320)
            }
            snack("الرسالة الأصليّة أقدم من المحمَّل حاليّاً.")
        }
    }

    // ── الأوراق المنسدلة ───────────────────────────────────
    pendingUpload?.let { file ->
        val contentType = guessContentType(file.name)
        CaptionDialog(
            file = file,
            type = chatTypeForMime(contentType),
            onDismiss = { pendingUpload = null },
            onSend = { caption ->
                pendingUpload = null
                sendAttachment(file, caption, chatTypeForMime(contentType), contentType)
            },
        )
    }

    actionsFor?.let { msg ->
        MessageActionsSheet(
            msg = msg,
            canModerate = canModerate,
            isOwner = isOwner,
            members = members,
            myUid = myUid,
            onDismiss = { actionsFor = null },
            onAction = { action ->
                actionsFor = null
                when {
                    action.startsWith("react:") -> react(msg, action.removePrefix("react:"))
                    action == "react_more" -> showEmojiPicker = msg
                    action == "reply" -> replyTo = msg.asRef()
                    action == "reply_private" -> {
                        val name = members[msg.senderId]?.displayName ?: msg.senderName
                        scope.launch {
                            val threadId = DmRepository.ensureThread(msg.senderId)
                            PendingGroupQuote.value = msg.asRef()
                            nav.navigate(Routes.dm(threadId, msg.senderId, name))
                        }
                    }

                    action == "forward" -> forwarding = msg
                    action == "copy" -> {
                        copyText(context, msg.text)
                        snack("نُسخ النصّ.")
                    }

                    action == "pin" -> scope.launch {
                        runCatching { ChatRepository.setPinned(msg.asRef()) }
                            .onSuccess { snack("ثُبّتت الرسالة.") }
                            .onFailure { snack("تعذّر التثبيت: ${it.message ?: it}") }
                    }

                    action == "info" -> infoFor = msg
                    action == "open" -> msg.attachment?.url?.let {
                        context.openExternalUri(android.net.Uri.parse(it))
                    }

                    action == "delete_me" -> scope.launch {
                        runCatching { ChatRepository.deleteForMe(msg.id) }
                            .onFailure { snack("تعذّر الحذف: ${it.message ?: it}") }
                    }

                    action == "delete_all" -> confirmDeleteAll = msg
                }
            },
        )
    }

    forwarding?.let { msg ->
        ForwardPickerSheet(
            members = membersList,
            myUid = myUid,
            includeGroup = false,
            onDismiss = { forwarding = null },
            onPickGroup = {},
            onPickMember = { member ->
                forwarding = null
                // النقل يمرّ بـChatUploader لأن نسخ المرفق رفعٌ طويل يجب أن
                // يصمد أمام التدوير والرجوع (وإلّا بقي ملفّ يتيم بلا رسالة).
                val threadId = DmRepository.ensureThread(member.uid)
                ChatUploader.enqueueForward(
                    ChatUploadTarget.Dm(threadId = threadId, otherUid = member.uid),
                    msg,
                )
                snack("جارٍ إعادة التوجيه إلى ${member.displayName}…")
            },
        )
    }

    showEmojiPicker?.let { msg ->
        EmojiPickerSheet(
            onDismiss = { showEmojiPicker = null },
            onPicked = { emoji ->
                showEmojiPicker = null
                react(msg, emoji)
            },
        )
    }

    reactionsFor?.let { msg ->
        ReactionsSheet(
            msg = msg,
            members = members,
            myUid = myUid,
            onDismiss = { reactionsFor = null },
            onRemoveMine = { emoji ->
                reactionsFor = null
                react(msg, emoji)
            },
        )
    }

    infoFor?.let { msg ->
        MessageInfoSheet(
            msg = msg,
            members = members,
            onDismiss = { infoFor = null },
        )
    }

    if (showAttachMenu) {
        AttachMenuSheet(
            onDismiss = { showAttachMenu = false },
            onPick = { mime ->
                showAttachMenu = false
                picker.launch(mime)
            },
        )
    }

    confirmDeleteAll?.let { msg ->
        com.ali.ishaqiyin_admin.ui.ConfirmDialog(
            title = "حذف عند الجميع",
            body = "ستُحذف هذه الرسالة عند جميع الأعضاء ولا يمكن التراجع. متابعة؟",
            confirmLabel = "حذف",
            confirmColor = ChatColors.rose,
            onDismiss = { confirmDeleteAll = null },
            onConfirm = {
                confirmDeleteAll = null
                scope.launch {
                    runCatching { ChatRepository.deleteForEveryone(msg) }
                        .onFailure { snack("تعذّر الحذف: ${it.message ?: it}") }
                }
            },
        )
    }

    // ── الواجهة ────────────────────────────────────────────
    WhatsAppChatBackground(Modifier.fillMaxSize()) {
        // edge-to-edge إجباريّ على targetSdk 36: بلا هذه الحشوات يقع شريط
        // الإدخال والميكروفون خلف شريط التنقّل فتُبتلع اللمسات، ويختفي جزء
        // من الترويسة خلف شريط الحالة.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            GroupHeader(
                meta = meta,
                members = membersList,
                myUid = myUid,
                dmUnread = dmUnread,
                onBack = { nav.popBackStack() },
                onToggleSearch = {
                    searching = !searching
                    if (!searching) searchQuery = ""
                },
                onOpenDms = { nav.navigate(Routes.DM_LIST) },
                onOpenInfo = { nav.navigate(Routes.GROUP_INFO) },
            )
            if (searching) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(ChatColors.surface)
                        .padding(start = 14.dp, end = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("ابحث في الرسائل…") },
                        singleLine = true,
                        colors = adminFieldColors(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(
                        onClick = {
                            searching = false
                            searchQuery = ""
                        },
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            meta.pinned?.let { pinned ->
                PinnedBar(
                    pinned = pinned,
                    canModerate = canModerate,
                    onTap = { jumpTo(pinned) },
                    onUnpin = { scope.launch { runCatching { ChatRepository.setPinned(null) } } },
                )
            }
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // على شبكة ضعيفة قد تتأخّر أوّل دفعة: نُظهر انتظاراً
                        // صريحاً بدل «لا رسائل» المُضلِّلة.
                        if (loadingFirstBatch && query.isEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = ChatColors.accent,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "جارٍ تحميل الرسائل…",
                                    color = ChatColors.textMuted,
                                    fontSize = 12.5.sp,
                                )
                            }
                        } else {
                            Text(
                                if (query.isEmpty()) {
                                    "لا رسائل بعد — ابدأ النقاش!"
                                } else {
                                    "لا نتائج لـ «$searchQuery» ضمن المحمَّل."
                                },
                                color = ChatColors.textMuted,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            vertical = 10.dp,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // ⚠️ مفتاح ثابت لكلّ رسالة: بلاه كانت إعادة استعمال
                        // العناصر تُغلق عارض الصورة/الفيديو عند وصول رسالة،
                        // وتعرض ملفّاً مغايراً في حوار مفتوح، وتزيح التمرير.
                        items(
                            count = messages.size,
                            key = { messages[it].id },
                            contentType = { chatTypeToString(messages[it].type) },
                        ) { i ->
                            val msg = messages[i]
                            // القائمة معكوسة: العنصر التالي (i+1) أقدم زمنيّاً.
                            val older = messages.getOrNull(i + 1)
                            val showHeader = older == null || older.senderId != msg.senderId
                            val showDateChip = older == null ||
                                !sameDay(older.createdAtMs, msg.createdAtMs)
                            // ✓✓ زرقاء: قرأ الرسالةَ كلُّ الأعضاء الآخرين.
                            val others = membersList.filter { it.uid != msg.senderId }
                            val readByAll = others.isNotEmpty() &&
                                others.all { it.lastReadAtMs >= msg.sentAtMs }
                            val highlightColor by animateColorAsState(
                                when {
                                    // الضغط المطوّل: تظليل الرسالة المستهدفة
                                    // ما دامت ورقة الإجراءات مفتوحة عليها.
                                    actionsFor?.id == msg.id ->
                                        ChatColors.accent.copy(alpha = 0.10f)

                                    highlightedId == msg.id -> ChatColors.highlight
                                    else -> Color.Transparent
                                },
                                label = "highlight",
                            )
                            // سحب الردّ يتنازع مع سحب موجة الصوت: يُعطَّل على
                            // الرسالة الصوتيّة النشطة وحدها.
                            val isActiveAudio = activeAudioKey != null &&
                                msg.attachment?.let(ChatMediaStore::keyOf) == activeAudioKey
                            Column(Modifier.background(highlightColor)) {
                                if (showDateChip) DateChip(msg.createdAtMs)
                                SwipeToReply(
                                    enabled = !msg.deleted && !isActiveAudio,
                                    onReply = { replyTo = msg.asRef() },
                                ) {
                                    MessageBubble(
                                        msg = msg,
                                        showSenderHeader = showHeader,
                                        members = members,
                                        readByAll = readByAll,
                                        onLongPress = { actionsFor = it },
                                        onReplyTap = { jumpTo(it) },
                                        onReactionsTap = { reactionsFor = it },
                                        // أوّل استماع لرسالة صوتيّة = ميكروفون
                                        // أزرق عند مرسِلها (نمط واتساب).
                                        onListened = { ChatRepository.markListened(it.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                val showJump by remember {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex > 3
                    }
                }
                if (showJump) {
                    FloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = ChatColors.surfaceAlt,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardDoubleArrowDown,
                            contentDescription = null,
                            tint = ChatColors.accent,
                        )
                    }
                }
            }
            val online by NetworkMonitor.online.collectAsState()
            if (!online) OfflineBanner()
            // شريط مستقلّ لكلّ عمليّة رفع (رفع صورة + رسالة صوتيّة معاً).
            myUploads.forEach { upload ->
                UploadBanner(
                    name = upload.name,
                    percent = upload.percent,
                    onCancel = { ChatUploader.cancel(upload.id) },
                )
            }
            replyTo?.let { ReplyBanner(it) { replyTo = null } }
            ClipboardImageSuggestion(
                enabled = !voice.showsBar && (!meta.locked || isOwner),
                onImage = { uri -> pendingUpload = context.pickedFileFrom(uri) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // مقفول أو معاينة: شريط مستقلّ يتقدّم على شريط قفل المجموعة —
            // إخفاؤه أثناء تسجيل مقفول كان يترك المسجّل عاملاً بلا زرّ حذف
            // ولا إرسال. أمّا أثناء الضغط المطوّل فيبقى شريط الإدخال كما هو
            // (تغيير تخطيطه يزيح الزرّ فيُطلق القفل/الإلغاء زوراً).
            if (voice.showsBar) {
                VoiceRecorderBar(voice)
            } else if (meta.locked && !isOwner) {
                LockedBar()
            } else {
                InputBar(
                    text = text,
                    onTextChange = {
                        text = it
                        if (it.trim().isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastTypingSentMs > 3000) {
                                lastTypingSentMs = now
                                scope.launch { ChatRepository.typingTick() }
                            }
                        }
                    },
                    sending = sending,
                    onAttach = { showAttachMenu = true },
                    onSend = {
                        val body = text.trim()
                        if (body.isEmpty() || sending) return@InputBar
                        sending = true
                        val reply = replyTo
                        text = ""
                        replyTo = null
                        scope.launch {
                            try {
                                ChatRepository.sendText(body, replyTo = reply)
                                ChatRepository.markRead()
                            } catch (e: Exception) {
                                snack("تعذّر الإرسال: ${e.message ?: e}")
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

/** اقتباس مؤقّت يُمرَّر إلى شاشة المحادثة الخاصّة عند «الردّ بشكل خاص». */
object PendingGroupQuote {
    var value: ChatReplyRef? = null
}

/** ترشيح البحث المشترك بين القائمة المرسومة وحساب فهرس القفز. */
private fun filterChatMessages(all: List<ChatMessage>, query: String): List<ChatMessage> {
    if (query.isEmpty()) return all
    return all.filter {
        it.text.lowercase().contains(query) ||
            it.attachment?.name?.lowercase()?.contains(query) == true ||
            it.senderName.lowercase().contains(query)
    }
}

/**
 * سحب أفقيّ = ردّ سريع (نمط واتساب): الدلتا **متراكمة** لا دلتا الحدث
 * الواحد — فالسحب المتأنّي كان لا يفعّله والقذفة العابرة تفعّله. الفقاعة
 * تنزلق مع الإصبع بمقاومة 0.55 بحدّ 72dp، وتظهر أيقونة الردّ داخل دائرة
 * 34dp بشفافيّة ومقياس متناميين، ويهتزّ الجهاز **مرّة واحدة** عند بلوغ
 * عتبة 56dp، ثمّ ترتدّ الفقاعة بزنبرك عند الرفع مع إطلاق الردّ مرّة واحدة.
 */
@Composable
private fun SwipeToReply(
    enabled: Boolean,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { 56.dp.toPx() }
    val maxShift = with(density) { 72.dp.toPx() }
    var target by remember { mutableFloatStateOf(0f) }
    var travelled by remember { mutableFloatStateOf(0f) }
    var buzzed by remember { mutableStateOf(false) }
    val shift by animateFloatAsState(
        target,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "replySwipe",
    )
    val progress = (abs(shift) / threshold).coerceIn(0f, 1f)
    Box(
        Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = {
                    travelled = 0f
                    buzzed = false
                },
                onDragEnd = {
                    if (abs(travelled) >= threshold) onReply()
                    travelled = 0f
                    buzzed = false
                    target = 0f
                },
                onDragCancel = {
                    travelled = 0f
                    buzzed = false
                    target = 0f
                },
            ) { change, dragAmount ->
                travelled += dragAmount
                target = (travelled * 0.55f).coerceIn(-maxShift, maxShift)
                if (!buzzed && abs(travelled) >= threshold) {
                    buzzed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                change.consume()
            }
        },
    ) {
        if (progress > 0.02f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .scale(0.6f + 0.4f * progress)
                    .alpha(progress)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ChatColors.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = ChatColors.accentDark,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Box(Modifier.offset { IntOffset(shift.roundToInt(), 0) }) { content() }
    }
}

private suspend fun snapshotFlowOfLastVisible(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onChange: (lastIndex: Int, total: Int) -> Unit,
) {
    androidx.compose.runtime.snapshotFlow {
        val info = listState.layoutInfo
        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
    }.collect { (lastIndex, total) -> onChange(lastIndex, total) }
}

@Composable
private fun GroupHeader(
    meta: ChatGroupMeta,
    members: List<ChatMember>,
    myUid: String,
    dmUnread: Int,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenDms: () -> Unit,
    onOpenInfo: () -> Unit,
) {
    // «متصل الآن» و«يكتب…» نوافذ زمنيّة: بلا نبضة ثانية تبقى معروضة حتى
    // إعادة التركيب التالية فلا تزول في وقتها.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val online = members.count { now - it.lastActiveAtMs < ONLINE_WINDOW_MS }
    val typers = members
        .filter { it.uid != myUid && now - it.typingAtMs < TYPING_WINDOW_MS }
        .map { it.displayName }
    val subtitle: String
    val subtitleColor: Color
    when {
        typers.isNotEmpty() -> {
            subtitle = if (typers.size == 1) {
                "${typers.first()} يكتب الآن…"
            } else {
                "${typers.take(2).joinToString("، ")} يكتبون الآن…"
            }
            subtitleColor = ChatColors.accent
        }

        members.isNotEmpty() -> {
            subtitle = "${members.size} عضو • $online متصل الآن" +
                if (meta.locked) " • 🔒" else ""
            subtitleColor = if (online > 0) ChatColors.online else ChatColors.metaText
        }

        else -> {
            subtitle = "خاصّة بمشرفي الإدارة"
            subtitleColor = ChatColors.metaText
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(ChatNotifications.isMuted) }
    val scope = rememberCoroutineScope()

    Surface(color = ChatColors.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenInfo)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // حشو مقلَّص كي يقترب العنوان من حافة الشاشة (نمط واتساب).
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع",
                    tint = ChatColors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (meta.photoUrl.isEmpty()) {
                            Brush.horizontalGradient(
                                listOf(ChatColors.accentDark, ChatColors.accent),
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (meta.photoUrl.isEmpty()) {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    RemoteImage(meta.photoUrl, Modifier.size(40.dp).clip(CircleShape))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                )
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    color = subtitleColor,
                )
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "بحث في الرسائل",
                    tint = ChatColors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            CountBadge(dmUnread) {
                IconButton(onClick = onOpenDms) {
                    Icon(
                        Icons.Outlined.Forum,
                        contentDescription = "الرسائل الخاصّة",
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // كانت أيقونة «معلومات» غير قابلة للنقر — صارت قائمة خيارات
            // حقيقيّة (معلومات المجموعة / بحث / كتم) بنمط واتساب.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "خيارات",
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = ChatColors.accentDark,
                            )
                        },
                        text = { Text("معلومات المجموعة") },
                        onClick = {
                            menuOpen = false
                            onOpenInfo()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = ChatColors.textMuted,
                            )
                        },
                        text = { Text("بحث في الرسائل") },
                        onClick = {
                            menuOpen = false
                            onToggleSearch()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                if (muted) {
                                    Icons.Filled.NotificationsOff
                                } else {
                                    Icons.Filled.Notifications
                                },
                                contentDescription = null,
                                tint = if (muted) ChatColors.textMuted else ChatColors.accent,
                            )
                        },
                        text = { Text(if (muted) "إلغاء كتم الإشعارات" else "كتم الإشعارات") },
                        onClick = {
                            menuOpen = false
                            val next = !muted
                            muted = next
                            scope.launch { ChatNotifications.setMuted(next) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedBar(
    pinned: ChatReplyRef,
    canModerate: Boolean,
    onTap: () -> Unit,
    onUnpin: () -> Unit,
) {
    Surface(color = ChatColors.highlight) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = null,
                tint = ChatColors.amber,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "رسالة مثبَّتة — ${pinned.senderName}",
                    fontSize = 10.5.sp,
                    color = ChatColors.amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    pinned.preview.ifEmpty { chatTypeLabel(pinned.type) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                )
            }
            if (canModerate) {
                IconButton(onClick = onUnpin) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "إلغاء التثبيت",
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * بانر «دون اتصال» — على شبكة ضعيفة/مقطوعة تُحفظ الرسائل النصّية محليّاً
 * (كاش Firestore الدائم) وتُرسَل تلقائياً عند العودة، والتنزيلات تُستأنف
 * من حيث توقّفت.
 */
@Composable
fun OfflineBanner() {
    Surface(color = ChatColors.amber.copy(alpha = 0.14f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = ChatColors.amber,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "دون اتصال — ما تكتبه يُحفظ ويُرسَل تلقائياً، والتنزيلات تُستأنف.",
                fontSize = 11.5.sp,
                color = ChatColors.amber,
            )
        }
    }
}

@Composable
fun UploadBanner(name: String, percent: Double, onCancel: () -> Unit) {
    Surface(color = ChatColors.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "جارٍ رفع: $name",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (percent / 100).toFloat() },
                    color = ChatColors.accent,
                    trackColor = ChatColors.border,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("${percent.toInt()}%", fontSize = 11.sp, color = ChatColors.textMuted)
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "إلغاء الرفع",
                    tint = ChatColors.rose,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun ReplyBanner(ref: ChatReplyRef, onClear: () -> Unit) {
    Surface(color = ChatColors.surface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).background(senderColor(ref.senderId)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "ردّ على ${ref.senderName}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = senderColor(ref.senderId),
                )
                Text(
                    ref.preview.ifEmpty { chatTypeLabel(ref.type) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                    color = ChatColors.textMuted,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = ChatColors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** شريط بديل عن الإدخال عندما يقفل المالك المجموعة. */
@Composable
private fun LockedBar() {
    Surface(color = ChatColors.surface) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = ChatColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "المجموعة مقفلة — الإرسال متاح للمالك 👑 فقط",
                fontSize = 12.5.sp,
                color = ChatColors.textMuted,
            )
        }
    }
}

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
    if (showEmoji) {
        EmojiPickerSheet(
            onDismiss = { showEmoji = false },
            onPicked = { emoji ->
                showEmoji = false
                onTextChange(text + emoji)
            },
        )
    }
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
        }
    }
}

/** حوار تأكيد الإرسال مع تعليق اختياري (مثل واتساب). */
@Composable
private fun CaptionDialog(
    file: PickedFile,
    type: ChatMessageType,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إرسال ${chatTypeLabel(type)}") },
        text = {
            Column {
                Text(
                    file.name + if (file.size > 0) " • ${formatBytes(file.size)}" else "",
                    fontSize = 12.sp,
                    color = ChatColors.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("أضف تعليقاً (اختياري)…") },
                    minLines = 1,
                    maxLines = 3,
                    colors = adminFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(caption) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("إرسال")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    msg: ChatMessage,
    canModerate: Boolean,
    isOwner: Boolean,
    members: Map<String, ChatMember>,
    myUid: String,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canDeleteForAll = !msg.deleted && (msg.isMine || canModerate)
    val modBadge = if (isOwner) "👑" else "⭐"
    val myReaction = msg.reactions[myUid]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (!msg.deleted) {
                // شريط التفاعلات السريعة (مثل واتساب).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    if (myReaction == emoji) {
                                        ChatColors.highlight
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onAction("react:$emoji") }
                                .padding(7.dp),
                        ) { Text(emoji, fontSize = 26.sp) }
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(ChatColors.surfaceAlt)
                            .clickable { onAction("react_more") }
                            .padding(9.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                HorizontalDivider()
                SheetItem(Icons.AutoMirrored.Filled.Reply, "ردّ", ChatColors.accent) {
                    onAction("reply")
                }
                // ردّ بشكل خاصّ: يفتح محادثة فرديّة مع المرسِل مع اقتباس
                // رسالته من المجموعة (نمط واتساب تماماً).
                if (!msg.isMine && msg.senderId != "system") {
                    SheetItem(
                        Icons.AutoMirrored.Filled.ForwardToInbox,
                        "ردّ بشكل خاص",
                        ChatColors.accentDark,
                        subtitle = "محادثة فرديّة مع " +
                            (members[msg.senderId]?.displayName ?: msg.senderName),
                    ) { onAction("reply_private") }
                }
                SheetItem(Icons.Filled.Share, "إعادة توجيه", ChatColors.accent) {
                    onAction("forward")
                }
                if (msg.text.isNotBlank()) {
                    SheetItem(Icons.Filled.ContentCopy, "نسخ النصّ", ChatColors.textMuted) {
                        onAction("copy")
                    }
                }
                if (canModerate) {
                    SheetItem(Icons.Filled.PushPin, "تثبيت الرسالة $modBadge", ChatColors.amber) {
                        onAction("pin")
                    }
                }
            }
            if (msg.isMine && !msg.pending) {
                SheetItem(Icons.Outlined.Info, "معلومات الرسالة", ChatColors.textMuted) {
                    onAction("info")
                }
            }
            if (!msg.deleted && msg.attachment != null) {
                SheetItem(Icons.Filled.Download, "فتح / تنزيل المرفق", ChatColors.textMuted) {
                    onAction("open")
                }
            }
            SheetItem(Icons.Filled.Delete, "حذف عندي فقط", ChatColors.amber) {
                onAction("delete_me")
            }
            if (canDeleteForAll) {
                SheetItem(
                    Icons.Filled.DeleteForever,
                    if (msg.isMine) "حذف عند الجميع" else "حذف عند الجميع $modBadge",
                    ChatColors.rose,
                    textColor = ChatColors.rose,
                ) { onAction("delete_all") }
            }
        }
    }
}

@Composable
private fun SheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: Color,
    subtitle: String? = null,
    textColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = textColor)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = ChatColors.textMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            modifier = Modifier.fillMaxWidth().height(320.dp),
        ) {
            items(emojiPalette.size) { i ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPicked(emojiPalette[i]) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(emojiPalette[i], fontSize = 24.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionsSheet(
    msg: ChatMessage,
    members: Map<String, ChatMember>,
    myUid: String,
    onDismiss: () -> Unit,
    onRemoveMine: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                "التفاعلات",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                textAlign = TextAlign.Center,
            )
            HorizontalDivider()
            msg.reactions.forEach { (uid, emoji) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = uid == myUid) { onRemoveMine(emoji) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(
                        uid = uid,
                        name = members[uid]?.displayName ?: "عضو",
                        photo = members[uid]?.displayPhoto.orEmpty(),
                        radius = 17,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (uid == myUid) {
                                "${members[uid]?.displayName ?: "أنا"} (أنا)"
                            } else {
                                members[uid]?.displayName ?: "عضو سابق"
                            },
                            fontSize = 13.5.sp,
                        )
                        if (uid == myUid) {
                            Text("اضغط للإزالة", fontSize = 10.5.sp, color = ChatColors.textMuted)
                        }
                    }
                    Text(emoji, fontSize = 22.sp)
                }
            }
        }
    }
}

/** معلومات رسالتي: وقت الإرسال الكامل ومن قرأها. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    msg: ChatMessage,
    members: Map<String, ChatMember>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val others = members.values.filter { it.uid != msg.senderId }
    val readers = others.filter { it.lastReadAtMs >= msg.sentAtMs }
    val pending = others.filter { it.lastReadAtMs < msg.sentAtMs }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("معلومات الرسالة", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = ChatColors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "أُرسلت: ${fullTimeFormat.format(Date(msg.createdAtMs))}",
                    fontSize = 12.5.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DoneAll,
                    contentDescription = null,
                    tint = ChatColors.readBlue,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("قرأها (${readers.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (readers.isEmpty()) {
                Text(
                    "لم يقرأها أحد بعد.",
                    fontSize = 12.sp,
                    color = ChatColors.textMuted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            readers.forEach { ReaderTile(it) }
            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Done,
                        contentDescription = null,
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "لم يقرأها بعد (${pending.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                pending.forEach { ReaderTile(it) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReaderTile(member: ChatMember) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(
            uid = member.uid,
            name = member.displayName,
            photo = member.displayPhoto,
            radius = 13,
        )
        Spacer(Modifier.width(8.dp))
        Text(member.displayName, fontSize = 12.5.sp)
        if (member.isOwner) {
            Spacer(Modifier.width(4.dp))
            Text("👑", fontSize = 11.sp)
        }
    }
}

/**
 * ورقة اختيار وجهة «إعادة التوجيه»: المجموعة أو أحد المشرفين. المرفق
 * يُعاد استعماله كما هو (بلا رفع جديد) فلا يكلّف شيئاً على شبكة ضعيفة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    members: List<ChatMember>,
    myUid: String,
    includeGroup: Boolean,
    onDismiss: () -> Unit,
    onPickGroup: () -> Unit,
    onPickMember: (ChatMember) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                "إعادة التوجيه إلى…",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
            HorizontalDivider()
            if (includeGroup) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickGroup)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(ChatColors.accentDark, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("مجموعة الإدارة")
                }
                HorizontalDivider()
            }
            members.filter { it.uid != myUid }.forEach { member ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPickMember(member) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(
                        uid = member.uid,
                        name = member.displayName,
                        photo = member.displayPhoto,
                        radius = 18,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(member.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachMenuSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة تُرسم خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة:
        // بلا هذا يبقى النصّ داكناً فوق سطح داكن.
        contentColor = ChatColors.textPrimary,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AttachOption(Icons.Filled.Image, "صورة", ChatColors.accent) { onPick("image/*") }
            AttachOption(Icons.Filled.Videocam, "فيديو", ChatColors.amber) { onPick("video/*") }
            AttachOption(Icons.Filled.MusicNote, "صوت", Color(0xFF2563EB)) { onPick("audio/*") }
            AttachOption(Icons.AutoMirrored.Filled.InsertDriveFile, "ملفّ", Color(0xFF7C3AED)) { onPick("*/*") }
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).background(color.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = ChatColors.textMuted)
    }
}
