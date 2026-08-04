package com.ali.ishaqiyin_admin.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ali.ishaqiyin_admin.call.CallActivity
import com.ali.ishaqiyin_admin.call.CallEngine
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatReplyRef
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.ChatUploadTarget
import com.ali.ishaqiyin_admin.data.ChatUploader
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.QUICK_REACTIONS
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.data.chatTypeForMime
import com.ali.ishaqiyin_admin.data.chatTypeLabel
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.ui.ClipboardImageSuggestion
import com.ali.ishaqiyin_admin.ui.ConfirmDialog
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.adminFieldColors
import com.ali.ishaqiyin_admin.util.PickedFile
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

private val lastSeenFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private val voiceNameFmt = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)

/**
 * مواصفات «سحب الردّ السريع» (نمط واتساب حرفيّاً): العتبة تُقاس على الدلتا
 * المتراكمة الخام، والفقاعة تنزلق بمقاومة [REPLY_SWIPE_RESISTANCE] بحدّ
 * أقصى [REPLY_SWIPE_MAX] فلا تُفلت مع الإصبع.
 */
private val REPLY_SWIPE_THRESHOLD = 56.dp
private val REPLY_SWIPE_MAX = 72.dp
private const val REPLY_SWIPE_RESISTANCE = 0.55f

/**
 * 💬 محادثة خاصّة بين مشرفَين — بنفس مزايا المجموعة: نصّ/صور/فيديو/صوت/
 * رسائل صوتيّة/ملفّات، ردود، تفاعلات، حضور، «يكتب…»، وعلامة قراءة ✓✓،
 * والوسائط تُنزَّل أوّلاً ثم تُشغَّل محليّاً (نمط واتساب).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmScreen(threadId: String, otherUid: String, otherName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    var limit by remember { mutableIntStateOf(60) }
    // المسودّة والاقتباسات تصمد أمام التدوير (كانت تضيع مع كلّ دوران).
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var replyTo by rememberSaveable(stateSaver = chatReplyRefSaver) {
        mutableStateOf<ChatReplyRef?>(null)
    }
    // اقتباس رسالة المجموعة عند «الردّ بشكل خاص» — يُلتقط هنا قبل تصفير
    // PendingGroupQuote في DisposableEffect، ثمّ يصمد أمام التدوير.
    var quotedFromGroup by rememberSaveable(stateSaver = chatReplyRefSaver) {
        mutableStateOf(PendingGroupQuote.value)
    }

    // الرفع يعمل في ChatUploader (نطاق مستقلّ) فلا يلغيه التدوير ولا الرجوع.
    val uploads by ChatUploader.uploads.collectAsState()
    val myUploads = uploads.filter {
        val target = it.target
        target is ChatUploadTarget.Dm && target.threadId == threadId
    }

    var lastTypingSentMs by remember { mutableLongStateOf(0L) }
    var lastReadMarkMs by remember { mutableLongStateOf(0L) }

    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    // مَن تفاعل بأيّ إيموجي (نقر شريط التفاعلات) — كانت الفقاعة تستجيب
    // للّمس بصريّاً بلا أيّ سلوك، خلافاً لِما توعد به الشاشة.
    var reactionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    // الرسالة المُضاءة لحظات بعد القفز إليها من اقتباس الردّ.
    var highlightedId by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<PickedFile?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearChat by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf<ChatMessage?>(null) }

    val listState = rememberLazyListState()
    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val membersList by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    val members = remember(membersList) { membersList.associateBy { it.uid } }
    val other = members[otherUid]
    // ⚠️ حالة مستقلّة لا `collectAsState(emptyList())`: توسيع النافذة يعيد
    // الربط، وتصفير القائمة كان يومض بشاشة فارغة حتى أوّل انبعاث.
    var messages by remember(threadId) { mutableStateOf(emptyList<ChatMessage>()) }
    var rawPageSize by remember(threadId) { mutableIntStateOf(0) }
    LaunchedEffect(threadId, limit) {
        DmRepository.messagesStream(threadId, limit.toLong()).collect { page ->
            messages = page.messages
            rawPageSize = page.rawSize
        }
    }
    val thread by remember(threadId) { DmRepository.threadStream(threadId) }
        .collectAsState(initial = null)
    val otherTyping by remember(threadId, otherUid) {
        DmRepository.otherTypingStream(threadId, otherUid)
    }.collectAsState(initial = false)

    var loadingFirstBatch by remember { mutableStateOf(true) }
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) loadingFirstBatch = false
    }
    LaunchedEffect(Unit) {
        delay(6000)
        loadingFirstBatch = false
    }

    // نهاية التمرير (أقدم الرسائل) → وسّع النافذة المحمَّلة.
    // المقارنة بحجم الصفحة الخام لا بالمرشَّحة: رسالة مخفيّة واحدة كانت تكفي
    // لتجميد الترقيم إلى الأبد فلا يصل المستخدم لما هو أقدم.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastIndex ->
            if (
                messages.isNotEmpty() &&
                lastIndex >= messages.size - 5 &&
                limit < 2000 &&
                rawPageSize >= limit
            ) {
                limit += 60
            }
        }
    }

    // ترشيح البحث (داخل المحمَّل) — نمط واتساب. مرفوع إلى هنا ليراه القفز
    // إلى الرسالة المقتبَسة قبل بناء القائمة.
    val query = searchQuery.trim().lowercase()
    val shown = remember(messages, query) {
        if (query.isEmpty()) {
            messages
        } else {
            messages.filter {
                it.text.lowercase().contains(query) ||
                    it.attachment?.name?.lowercase()?.contains(query) == true
            }
        }
    }

    /**
     * القفز إلى الرسالة المقتبَسة وإضاءتها لحظات (نمط واتساب) — يوسّع
     * النافذة المحمَّلة تدريجياً إن كانت أقدم من المعروض.
     *
     * البحث النشط يرشّح القائمة فيختلف فهرسها عن المعروض، لذا يُغلق أوّلاً.
     */
    fun jumpTo(ref: ChatReplyRef) {
        searching = false
        searchQuery = ""
        scope.launch {
            repeat(4) {
                // `messages` حالة حيّة: توسيع النافذة أدناه يُحدّثها فعلاً.
                val index = messages.indexOfFirst { it.id == ref.messageId }
                if (index >= 0) {
                    listState.animateScrollToItem(index)
                    highlightedId = ref.messageId
                    delay(1600)
                    highlightedId = ""
                    return@launch
                }
                if (limit >= 2000) return@repeat
                limit += 200
                delay(320)
            }
            snack("الرسالة الأصليّة أقدم من المحمَّل حاليّاً.")
        }
    }

    DisposableEffect(threadId) {
        ChatNotifications.openDmThreadId = threadId
        PendingGroupQuote.value = null
        onDispose {
            ChatNotifications.openDmThreadId = ""
            SharedAudioPlayer.stop()
        }
    }

    // 📞 كشف المكالمات الواردة داخل التطبيق: لا يعتمد على إشعار FCM (يرجع
    // مبكّراً إن عُطّلت الإشعارات أو رُفض إذنها) — فتظهر شاشة الرنين هنا.
    LaunchedEffect(Unit) { CallEngine.startIncomingWatch(context) }

    LaunchedEffect(threadId) {
        DmRepository.markRead(threadId)
        ChatRepository.presenceTick()
        while (true) {
            delay(45_000)
            ChatRepository.presenceTick()
        }
    }

    LaunchedEffect(messages.firstOrNull()?.sentAtMs) {
        val newest = messages.firstOrNull()?.sentAtMs ?: 0L
        if (newest > lastReadMarkMs) {
            lastReadMarkMs = newest
            DmRepository.markRead(threadId)
        }
    }

    // انتهت رسالة صوتيّة؟ شغّل التالية تلقائياً (نمط واتساب — كالمجموعة).
    DisposableEffect(messages) {
        SharedAudioPlayer.onCompleted = { finishedKey ->
            val index = messages.indexOfFirst {
                it.attachment?.let(ChatMediaStore::keyOf) == finishedKey
            }
            val next = if (index > 0) messages.getOrNull(index - 1) else null
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
    ) { uri -> if (uri != null) pendingUpload = context.pickedFileFrom(uri) }

    // أخطاء الرفع تصل من النطاق المستقلّ (لا من نطاق الشاشة).
    LaunchedEffect(Unit) {
        ChatUploader.errors.collect { snack(it) }
    }

    fun sendAttachment(
        file: PickedFile,
        type: ChatMessageType,
        contentType: String,
        durationMs: Long? = null,
        waveform: List<Int>? = null,
        deleteAfter: File? = null,
    ) {
        val reply = replyTo
        // الردّ الخاصّ بمرفق يحمل اقتباس المجموعة أيضاً (كما في الردّ النصّي).
        val quoted = quotedFromGroup
        replyTo = null
        quotedFromGroup = null
        ChatUploader.enqueue(
            target = ChatUploadTarget.Dm(threadId, otherUid),
            file = file,
            type = type,
            contentType = contentType,
            durationMs = durationMs,
            waveform = waveform,
            replyTo = reply,
            fromGroup = quoted,
            deleteAfter = deleteAfter,
        )
    }

    // نفس مكوّن التسجيل المشترك المستعمل في المجموعة (الإذن والمؤقّت
    // والقفل والمعاينة) — بادئة الملفّ وحدها تختلف.
    val voice = rememberVoiceRecorderState(
        prefix = "dm_voice",
        onNotice = { snack(it) },
        onSend = { file, durationMs, waveform ->
            val name = "رسالة صوتيّة ${voiceNameFmt.format(Date())}.m4a"
            sendAttachment(
                file = PickedFile(android.net.Uri.fromFile(file), name, file.length()),
                type = ChatMessageType.Voice,
                contentType = "audio/mp4",
                durationMs = durationMs,
                waveform = waveform,
                deleteAfter = file,
            )
        },
    )

    LaunchedEffect(pendingUpload) {
        val file = pendingUpload ?: return@LaunchedEffect
        pendingUpload = null
        val contentType = guessContentType(file.name)
        sendAttachment(file, chatTypeForMime(contentType), contentType)
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

    actionsFor?.let { msg ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionsFor = null },
            sheetState = sheetState,
            containerColor = ChatColors.surface,
            // الورقة خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة.
            contentColor = ChatColors.textPrimary,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (!msg.deleted) {
                    // الكبسولة نفسها المستعملة في المجموعة — لا صفّان
                    // مختلفان لشيء واحد.
                    QuickReactionsPill(
                        myReaction = msg.reactions[myUid],
                        onPick = { emoji ->
                            actionsFor = null
                            scope.launch {
                                DmRepository.setReaction(
                                    threadId,
                                    msg.id,
                                    if (msg.reactions[myUid] == emoji) null else emoji,
                                )
                            }
                        },
                        // لا لوحة إيموجي كاملة في الخاصّ بعد — ＋ يفتح
                        // السريعة نفسها، فلا يعِد بما لا يوجد.
                        onMore = { actionsFor = null },
                    )
                    HorizontalDivider()
                    DmSheetItem(Icons.AutoMirrored.Filled.Reply, "ردّ", ChatColors.accent) {
                        actionsFor = null
                        replyTo = msg.asRef()
                    }
                    DmSheetItem(Icons.Filled.Share, "إعادة توجيه", ChatColors.accent) {
                        actionsFor = null
                        forwarding = msg
                    }
                    if (msg.text.isNotBlank()) {
                        DmSheetItem(
                            Icons.Filled.ContentCopy,
                            "نسخ النصّ",
                            ChatColors.textMuted,
                        ) {
                            actionsFor = null
                            copyText(context, msg.text)
                            snack("نُسخ النصّ.")
                        }
                    }
                }
                DmSheetItem(Icons.Filled.Delete, "حذف عندي فقط", ChatColors.amber) {
                    actionsFor = null
                    scope.launch {
                        runCatching { DmRepository.deleteForMe(threadId, msg.id) }
                            .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                    }
                }
                if (msg.isMine && !msg.deleted) {
                    DmSheetItem(
                        Icons.Filled.DeleteForever,
                        "حذف عند الطرفين",
                        ChatColors.rose,
                        textColor = ChatColors.rose,
                    ) {
                        // كان يُنفَّذ بضغطة واحدة بلا تأكيد رغم أنّه يمحو ملفّ
                        // المرفق من التخزين نهائياً — والمجموعة تؤكّد الإجراء نفسه.
                        actionsFor = null
                        confirmDeleteAll = msg
                    }
                }
            }
        }
    }

    reactionsFor?.let { msg ->
        DmReactionsSheet(
            msg = msg,
            members = members,
            myUid = myUid,
            onDismiss = { reactionsFor = null },
            onRemoveMine = {
                reactionsFor = null
                scope.launch {
                    runCatching { DmRepository.setReaction(threadId, msg.id, null) }
                        .onFailure { snack("تعذّر التفاعل: ${it.arabicReason()}") }
                }
            },
        )
    }

    confirmDeleteAll?.let { msg ->
        ConfirmDialog(
            title = "حذف عند الطرفين",
            body = "ستُحذف هذه الرسالة عند الطرفين ولا يمكن التراجع. متابعة؟",
            confirmLabel = "حذف",
            confirmColor = ChatColors.rose,
            onDismiss = { confirmDeleteAll = null },
            onConfirm = {
                confirmDeleteAll = null
                scope.launch {
                    runCatching { DmRepository.deleteForEveryone(threadId, msg) }
                        .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                }
            },
        )
    }

    if (confirmClearChat) {
        ConfirmDialog(
            title = "مسح المحادثة عندي",
            body = "ستختفي كلّ رسائل هذه المحادثة من جهازك أنت فقط — يبقى كلّ " +
                "شيء كما هو عند الطرف الآخر. متابعة؟",
            confirmLabel = "مسح عندي",
            confirmColor = ChatColors.rose,
            onDismiss = { confirmClearChat = false },
            onConfirm = {
                confirmClearChat = false
                scope.launch {
                    runCatching { DmRepository.clearForMe(threadId) }
                        .onSuccess { snack("مُسحت المحادثة عندك ($it رسالة).") }
                        .onFailure { snack("تعذّر المسح: ${it.arabicReason()}") }
                }
            },
        )
    }

    forwarding?.let { msg ->
        ForwardPickerSheet(
            members = membersList,
            myUid = myUid,
            includeGroup = true,
            onDismiss = { forwarding = null },
            // النقل يمرّ بـChatUploader لأن نسخ المرفق رفعٌ طويل يجب أن يصمد
            // أمام التدوير والرجوع (وإلّا بقي ملفّ يتيم في التخزين بلا رسالة).
            onPickGroup = {
                forwarding = null
                ChatUploader.enqueueForward(ChatUploadTarget.Group, msg)
                snack("جارٍ إعادة التوجيه إلى المجموعة…")
            },
            onPickMember = { member ->
                forwarding = null
                val target = DmRepository.ensureThread(member.uid)
                ChatUploader.enqueueForward(
                    ChatUploadTarget.Dm(threadId = target, otherUid = member.uid),
                    msg,
                )
                snack("جارٍ إعادة التوجيه إلى ${member.displayName}…")
            },
        )
    }

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
            // ── الترويسة ──
            Surface(color = ChatColors.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    MemberAvatar(
                        uid = otherUid,
                        name = other?.displayName ?: otherName,
                        photo = other?.displayPhoto.orEmpty(),
                        radius = 20,
                        showOnline = true,
                        online = other?.isOnline == true,
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                other?.displayName ?: otherName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // مقاس رأس واتساب نفسه كالمجموعة (المواصفة ٦).
                                fontWeight = FontWeight.Medium,
                                fontSize = 17.sp,
                            )
                            if (other?.isOwner == true) {
                                Spacer(Modifier.size(5.dp))
                                Text("👑", fontSize = 12.sp)
                            }
                        }
                        val subtitle = when {
                            otherTyping -> "يكتب الآن…"
                            other?.isOnline == true -> "متصل الآن"
                            other?.lastSeenAtMs != null ->
                                "آخر ظهور: ${lastSeenFmt.format(Date(other.lastSeenAtMs))}"

                            else -> "محادثة خاصّة"
                        }
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            color = when {
                                otherTyping -> ChatColors.accent
                                other?.isOnline == true -> ChatColors.online
                                else -> ChatColors.textMuted
                            },
                        )
                    }
                    // 📞 مكالمة صوتيّة مع هذا المشرف (شاشة مستقلّة تدير
                    // الإذن والإشارات، فلا تتأثّر هذه الشاشة بشيء).
                    IconButton(
                        onClick = {
                            context.startActivity(
                                CallActivity.outgoingIntent(
                                    context = context,
                                    peerUid = otherUid,
                                    peerName = other?.displayName ?: otherName,
                                    peerPhoto = other?.displayPhoto.orEmpty(),
                                ),
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = "مكالمة صوتيّة",
                            tint = ChatColors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            searching = !searching
                            if (!searching) searchQuery = ""
                        },
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "بحث في الرسائل",
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "خيارات",
                                tint = ChatColors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.CleaningServices,
                                        contentDescription = null,
                                        tint = ChatColors.rose,
                                    )
                                },
                                text = { Text("مسح المحادثة عندي") },
                                onClick = {
                                    menuOpen = false
                                    confirmClearChat = true
                                },
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (searching) {
                Surface(color = ChatColors.surface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
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
            }

            // ── الرسائل ──
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty() && loadingFirstBatch) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = ChatColors.accent,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "جارٍ تحميل الرسائل…",
                                color = ChatColors.textMuted,
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = ChatColors.textMuted,
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "محادثة خاصّة مع $otherName",
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "لا يراها بقيّة المشرفين ولا المجموعة.",
                                fontSize = 12.sp,
                                color = ChatColors.textMuted,
                            )
                        }
                    }
                } else {
                    val otherRead = thread?.readAtMs?.get(otherUid) ?: 0L
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "لا نتائج لـ «$searchQuery» ضمن المحمَّل.",
                                color = ChatColors.textMuted,
                            )
                        }
                    }
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            vertical = 10.dp,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // ⚠️ مفتاح ثابت لكلّ رسالة: بلاه يربط Compose الحالةَ
                        // بالفهرس، فتقفز حالات الفقاعات (تشغيل الصوت، التنزيل،
                        // إزاحة السحب) إلى رسائل أخرى مع كلّ وصول رسالة جديدة.
                        items(shown.size, key = { shown[it].id }) { i ->
                            val msg = shown[i]
                            val older = shown.getOrNull(i + 1)
                            val showDateChip = older == null ||
                                !sameDay(older.createdAtMs, msg.createdAtMs)
                            val highlightColor by animateColorAsState(
                                if (highlightedId == msg.id) {
                                    ChatColors.highlight
                                } else {
                                    Color.Transparent
                                },
                                label = "highlight",
                            )
                            Column(Modifier.background(highlightColor)) {
                                if (showDateChip) DateChip(msg.createdAtMs)
                                SwipeToReplyRow(
                                    messageId = msg.id,
                                    enabled = !msg.deleted,
                                    onReply = { replyTo = msg.asRef() },
                                ) {
                                    MessageBubble(
                                        msg = msg,
                                        // محادثة ثنائيّة: لا حاجة لاسم المرسِل فوق كلّ سلسلة.
                                        showSenderHeader = false,
                                        members = members,
                                        readByAll = otherRead >= msg.sentAtMs,
                                        onLongPress = { actionsFor = it },
                                        onReplyTap = { jumpTo(it) },
                                        onReactionsTap = { reactionsFor = it },
                                        // أوّل استماع = ميكروفون أزرق عند المرسِل.
                                        onListened = {
                                            DmRepository.markListened(threadId, it.id)
                                        },
                                    )
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
            }

            val online by NetworkMonitor.online.collectAsState()
            if (!online) OfflineBanner()
            // شريط مستقلّ لكلّ عمليّة رفع في هذه المحادثة.
            myUploads.forEach { upload ->
                UploadBanner(upload.name, upload.percent) { ChatUploader.cancel(upload.id) }
            }
            quotedFromGroup?.let { quote ->
                // شريط الاقتباس من المجموعة عند «الردّ بشكل خاص» (نمط واتساب).
                Surface(color = ChatColors.highlight) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Groups,
                            contentDescription = null,
                            tint = ChatColors.accentDark,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ردّ خاصّ على رسالة ${quote.senderName} في المجموعة",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ChatColors.accentDark,
                            )
                            Text(
                                quote.preview.ifEmpty { chatTypeLabel(quote.type) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.5.sp,
                                color = ChatColors.textMuted,
                            )
                        }
                        IconButton(onClick = { quotedFromGroup = null }) {
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
            replyTo?.let { ReplyBanner(it) { replyTo = null } }
            ClipboardImageSuggestion(
                enabled = !voice.showsBar,
                onImage = { uri -> pendingUpload = context.pickedFileFrom(uri) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (voice.showsBar) {
                // مقفول أو معاينة فقط؛ أثناء الضغط المطوّل يبقى شريط الإدخال
                // كما هو (تغيير تخطيطه يزيح الزرّ فيُطلق القفل/الإلغاء زوراً).
                VoiceRecorderBar(voice)
            } else {
                InputBar(
                    text = text,
                    onTextChange = {
                        text = it
                        if (it.trim().isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastTypingSentMs > 3000) {
                                lastTypingSentMs = now
                                scope.launch { DmRepository.typingTick(threadId) }
                            }
                        }
                    },
                    sending = sending,
                    hint = "رسالة خاصّة…",
                    onAttach = { showAttachMenu = true },
                    onSend = {
                        val body = text.trim()
                        if (body.isEmpty() || sending) return@InputBar
                        sending = true
                        val reply = replyTo
                        val quoted = quotedFromGroup
                        text = ""
                        replyTo = null
                        quotedFromGroup = null
                        scope.launch {
                            try {
                                DmRepository.sendText(
                                    threadId = threadId,
                                    otherUid = otherUid,
                                    text = body,
                                    replyTo = reply,
                                    fromGroup = quoted,
                                )
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

/**
 * ↩️ سحب الردّ السريع بنمط واتساب — كان غائباً كلّياً في المحادثة الخاصّة.
 *
 * العتبة تُقاس على **الدلتا المتراكمة** لا على قفزة الإصبع الواحدة، والفقاعة
 * تنزلق بمقاومة [REPLY_SWIPE_RESISTANCE] بحدّ [REPLY_SWIPE_MAX] فلا تُفلت مع
 * الإصبع، مع اهتزاز **واحد** عند تجاوز العتبة وارتداد نابضيّ عند الإفلات.
 *
 * السحب أفقيّ فقط، فتمرير القائمة العموديّ يمرّ إلى `LazyColumn` كما هو.
 */
@Composable
private fun SwipeToReplyRow(
    messageId: String,
    enabled: Boolean,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { REPLY_SWIPE_THRESHOLD.toPx() }
    val maxPx = with(density) { REPLY_SWIPE_MAX.toPx() }
    val scope = rememberCoroutineScope()
    val slide = remember(messageId) { Animatable(0f) }
    var accumulated by remember(messageId) { mutableFloatStateOf(0f) }
    var passed by remember(messageId) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        val progress = (abs(slide.value) / thresholdPx).coerceIn(0f, 1f)
        if (progress > 0f) {
            // أيقونة الردّ تنمو خلف الفقاعة وتكتمل عند العتبة، على الجهة التي
            // يُسحب منها (فتصلح للاتّجاهين).
            Box(
                Modifier
                    .align(if (slide.value < 0f) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 12.dp)
                    .size((20 + 14 * progress).dp)
                    .background(
                        ChatColors.accent.copy(alpha = 0.06f + 0.18f * progress),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = ChatColors.accent.copy(alpha = 0.35f + 0.65f * progress),
                    modifier = Modifier.size((11 + 8 * progress).dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(slide.value.roundToInt(), 0) }
                .pointerInput(messageId, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulated = 0f
                            passed = false
                        },
                        onDragEnd = {
                            if (passed) onReply()
                            accumulated = 0f
                            passed = false
                            scope.launch { slide.animateTo(0f, spring(dampingRatio = 0.6f)) }
                        },
                        onDragCancel = {
                            accumulated = 0f
                            passed = false
                            scope.launch { slide.animateTo(0f, spring(dampingRatio = 0.6f)) }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount
                        scope.launch {
                            slide.snapTo(
                                (accumulated * REPLY_SWIPE_RESISTANCE).coerceIn(-maxPx, maxPx),
                            )
                        }
                        if (!passed && abs(accumulated) >= thresholdPx) {
                            // مرّة واحدة لكلّ سحبة: العلَم يُصفَّر عند بدء التالية.
                            passed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
        ) { content() }
    }
}

/**
 * 😀 مَن تفاعل بأيّ إيموجي — نظير ورقة المجموعة تماماً: تفاعلي وحده قابل
 * للإزالة بالنقر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DmReactionsSheet(
    msg: ChatMessage,
    members: Map<String, ChatMember>,
    myUid: String,
    onDismiss: () -> Unit,
    onRemoveMine: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatColors.surface,
        // الورقة خارج WhatsAppChatBackground فلا يصلها لون نصّ الدردشة.
        contentColor = ChatColors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                "التفاعلات",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
            HorizontalDivider()
            if (msg.reactions.isEmpty()) {
                Text(
                    "لا تفاعلات على هذه الرسالة بعد.",
                    color = ChatColors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
            msg.reactions.forEach { (uid, emoji) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = uid == myUid) { onRemoveMine() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(
                        uid = uid,
                        name = members[uid]?.displayName ?: "مشرف",
                        photo = members[uid]?.displayPhoto.orEmpty(),
                        radius = 17,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (uid == myUid) {
                                "${members[uid]?.displayName ?: "أنا"} (أنا)"
                            } else {
                                members[uid]?.displayName ?: "مشرف سابق"
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

@Composable
private fun DmSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: Color,
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
        Spacer(Modifier.size(16.dp))
        Text(title, color = textColor)
    }
}
