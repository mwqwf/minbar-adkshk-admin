package com.ali.ishaqiyin_admin.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ali.ishaqiyin_admin.call.CallEngine
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.DmThread
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.ui.AdminScaffold
import com.ali.ishaqiyin_admin.ui.ConfirmDialog
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.Routes
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hourFormat = SimpleDateFormat("HH:mm", Locale.US)
private val dateOnlyFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US)

/**
 * 💬 «المحادثات» — قائمة **واحدة** كواتساب تماماً: مجموعة الإدارة في
 * أعلاها ثم المحادثات الفرديّة، لكلّ منها آخر رسالة ووقتها وشارة غير
 * المقروء، وزرّ «محادثة جديدة» يفتح قائمة المشرفين.
 *
 * **لماذا وُحِّدت؟** كان للمجموعة بابٌ وللخاصّ بابٌ آخر في اللوحة، فمن
 * يراسل ثلاثة مشرفين لا يرى محادثاتهم إلا بعد أن يتذكّر أنّ لها قسماً
 * مستقلاً — وهو عكس ما اعتاده الناس في تطبيقات المراسلة.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsHomeScreen(nav: NavHostController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // 📞 كشف المكالمات الواردة داخل التطبيق — لا ينتظر إشعار FCM (يرجع
    // مبكّراً إن عُطّلت الإشعارات أو رُفض إذن `POST_NOTIFICATIONS`، ويتأخّر في
    // وضع Doze أو حين يُبطَل رمز الجهاز). المراقبة مستقلّة عن دورة حياة
    // الشاشة: تبقى عاملة بعد مغادرتها، وتُهمِل التكرار مع مسار FCM بحارسها.
    LaunchedEffect(Unit) { CallEngine.startIncomingWatch(context) }

    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val membersList by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    val members = remember(membersList) { membersList.associateBy { it.uid } }
    val threads by remember { DmRepository.threadsStream() }
        .collectAsState(initial = emptyList())
    // بطاقة المجموعة داخل القائمة نفسها: هويّتها وآخر رسالة فيها وعدد ما
    // لم يُقرأ — ثلاثة تدفّقات خفيفة (وثيقة، وثيقة، ٦٠ رسالة على الأكثر).
    val groupMeta by remember { ChatRepository.metaStream() }
        .collectAsState(initial = null)
    val groupLast by remember { ChatRepository.lastMessageStream() }
        .collectAsState(initial = null)
    val groupUnread by remember { ChatRepository.unreadCountStream() }
        .collectAsState(initial = 0)
    var showPicker by remember { mutableStateOf(false) }
    // ضغطة مطوّلة على محادثة ⇒ خيارات حذفها (نمط واتساب).
    var menuFor by remember { mutableStateOf<DmThread?>(null) }
    var confirmPurge by remember { mutableStateOf<DmThread?>(null) }
    val iAmOwner = members[myUid]?.isOwner == true

    fun nameOf(thread: DmThread): String =
        members[thread.otherOf(myUid)]?.displayName ?: "عضو"

    menuFor?.let { target ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = sheetState,
            containerColor = ChatColors.surface,
            contentColor = ChatColors.textPrimary,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Text(
                    "محادثة ${nameOf(target)}",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
                HorizontalDivider()
                ThreadMenuItem(
                    icon = Icons.Filled.Delete,
                    label = "حذف المحادثة عندي",
                    subtitle = "تختفي من قائمتي ولا يفقد الطرف الآخر شيئاً، " +
                        "وتعود إن راسلني ثانيةً.",
                    tint = ChatColors.amber,
                ) {
                    val id = target.id
                    menuFor = null
                    scope.launch {
                        runCatching { DmRepository.deleteThreadForMe(id) }
                            .onSuccess { snack("حُذفت المحادثة عندك.") }
                            .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                    }
                }
                if (iAmOwner) {
                    ThreadMenuItem(
                        icon = Icons.Filled.DeleteForever,
                        label = "حذف المحادثة ومحتواها عند الطرفين 👑",
                        subtitle = "محو نهائيّ للرسائل ومرفقاتها من الخادم — بلا تراجع.",
                        tint = ChatColors.rose,
                    ) {
                        confirmPurge = target
                        menuFor = null
                    }
                }
            }
        }
    }

    confirmPurge?.let { target ->
        ConfirmDialog(
            title = "محو محادثة ${nameOf(target)} نهائياً؟",
            body = "ستُمحى كلّ رسائل هذه المحادثة ومرفقاتها (صور وصوتيّات " +
                "وملفّات) من الخادم عند الطرفين معاً، بلا أيّ إمكانية " +
                "استرجاع. إن أردت إخفاءها عنك وحدك فاستعمل «حذف المحادثة عندي».",
            confirmLabel = "محو نهائيّ",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                val id = target.id
                confirmPurge = null
                scope.launch {
                    runCatching { DmRepository.deleteThreadForEveryone(id) }
                        .onSuccess { snack("مُحيت المحادثة ومحتواها ($it رسالة).") }
                        .onFailure { snack("تعذّر المحو: ${it.arabicReason()}") }
                }
            },
            onDismiss = { confirmPurge = null },
        )
    }

    fun openDm(member: ChatMember) {
        scope.launch {
            val threadId = DmRepository.ensureThread(member.uid)
            nav.navigate(Routes.dm(threadId, member.uid, member.displayName))
        }
    }

    if (showPicker) {
        val others = membersList.filter { it.uid != myUid }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState,
            containerColor = ChatColors.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    "اختر مشرفاً للمراسلة",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
                HorizontalDivider()
                if (others.isEmpty()) {
                    Text(
                        "لا يوجد مشرفون آخرون بعد.",
                        color = ChatColors.textMuted,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                others.forEach { member ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPicker = false
                                openDm(member)
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberAvatar(
                            uid = member.uid,
                            name = member.displayName,
                            photo = member.displayPhoto,
                            radius = 20,
                            showOnline = true,
                            online = member.isOnline,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    member.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (member.isOwner) {
                                    Spacer(Modifier.size(5.dp))
                                    Text("👑", fontSize = 12.sp)
                                }
                            }
                            Text(
                                if (member.isOnline) "متصل الآن" else member.email,
                                fontSize = 11.sp,
                                color = if (member.isOnline) {
                                    ChatColors.online
                                } else {
                                    ChatColors.textMuted
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    AdminScaffold(
        title = "المحادثات",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showPicker = true },
                containerColor = ChatColors.accentDark,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("محادثة جديدة")
            }
        },
    ) { padding ->
        val visible = threads.filter { it.lastAtMs > 0 }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp),
        ) {
            // 👥 المجموعة أوّلاً ودائماً — حتى قبل أوّل رسالة فيها.
            item {
                GroupTile(
                    name = groupMeta?.name?.takeIf { it.isNotBlank() } ?: "مجموعة الإدارة",
                    photo = groupMeta?.photoUrl.orEmpty(),
                    preview = groupLast?.let { previewOf(it, members) }
                        ?: "دردشة المالك والمشرفين",
                    timeMs = groupLast?.createdAtMs ?: 0L,
                    unread = groupUnread,
                    // singleTop: نقرتان سريعتان كانتا تكدّسان نسختين من
                    // شاشة المجموعة، فيحتاج الرجوع ضغطتين.
                    onClick = {
                        nav.navigate(
                            Routes.CHAT,
                            androidx.navigation.NavOptions.Builder()
                                .setLaunchSingleTop(true)
                                .build(),
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
            }
            if (visible.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Forum,
                            contentDescription = null,
                            tint = ChatColors.textMuted,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("لا محادثات خاصّة بعد", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "اضغط «محادثة جديدة» لمراسلة أيّ مشرف على حدة.\n" +
                                "ومن المجموعة: اضغط مطوّلاً على رسالة ثم «ردّ بشكل خاص».",
                            textAlign = TextAlign.Center,
                            fontSize = 12.5.sp,
                            color = ChatColors.textMuted,
                        )
                    }
                }
                return@LazyColumn
            }
            items(visible.size) { index ->
                ThreadTile(
                    thread = visible[index],
                    myUid = myUid,
                    other = members[visible[index].otherOf(myUid)],
                    onClick = {
                        val otherUid = visible[index].otherOf(myUid)
                        val name = members[otherUid]?.displayName ?: "عضو"
                        nav.navigate(Routes.dm(visible[index].id, otherUid, name))
                    },
                    onLongPress = { menuFor = visible[index] },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
            }
        }
    }
}

/// معاينة سطر واحد لآخر رسالة في المجموعة: «الاسم: النصّ» أو نوع المرفق.
private fun previewOf(
    msg: com.ali.ishaqiyin_admin.data.ChatMessage,
    members: Map<String, ChatMember>,
): String {
    val sender = members[msg.senderId]?.displayName ?: msg.senderName
    val body = when {
        msg.deleted -> "تم حذف هذه الرسالة"
        msg.text.isNotBlank() -> msg.text
        else -> com.ali.ishaqiyin_admin.data.chatTypeLabel(msg.type)
    }
    return if (sender.isBlank()) body else "$sender: $body"
}

/// بطاقة مجموعة الإدارة داخل قائمة المحادثات — بنية [ThreadTile] نفسها
/// (صورة، اسم، معاينة، وقت، شارة) كي يبدو الصفّان من عائلة واحدة.
@Composable
private fun GroupTile(
    name: String,
    photo: String,
    preview: String,
    timeMs: Long,
    unread: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(photo = photo, name = name, radius = 24)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.5.sp,
                fontWeight = if (unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold,
            )
            Text(
                preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.5.sp,
                color = if (unread > 0) ChatColors.accentDark else ChatColors.textMuted,
                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                timeLabel(timeMs),
                fontSize = 10.5.sp,
                color = if (unread > 0) ChatColors.accent else ChatColors.textMuted,
                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
            )
            if (unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .background(ChatColors.accent, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (unread > 99) "99+" else "$unread",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadTile(
    thread: DmThread,
    myUid: String,
    other: ChatMember?,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val unread = thread.hasUnreadFor(myUid)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(
            uid = thread.otherOf(myUid),
            name = other?.displayName ?: "عضو",
            photo = other?.displayPhoto.orEmpty(),
            radius = 24,
            showOnline = true,
            online = other?.isOnline == true,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    other?.displayName ?: "عضو سابق",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.5.sp,
                    fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold,
                )
                if (other?.isOwner == true) {
                    Spacer(Modifier.size(5.dp))
                    Text("👑", fontSize = 12.sp)
                }
            }
            Text(
                thread.previewFor(myUid),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.5.sp,
                color = if (unread) ChatColors.accentDark else ChatColors.textMuted,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                timeLabel(thread.lastAtMs),
                fontSize = 10.5.sp,
                color = if (unread) ChatColors.accent else ChatColors.textMuted,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
            )
            if (unread) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(10.dp).background(ChatColors.accent, CircleShape))
            }
        }
    }
}

/** سطر في ورقة خيارات المحادثة (أيقونة + عنوان + شرح سبب/أثر). */
@Composable
private fun ThreadMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = tint)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.5.sp, color = ChatColors.textMuted, lineHeight = 16.sp)
        }
    }
}

private fun timeLabel(ms: Long): String {
    if (ms <= 0) return ""
    val label = dateChipLabel(ms)
    return when (label) {
        "اليوم" -> hourFormat.format(Date(ms))
        "أمس" -> "أمس"
        else -> dateOnlyFormat.format(Date(ms))
    }
}
