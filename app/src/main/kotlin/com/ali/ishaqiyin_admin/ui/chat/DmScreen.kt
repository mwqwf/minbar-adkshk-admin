package com.ali.ishaqiyin_admin.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.ChatMessage
import com.ali.ishaqiyin_admin.data.ChatMessageType
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatReplyRef
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.QUICK_REACTIONS
import com.ali.ishaqiyin_admin.data.chatTypeForMime
import com.ali.ishaqiyin_admin.data.chatTypeLabel
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.ui.ConfirmDialog
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.adminFieldColors
import com.ali.ishaqiyin_admin.util.AudioRecorderController
import com.ali.ishaqiyin_admin.util.ImageCompressor
import com.ali.ishaqiyin_admin.util.PickedFile
import com.ali.ishaqiyin_admin.util.pickedFileFrom
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val lastSeenFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private val voiceNameFmt = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)

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
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<ChatReplyRef?>(null) }
    // اقتباس رسالة المجموعة عند «الردّ بشكل خاص».
    var quotedFromGroup by remember { mutableStateOf(PendingGroupQuote.value) }

    var uploading by remember { mutableStateOf(false) }
    var uploadPct by remember { mutableDoubleStateOf(0.0) }
    var uploadName by remember { mutableStateOf("") }
    var uploadAborted by remember { mutableStateOf(false) }

    val recorder = remember { AudioRecorderController() }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var lastTypingSentMs by remember { mutableLongStateOf(0L) }
    var lastReadMarkMs by remember { mutableLongStateOf(0L) }

    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<PickedFile?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearChat by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val membersList by ChatRepository.membersStream().collectAsState(initial = emptyList())
    val members = remember(membersList) { membersList.associateBy { it.uid } }
    val other = members[otherUid]
    val messages by remember(threadId, limit) {
        DmRepository.messagesStream(threadId, limit.toLong())
    }.collectAsState(initial = emptyList())
    val thread by remember(threadId) { DmRepository.threadStream(threadId) }
        .collectAsState(initial = null)
    val otherTyping by remember(threadId, otherUid) {
        DmRepository.otherTypingStream(threadId, otherUid)
    }.collectAsState(initial = false)

    // نهاية التمرير (أقدم الرسائل) → وسّع النافذة المحمَّلة.
    LaunchedEffect(listState, messages.size) {
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastIndex ->
            if (messages.isNotEmpty() && lastIndex >= messages.size - 5 && limit < 2000) {
                limit += 60
            }
        }
    }

    DisposableEffect(threadId) {
        ChatNotifications.openDmThreadId = threadId
        PendingGroupQuote.value = null
        onDispose {
            ChatNotifications.openDmThreadId = ""
            recorder.release()
            SharedAudioPlayer.stop()
        }
    }

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

    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            recordSeconds += 1
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) pendingUpload = context.pickedFileFrom(uri) }

    fun sendAttachment(
        file: PickedFile,
        type: ChatMessageType,
        contentType: String,
        durationMs: Long? = null,
        deleteAfter: File? = null,
    ) {
        val reply = replyTo
        replyTo = null
        uploading = true
        uploadPct = 0.0
        uploadName = file.name
        uploadAborted = false
        scope.launch {
            var temp: java.io.File? = null
            try {
                // ضغط الصور قبل الرفع (توفير كبير على الشبكات الضعيفة).
                val prepared = ImageCompressor.prepare(context, file, contentType)
                temp = prepared.temp
                val payload = prepared.file
                DmRepository.sendAttachment(
                    threadId = threadId,
                    otherUid = otherUid,
                    uri = payload.uri,
                    filename = payload.name,
                    contentType = contentType,
                    type = type,
                    durationMs = durationMs,
                    replyTo = reply,
                    onProgress = { uploadPct = it },
                    isAborted = { uploadAborted },
                )
            } catch (e: Exception) {
                if (!uploadAborted) snack("فشل رفع \"${file.name}\": ${e.message ?: e}")
            } finally {
                runCatching { deleteAfter?.delete() }
                runCatching { temp?.delete() }
                uploading = false
            }
        }
    }

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
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (!msg.deleted) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        QUICK_REACTIONS.forEach { emoji ->
                            Box(
                                Modifier
                                    .background(
                                        if (msg.reactions[myUid] == emoji) {
                                            ChatColors.highlight
                                        } else {
                                            Color.Transparent
                                        },
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .clickable {
                                        actionsFor = null
                                        scope.launch {
                                            DmRepository.setReaction(
                                                threadId,
                                                msg.id,
                                                if (msg.reactions[myUid] == emoji) null else emoji,
                                            )
                                        }
                                    }
                                    .padding(7.dp),
                            ) { Text(emoji, fontSize = 26.sp) }
                        }
                    }
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
                    scope.launch { runCatching { DmRepository.deleteForMe(threadId, msg.id) } }
                }
                if (msg.isMine && !msg.deleted) {
                    DmSheetItem(
                        Icons.Filled.DeleteForever,
                        "حذف عند الطرفين",
                        ChatColors.rose,
                        textColor = ChatColors.rose,
                    ) {
                        actionsFor = null
                        scope.launch {
                            runCatching { DmRepository.deleteForEveryone(threadId, msg) }
                        }
                    }
                }
            }
        }
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
                        .onFailure { snack("تعذّر المسح: ${it.message ?: it}") }
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
            onPickGroup = {
                forwarding = null
                scope.launch {
                    runCatching { ChatRepository.forward(msg) }
                        .onSuccess { snack("أُعيد توجيه الرسالة إلى المجموعة.") }
                        .onFailure { snack("تعذّرت إعادة التوجيه: ${it.message ?: it}") }
                }
            },
            onPickMember = { member ->
                forwarding = null
                scope.launch {
                    runCatching {
                        val target = DmRepository.ensureThread(member.uid)
                        DmRepository.forward(target, member.uid, msg)
                    }
                        .onSuccess { snack("أُعيد توجيه الرسالة إلى ${member.displayName}.") }
                        .onFailure { snack("تعذّرت إعادة التوجيه: ${it.message ?: it}") }
                }
            },
        )
    }

    Surface(color = ChatColors.bg, modifier = Modifier.fillMaxSize()) {
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
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
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
                            fontSize = 11.sp,
                            color = when {
                                otherTyping -> ChatColors.accent
                                other?.isOnline == true -> ChatColors.online
                                else -> ChatColors.textMuted
                            },
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
                if (messages.isEmpty()) {
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
                    val query = searchQuery.trim().lowercase()
                    val shown = if (query.isEmpty()) {
                        messages
                    } else {
                        messages.filter {
                            it.text.lowercase().contains(query) ||
                                it.attachment?.name?.lowercase()?.contains(query) == true
                        }
                    }
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
                        items(shown.size) { i ->
                            val msg = shown[i]
                            val older = shown.getOrNull(i + 1)
                            val showDateChip = older == null ||
                                !sameDay(older.createdAtMs, msg.createdAtMs)
                            Column {
                                if (showDateChip) DateChip(msg.createdAtMs)
                                MessageBubble(
                                    msg = msg,
                                    // محادثة ثنائيّة: لا حاجة لاسم المرسِل فوق كلّ سلسلة.
                                    showSenderHeader = false,
                                    members = members,
                                    readByAll = otherRead >= msg.sentAtMs,
                                    onLongPress = { actionsFor = it },
                                    onReplyTap = {},
                                    onReactionsTap = {},
                                )
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
            if (uploading) {
                UploadBanner(uploadName, uploadPct) { uploadAborted = true }
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
            if (recording) {
                RecordingBar(
                    seconds = recordSeconds,
                    onCancel = {
                        recorder.cancel()
                        recording = false
                        recordSeconds = 0
                    },
                    onSend = {
                        val elapsed = recordSeconds
                        val file = recorder.stop() ?: recordFile
                        recording = false
                        recordSeconds = 0
                        if (file == null || elapsed < 1) {
                            snack("التسجيل قصير جدّاً.")
                        } else {
                            val name = "رسالة صوتيّة ${voiceNameFmt.format(Date())}.m4a"
                            sendAttachment(
                                PickedFile(
                                    android.net.Uri.fromFile(file),
                                    name,
                                    file.length(),
                                ),
                                ChatMessageType.Voice,
                                "audio/mp4",
                                durationMs = elapsed * 1000L,
                                deleteAfter = file,
                            )
                        }
                    },
                )
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
                    uploading = uploading,
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
                                snack("تعذّر الإرسال: ${e.message ?: e}")
                            }
                            sending = false
                        }
                    },
                    onRecord = {
                        runCatching {
                            recordFile = recorder.start(context, "dm_voice")
                            recording = true
                            recordSeconds = 0
                        }.onFailure { snack("تعذّر بدء التسجيل: ${it.message ?: it}") }
                    },
                )
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
