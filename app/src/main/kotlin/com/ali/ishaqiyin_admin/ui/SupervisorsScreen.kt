package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DashAdmin
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

private sealed interface SupervisorAction {
    data class Block(val admin: DashAdmin, val mode: String) : SupervisorAction
    data class Remove(val admin: DashAdmin) : SupervisorAction
}

/**
 * الحظر والحذف كلاهما يستدعي `ChatRepository.removeMemberByEmail` — وهو حذف
 * نهائيّ لوثيقة العضويّة لا استعادة له: إلغاء الحظر يعيد الوصول فقط، بينما
 * الاسم والصورة ورتبة «مشرف المجموعة» تُبنى من جديد عند أوّل دخول للدردشة.
 */
private const val CHAT_MEMBERSHIP_NOTE =
    "كما تُزال عضويّته من مجموعة الإدارة فوراً، ولا يعود بعد إلغاء الحظر " +
        "اسمه ولا صورته ولا رتبته «مشرف المجموعة» تلقائياً."

private const val CHAT_MEMBERSHIP_NOTE_REMOVE =
    "كما تُزال عضويّته من مجموعة الإدارة نهائياً باسمه وصورته ورتبته " +
        "«مشرف المجموعة»."

/**
 * إدارة المشرفين (المالك فقط) — مطابقة لصفحة المشرفين في نبراس: قائمة بكل
 * الحسابات المصرَّح لها (المالك + المشرفون)، مع حظر مؤقّت/نهائي/إلغاء
 * حظر/حذف. لا توجد إضافة يدوية بكتابة بريد — المشرف الجديد يُعتمَد فقط عبر
 * رمز الاعتماد. لا يمكن التعديل على المالك ولا على حسابك أنت.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupervisorsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var admins by remember { mutableStateOf<List<DashAdmin>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyEmail by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<SupervisorAction?>(null) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            admins = AdminRepository.fetchDashAdmins()
        } catch (e: Exception) {
            error = "تعذّر جلب المشرفين: ${e.arabicReason()}"
        }
        loading = false
    }

    val myEmail = AuthService.currentUser?.email.orEmpty().lowercase()
    fun canActOn(a: DashAdmin): Boolean = !a.isOwner && a.email != myEmail

    val scheme = MaterialTheme.colorScheme
    val dark = isAdminDarkTheme()

    when (val action = pending) {
        is SupervisorAction.Block -> ConfirmDialog(
            title = if (action.mode == "permanent") "حظر نهائي" else "حظر مؤقّت",
            body = if (action.mode == "permanent") {
                "سيتم حظر \"${action.admin.email}\" بشكل نهائي. " +
                    "يمكنك إلغاء الحظر لاحقاً إن رغبت.\n\n" + CHAT_MEMBERSHIP_NOTE
            } else {
                "سيتم تعليق وصول \"${action.admin.email}\" فوراً. " +
                    "يمكنك إلغاء الحظر لاحقاً.\n\n" + CHAT_MEMBERSHIP_NOTE
            },
            confirmLabel = "تأكيد",
            confirmColor = scheme.error,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                busyEmail = action.admin.email
                scope.launch {
                    try {
                        AdminRepository.setDashAdminBlocked(
                            action.admin.email,
                            true,
                            mode = action.mode,
                        )
                        ChatRepository.removeMemberByEmail(action.admin.email)
                    } catch (e: Exception) {
                        snack("تعذّر التعديل: ${e.arabicReason()}")
                    }
                    busyEmail = null
                    reload++
                }
            },
        )

        is SupervisorAction.Remove -> ConfirmDialog(
            title = "حذف نهائي",
            body = "سيتم حذف \"${action.admin.email}\" من قائمة المشرفين نهائيّاً. " +
                "لا يمكن التراجع إلّا بإعادة اعتماده عبر رمز جديد.\n\n" +
                CHAT_MEMBERSHIP_NOTE_REMOVE,
            confirmLabel = "حذف",
            confirmColor = scheme.error,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                busyEmail = action.admin.email
                scope.launch {
                    try {
                        AdminRepository.removeDashAdmin(action.admin.email)
                        ChatRepository.removeMemberByEmail(action.admin.email)
                    } catch (e: Exception) {
                        snack("تعذّر الحذف: ${e.arabicReason()}")
                    }
                    busyEmail = null
                    reload++
                }
            },
        )

        null -> Unit
    }

    AdminScaffold(
        title = "إدارة المشرفين",
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (!loading) reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        when {
            loading -> FullScreenLoader()
            error != null -> Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(error!!, color = scheme.error, textAlign = TextAlign.Center) }

            admins.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "لا يوجد مشرفون بعد.\n" +
                        "يُعتمَد المشرف عبر رمز اعتماد (انظر «الحساب والمشرفون»).",
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(admins.size) { index ->
                    val a = admins[index]
                    val locked = !canActOn(a)
                    val busy = busyEmail == a.email
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RemoteAvatar(
                                    url = a.photoURL,
                                    fallbackText = a.displayName.ifEmpty { a.email },
                                    size = 40,
                                    // لونا الدور يتبعان السمة، وحبر الأيقونة
                                    // يُشتقّ منهما داخل RemoteAvatar.
                                    background = if (a.blocked) scheme.error else scheme.primary,
                                    fallbackIcon = if (a.blocked) {
                                        Icons.Filled.Block
                                    } else {
                                        Icons.Filled.Person
                                    },
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.displayName.ifEmpty { a.email },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(a.email, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                                }
                                if (a.isOwner) {
                                    Box(
                                        Modifier
                                            .background(
                                                Gold300.copy(alpha = if (dark) 0.20f else 0.18f),
                                                RoundedCornerShape(20.dp),
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            "المالك",
                                            fontSize = 11.sp,
                                            // كان #B8860B بتباين 3.01 على الشارة — راسب.
                                            // الحبر الذهبي الداكن يعطي 9.15 على الفاتح.
                                            color = if (dark) Gold300 else kOwnerBadge,
                                        )
                                    }
                                } else {
                                    Text(
                                        if (a.blocked) {
                                            "محظور" +
                                                if (a.blockMode == "permanent") " (نهائي)" else ""
                                        } else {
                                            "نشِط"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (a.blocked) scheme.error else adminGreen,
                                    )
                                }
                            }
                            if (!locked) {
                                Spacer(Modifier.size(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (a.blocked) {
                                        OutlinedButton(
                                            onClick = {
                                                busyEmail = a.email
                                                scope.launch {
                                                    runCatching {
                                                        AdminRepository.setDashAdminBlocked(
                                                            a.email,
                                                            false,
                                                        )
                                                    }.onFailure {
                                                        snack("تعذّر التعديل: ${it.arabicReason()}")
                                                    }
                                                    busyEmail = null
                                                    reload++
                                                }
                                            },
                                            enabled = !busy,
                                        ) { Text("إلغاء الحظر") }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                pending = SupervisorAction.Block(a, "temporary")
                                            },
                                            enabled = !busy,
                                        ) { Text("حظر مؤقّت") }
                                        OutlinedButton(
                                            onClick = {
                                                pending = SupervisorAction.Block(a, "permanent")
                                            },
                                            enabled = !busy,
                                        ) { Text("حظر نهائي", color = scheme.error) }
                                    }
                                    OutlinedButton(
                                        onClick = { pending = SupervisorAction.Remove(a) },
                                        enabled = !busy,
                                    ) {
                                        if (busy) {
                                            Spin(color = scheme.error, size = 14)
                                        } else {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = null,
                                                tint = scheme.error,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        Spacer(Modifier.size(4.dp))
                                        Text("حذف", color = scheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
