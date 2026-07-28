package com.ali.ishaqiyin_admin.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.ChatGroupMeta
import com.ali.ishaqiyin_admin.data.ChatMediaStore
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.ONLINE_WINDOW_MS
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.ui.AdminScaffold
import com.ali.ishaqiyin_admin.ui.ConfirmDialog
import com.ali.ishaqiyin_admin.ui.EditTextDialog
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.RemoteImage
import com.ali.ishaqiyin_admin.ui.Routes
import com.ali.ishaqiyin_admin.util.pickedFileFrom
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val lastSeenFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * صفحة معلومات المجموعة (مثل واتساب): صورة المجموعة واسمها، قفل الإرسال،
 * صورتي الشخصيّة، إدارة الوسائط المنزَّلة، وقائمة الأعضاء مع الحضور المباشر.
 */
@Composable
fun GroupInfoScreen(isOwner: Boolean, nav: NavHostController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val meta by remember { ChatRepository.metaStream() }
        .collectAsState(initial = ChatGroupMeta.fallback)
    val members by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(ChatNotifications.isMuted) }
    var autoDownload by remember { mutableStateOf(AppPrefs.autoDownloadMedia) }
    var mediaBytes by remember { mutableLongStateOf(0L) }
    var mediaRefresh by remember { mutableIntStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var confirmClearMedia by remember { mutableStateOf(false) }
    var confirmClearChat by remember { mutableStateOf(false) }

    LaunchedEffect(mediaRefresh) { mediaBytes = ChatMediaStore.totalBytes() }

    val groupPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                ChatRepository.setGroupPhoto(uri, context.pickedFileFrom(uri).name)
            }
                .onSuccess { snack("تم تحديث صورة المجموعة.") }
                .onFailure { snack("فشل: ${it.message ?: it}") }
            busy = false
        }
    }

    val myPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { ChatRepository.setMyPhoto(uri, context.pickedFileFrom(uri).name) }
                .onSuccess { snack("تم تحديث صورتك الشخصيّة.") }
                .onFailure { snack("فشل: ${it.message ?: it}") }
            busy = false
        }
    }

    if (showProfile) ProfileDialog(firstRun = false, onDismiss = { showProfile = false })

    if (editingName) {
        EditTextDialog(
            title = "اسم المجموعة",
            initial = meta.name,
            hint = "اسم المجموعة…",
            onDismiss = { editingName = false },
            onSave = { newName ->
                editingName = false
                if (newName.isNotEmpty()) {
                    scope.launch {
                        runCatching { ChatRepository.setGroupName(newName) }
                            .onSuccess { snack("تم تحديث اسم المجموعة.") }
                            .onFailure { snack("فشل: ${it.message ?: it}") }
                    }
                }
            },
        )
    }

    if (confirmClearChat) {
        ConfirmDialog(
            title = "مسح المحادثة عندي",
            body = "ستختفي كلّ رسائل المجموعة من جهازك أنت فقط — يبقى كلّ شيء " +
                "كما هو عند بقيّة الأعضاء، ولا يُحذف أيّ ملفّ من الخادم. متابعة؟",
            confirmLabel = "مسح عندي",
            confirmColor = ChatColors.rose,
            onDismiss = { confirmClearChat = false },
            onConfirm = {
                confirmClearChat = false
                busy = true
                scope.launch {
                    runCatching { ChatRepository.clearForMe() }
                        .onSuccess { snack("مُسحت المحادثة عندك ($it رسالة).") }
                        .onFailure { snack("تعذّر المسح: ${it.message ?: it}") }
                    busy = false
                }
            },
        )
    }

    if (confirmClearMedia) {
        ConfirmDialog(
            title = "مسح الوسائط المنزَّلة",
            body = "ستُحذف نسخ الوسائط من هذا الجهاز فقط — تبقى الرسائل كما هي " +
                "ويمكن تنزيلها مجدّداً في أيّ وقت.",
            confirmLabel = "مسح",
            confirmColor = ChatColors.rose,
            onDismiss = { confirmClearMedia = false },
            onConfirm = {
                confirmClearMedia = false
                ChatMediaStore.clearAll()
                mediaRefresh++
                snack("مُسحت الوسائط المحفوظة.")
            },
        )
    }

    // «متصل الآن» نافذة زمنيّة: بلا نبضة ثانية تبقى معروضة حتى إعادة
    // التركيب التالية فلا تزول في وقتها.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val online = members.count { now - it.lastActiveAtMs < ONLINE_WINDOW_MS }
    val me = members.firstOrNull { it.uid == myUid }

    AdminScaffold(title = "معلومات المجموعة", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .background(
                                if (meta.photoUrl.isEmpty()) {
                                    Brush.horizontalGradient(
                                        listOf(ChatColors.accentDark, ChatColors.accent),
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color.Transparent),
                                    )
                                },
                                CircleShape,
                            )
                            .border(2.dp, ChatColors.border, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (meta.photoUrl.isEmpty()) {
                            Icon(
                                Icons.Filled.Groups,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        } else {
                            RemoteImage(meta.photoUrl, Modifier.size(96.dp).clip(CircleShape))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(meta.name, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${members.size} عضو • $online متصل الآن" +
                            if (meta.locked) " • 🔒 مقفلة" else "",
                        fontSize = 12.sp,
                        color = ChatColors.textMuted,
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }

            item {
                SectionCard {
                    // صورة المجموعة واسمها: متاحان لكلّ المشرفين (نمط واتساب).
                    InfoTile(
                        Icons.Filled.PhotoCamera,
                        "تغيير صورة المجموعة",
                        "متاح لكلّ المشرفين",
                        enabled = !busy,
                    ) { groupPhotoPicker.launch("image/*") }
                    if (meta.photoUrl.isNotEmpty()) {
                        InfoTile(
                            Icons.Filled.HideImage,
                            "إزالة صورة المجموعة",
                            null,
                            tint = ChatColors.textMuted,
                            enabled = !busy,
                        ) {
                            scope.launch {
                                runCatching { ChatRepository.clearGroupPhoto() }
                                    .onSuccess { snack("أُزيلت صورة المجموعة.") }
                            }
                        }
                    }
                    InfoTile(
                        Icons.Filled.Edit,
                        "تغيير اسم المجموعة",
                        "متاح لكلّ المشرفين",
                        enabled = !busy,
                    ) { editingName = true }
                    if (isOwner) {
                        SwitchTile(
                            icon = if (meta.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            title = "قفل المجموعة",
                            subtitle = "عند القفل لا يرسل أحد غير المالك (وضع الإعلانات)",
                            checked = meta.locked,
                            tint = if (meta.locked) ChatColors.rose else ChatColors.accent,
                            enabled = !busy,
                        ) { value ->
                            scope.launch {
                                runCatching { ChatRepository.setLocked(value) }
                                    .onSuccess {
                                        snack(if (value) "قُفلت المجموعة." else "فُتحت المجموعة.")
                                    }
                            }
                        }
                        meta.pinned?.let { pinned ->
                            InfoTile(
                                Icons.Filled.PushPin,
                                "إلغاء تثبيت الرسالة",
                                pinned.preview,
                                tint = ChatColors.amber,
                                enabled = !busy,
                            ) {
                                scope.launch {
                                    runCatching { ChatRepository.setPinned(null) }
                                        .onSuccess { snack("أُلغي التثبيت.") }
                                }
                            }
                        }
                    }
                    SwitchTile(
                        icon = if (muted) {
                            Icons.Filled.NotificationsOff
                        } else {
                            Icons.Filled.Notifications
                        },
                        title = "إشعارات المجموعة",
                        subtitle = "تنبيه عند وصول رسائل جديدة",
                        checked = !muted,
                        tint = if (muted) ChatColors.textMuted else ChatColors.accent,
                    ) { value ->
                        muted = !value
                        scope.launch { ChatNotifications.setMuted(!value) }
                    }
                    InfoTile(
                        Icons.Outlined.Badge,
                        "ملفّي الشخصي (الاسم والصورة)",
                        me?.let { "أظهر باسم: ${it.displayName}" }
                            ?: "اختر اسمك وصورتك في المجموعة",
                        enabled = !busy,
                    ) { showProfile = true }
                    InfoTile(
                        Icons.Filled.AccountCircle,
                        "تغيير صورتي الشخصيّة فقط",
                        "تظهر تلقائيّاً مع اسمك في الدردشة",
                        enabled = !busy,
                    ) { myPhotoPicker.launch("image/*") }
                    if (!me?.customPhoto.isNullOrEmpty()) {
                        InfoTile(
                            Icons.Filled.NoPhotography,
                            "إزالة صورتي المخصّصة",
                            "العودة إلى صورة Google",
                            tint = ChatColors.textMuted,
                            enabled = !busy,
                        ) {
                            scope.launch {
                                runCatching { ChatRepository.clearMyPhoto() }
                                    .onSuccess { snack("أُزيلت الصورة.") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // إدارة الوسائط المنزَّلة (نمط واتساب: التخزين والبيانات).
            item {
                SectionCard {
                    SwitchTile(
                        icon = Icons.Filled.DownloadForOffline,
                        title = "التنزيل التلقائي للوسائط الصغيرة",
                        subtitle = "الرسائل الصوتيّة والصور الصغيرة (أقلّ من 3 م.ب) " +
                            "تُنزَّل تلقائيّاً لتُفتح فوراً وبلا إنترنت",
                        checked = autoDownload,
                    ) { value ->
                        autoDownload = value
                        AppPrefs.autoDownloadMedia = value
                    }
                    InfoTile(
                        Icons.Filled.CleaningServices,
                        "مسح المحادثة عندي",
                        "تختفي الرسائل من جهازك وحدك — لا تتأثّر نسخة الآخرين",
                        tint = ChatColors.rose,
                        enabled = !busy,
                    ) { confirmClearChat = true }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SdStorage,
                            contentDescription = null,
                            tint = ChatColors.accent,
                        )
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("وسائط محفوظة على الجهاز")
                            Text(
                                if (mediaBytes > 0) {
                                    "${formatBytes(mediaBytes)} — تُشغَّل بلا إنترنت"
                                } else {
                                    "لا وسائط منزَّلة بعد"
                                },
                                fontSize = 11.sp,
                                color = ChatColors.textMuted,
                            )
                        }
                        if (mediaBytes > 0) {
                            TextButton(onClick = { confirmClearMedia = true }, enabled = !busy) {
                                Text("مسح", color = ChatColors.rose)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "الأعضاء (${members.size}) — $online متصل الآن",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            item {
                SectionCard {
                    members.forEach { member ->
                        MemberTile(
                            member = member,
                            isOwner = isOwner,
                            myUid = myUid,
                            now = now,
                            onOpenDm = {
                                scope.launch {
                                    val threadId = DmRepository.ensureThread(member.uid)
                                    nav.navigate(
                                        Routes.dm(threadId, member.uid, member.displayName),
                                    )
                                }
                            },
                            onSetModerator = { moderator ->
                                scope.launch {
                                    runCatching {
                                        ChatRepository.setChatRole(
                                            member.uid,
                                            if (moderator) "moderator" else null,
                                        )
                                    }.onSuccess {
                                        snack(
                                            if (moderator) {
                                                "صار ${member.displayName} مشرفاً للمجموعة ⭐"
                                            } else {
                                                "أُزيل إشراف المجموعة عن ${member.displayName}"
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (isOwner) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "العضويّة تُدار تلقائيّاً: كلّ من يدخل تطبيق الإدارة ينضمّ، " +
                            "ومن يُزال أو يُحظر من «الحساب والمشرفون» يخرج فوراً.",
                        fontSize = 11.sp,
                        color = ChatColors.textMuted,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun InfoTile(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = ChatColors.accent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = ChatColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SwitchTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    tint: Color = ChatColors.accent,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, fontSize = 11.sp, color = ChatColors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun MemberTile(
    member: ChatMember,
    isOwner: Boolean,
    myUid: String,
    now: Long,
    onOpenDm: () -> Unit,
    onSetModerator: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // المالك يعيّن/يزيل مشرفي المجموعة ⭐ (صلاحيّات داخل الدردشة فقط).
    val canManage = isOwner && member.uid != myUid && !member.isOwner
    val lastSeen = member.lastSeenAtMs?.let { lastSeenFormat.format(Date(it)) }.orEmpty()
    // يُحسب من نبضة الثانية بدل الخاصيّة الجامدة وقت التركيب.
    val memberOnline = now - member.lastActiveAtMs < ONLINE_WINDOW_MS

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = member.uid != myUid, onClick = onOpenDm)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(
            uid = member.uid,
            name = member.displayName,
            photo = member.displayPhoto,
            radius = 20,
            showOnline = true,
            online = memberOnline,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
                if (member.uid == myUid) {
                    Spacer(Modifier.size(6.dp))
                    Text("(أنا)", fontSize = 11.sp, color = ChatColors.textMuted)
                }
                if (member.isOwner) {
                    RoleBadge("👑 المالك", ChatColors.amber)
                } else if (member.isChatModerator) {
                    RoleBadge("⭐ مشرف المجموعة", ChatColors.accent)
                }
            }
            Text(
                if (memberOnline) {
                    "متصل الآن"
                } else if (lastSeen.isEmpty()) {
                    member.email
                } else {
                    "آخر ظهور: $lastSeen"
                },
                fontSize = 11.sp,
                color = if (memberOnline) ChatColors.online else ChatColors.textMuted,
            )
        }
        // مراسلة خاصّة — متاحة لأيّ عضو (عدا نفسي).
        if (member.uid != myUid) {
            IconButton(onClick = onOpenDm) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = "مراسلة خاصّة",
                    tint = ChatColors.accent,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!member.isChatModerator) {
                        DropdownMenuItem(
                            text = { Text("تعيين مشرفاً للمجموعة ⭐") },
                            onClick = {
                                menuOpen = false
                                onSetModerator(true)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("إزالة إشراف المجموعة") },
                            onClick = {
                                menuOpen = false
                                onSetModerator(false)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(label: String, color: Color) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 10.5.sp, color = color)
    }
}
