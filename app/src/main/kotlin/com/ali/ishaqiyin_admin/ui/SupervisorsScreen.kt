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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DashAdmin
import kotlinx.coroutines.launch

private sealed interface SupervisorAction {
    data class Block(val admin: DashAdmin, val mode: String) : SupervisorAction
    data class Remove(val admin: DashAdmin) : SupervisorAction
}

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
            error = "تعذّر جلب المشرفين: ${e.message ?: e}"
        }
        loading = false
    }

    val myEmail = AuthService.currentUser?.email.orEmpty().lowercase()
    fun canActOn(a: DashAdmin): Boolean = !a.isOwner && a.email != myEmail

    when (val action = pending) {
        is SupervisorAction.Block -> ConfirmDialog(
            title = if (action.mode == "permanent") "حظر نهائي" else "حظر مؤقّت",
            body = if (action.mode == "permanent") {
                "سيتم حظر \"${action.admin.email}\" بشكل نهائي. يمكنك إلغاء الحظر لاحقاً إن رغبت."
            } else {
                "سيتم تعليق وصول \"${action.admin.email}\" فوراً. يمكنك إلغاء الحظر لاحقاً."
            },
            confirmLabel = "تأكيد",
            confirmColor = kDanger,
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
                        snack("تعذّر التعديل: ${e.message ?: e}")
                    }
                    busyEmail = null
                    reload++
                }
            },
        )

        is SupervisorAction.Remove -> ConfirmDialog(
            title = "حذف نهائي",
            body = "سيتم حذف \"${action.admin.email}\" من قائمة المشرفين نهائيّاً. " +
                "لا يمكن التراجع إلّا بإعادة اعتماده عبر رمز جديد.",
            confirmLabel = "حذف",
            confirmColor = kDanger,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                busyEmail = action.admin.email
                scope.launch {
                    try {
                        AdminRepository.removeDashAdmin(action.admin.email)
                        ChatRepository.removeMemberByEmail(action.admin.email)
                    } catch (e: Exception) {
                        snack("تعذّر الحذف: ${e.message ?: e}")
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
            ) { Text(error!!, color = kDanger, textAlign = TextAlign.Center) }

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
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RemoteAvatar(
                                    url = a.photoURL,
                                    fallbackText = a.displayName.ifEmpty { a.email },
                                    size = 40,
                                    background = if (a.blocked) kDanger else kTeal,
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
                                    Text(a.email, fontSize = 12.sp, color = kMuted)
                                }
                                if (a.isOwner) {
                                    Box(
                                        Modifier
                                            .background(
                                                Color(0xFFFFC107).copy(alpha = 0.15f),
                                                RoundedCornerShape(20.dp),
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            "المالك",
                                            fontSize = 11.sp,
                                            color = Color(0xFFB8860B),
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
                                        color = if (a.blocked) kDanger else kGreen,
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
                                                        snack("تعذّر التعديل: ${it.message ?: it}")
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
                                        ) { Text("حظر نهائي", color = kDanger) }
                                    }
                                    OutlinedButton(
                                        onClick = { pending = SupervisorAction.Remove(a) },
                                        enabled = !busy,
                                    ) {
                                        if (busy) {
                                            Spin(color = kDanger, size = 14)
                                        } else {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = null,
                                                tint = kDanger,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        Spacer(Modifier.size(4.dp))
                                        Text("حذف", color = kDanger)
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
