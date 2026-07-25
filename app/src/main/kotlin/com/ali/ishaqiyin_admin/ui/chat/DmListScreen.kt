package com.ali.ishaqiyin_admin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.DmThread
import com.ali.ishaqiyin_admin.ui.AdminScaffold
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
 * 💬 «الرسائل الخاصّة» — قائمة المحادثات الفرديّة بين المشرفين (نمط واتساب:
 * آخر رسالة، وقتها، وشارة غير المقروء)، مع زرّ لبدء محادثة مع أيّ عضو.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmListScreen(nav: NavHostController, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val membersList by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    val members = remember(membersList) { membersList.associateBy { it.uid } }
    val threads by remember { DmRepository.threadsStream() }
        .collectAsState(initial = emptyList())
    var showPicker by remember { mutableStateOf(false) }

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
        title = "الرسائل الخاصّة",
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
        if (visible.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Forum,
                        contentDescription = null,
                        tint = ChatColors.textMuted,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("لا محادثات خاصّة بعد", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "تستطيع مراسلة أيّ مشرف على حدة — بعيداً عن المجموعة.\n" +
                            "ومن المجموعة: اضغط مطوّلاً على رسالة ثم «ردّ بشكل خاص».",
                        textAlign = TextAlign.Center,
                        fontSize = 12.5.sp,
                        color = ChatColors.textMuted,
                    )
                }
            }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp),
        ) {
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
                )
                HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
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
) {
    val unread = thread.hasUnreadFor(myUid)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

private fun timeLabel(ms: Long): String {
    if (ms <= 0) return ""
    val label = dateChipLabel(ms)
    return when (label) {
        "اليوم" -> hourFormat.format(Date(ms))
        "أمس" -> "أمس"
        else -> dateOnlyFormat.format(Date(ms))
    }
}
