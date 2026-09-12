package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.DashAdmin
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ما ينتظر تأكيد المالك قبل أن يقع. */
private sealed interface SupervisorAction {
    data class Block(val admin: DashAdmin) : SupervisorAction
    data class Remove(val admin: DashAdmin) : SupervisorAction
}

/**
 * 👥 المشرفون — قائمة واحدة لكل الحسابات من `GET /admin/admins`:
 * الاسم أو البريد، الرتبة، نقطة الحضور («متّصل» أو «آخر ظهور منذ …»)،
 * تاريخ الانضمام، وللمطرودين «غادر في …» باهتاً.
 *
 * يراها كل المشرفين. أما الأفعال (حظر/إلغاء حظر، تبديل الرتبة، طرد) فللمالك
 * وحده — تُخفى عن غيره لا تُعطَّل — والخادم يرفض غير المالك أصلاً (403).
 * لا «حظر مؤقّت» و«حظر نهائي»: حظر واحد يُلغى متى شاء المالك. ولا مجموعات.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupervisorsScreen(isOwner: Boolean, onBack: () -> Unit) {
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

    val myEmail = AuthService.currentUser?.email.orEmpty().trim().lowercase()
    fun canActOn(a: DashAdmin): Boolean =
        isOwner && !a.isOwner && !a.removed && a.email.trim().lowercase() != myEmail

    fun run(email: String, block: suspend () -> Unit, failure: String) {
        busyEmail = email
        scope.launch {
            runCatching { block() }.onFailure { snack("$failure: ${it.arabicReason()}") }
            busyEmail = null
            reload++
        }
    }

    // 🔗 دعوة مشرف برمز (المالك): بريد واسم ⇒ رمز 24 ساعة.
    var inviting by remember { mutableStateOf(false) }
    var inviteBusy by remember { mutableStateOf(false) }
    var invited by remember { mutableStateOf<AdminRepository.AccessCode?>(null) }
    if (inviting) {
        InviteAdminDialog(
            busy = inviteBusy,
            onDismiss = { inviting = false },
            onInvite = { email, name ->
                inviteBusy = true
                scope.launch {
                    runCatching { AdminRepository.createInvite(email, name) }
                        .onSuccess { invited = it; inviting = false; reload++ }
                        .onFailure { snack("تعذّرت الدعوة: ${it.arabicReason()}") }
                    inviteBusy = false
                }
            },
        )
    }
    invited?.let {
        AccessCodeDialog(
            title = "رمز الدعوة",
            code = it.code,
            note = "صالح 24 ساعة، مرّة واحدة. يُدخله المدعوّ في «لديّ رمز دخول».",
            onDismiss = { invited = null },
        )
    }

    val scheme = MaterialTheme.colorScheme
    when (val action = pending) {
        is SupervisorAction.Block -> ConfirmDialog(
            title = "حظر المشرف",
            body = "سيُمنع «${action.admin.label}» من الدخول فوراً وتُبطَل جلساته. " +
                "يمكنك إلغاء الحظر لاحقاً.",
            confirmLabel = "حظر",
            confirmColor = scheme.error,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                run(action.admin.email, { AdminRepository.setDashAdminRole(action.admin.email, "blocked") }, "تعذّر الحظر")
            },
        )

        is SupervisorAction.Remove -> ConfirmDialog(
            title = "طرد المشرف",
            body = "سيُطرد «${action.admin.label}» من الإدارة وتُبطَل جلساته، ويبقى في القائمة " +
                "بعلامة «غادر». لا يعود إلا باعتماد جديد.",
            confirmLabel = "طرد",
            confirmColor = scheme.error,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                run(action.admin.email, { AdminRepository.removeDashAdmin(action.admin.email) }, "تعذّر الطرد")
            },
        )

        null -> Unit
    }

    AdminScaffold(
        title = "المشرفون",
        onBack = onBack,
        actions = {
            if (isOwner) {
                IconButton(onClick = { inviting = true }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "دعوة مشرف برمز")
                }
            }
            IconButton(onClick = { if (!loading) reload++ }) {
                if (loading) Spin(size = 20) else Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        when {
            loading && admins.isEmpty() -> FullScreenLoader()
            error != null && admins.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(error!!, color = scheme.error, textAlign = TextAlign.Center) }

            admins.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "لا مشرفون بعد.\nادعُ مشرفاً برمز من الزرّ أعلاه، أو يطلب هو رمز اعتماد من تطبيقه.",
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(admins, key = { it.email }) { a ->
                    AdminRow(
                        admin = a,
                        busy = busyEmail == a.email,
                        showActions = canActOn(a),
                        onBlock = { pending = SupervisorAction.Block(a) },
                        onUnblock = { run(a.email, { AdminRepository.setDashAdminRole(a.email, "supervisor") }, "تعذّر إلغاء الحظر") },
                        onSwapRole = {
                            val next = if (a.role == "admin") "supervisor" else "admin"
                            run(a.email, { AdminRepository.setDashAdminRole(a.email, next) }, "تعذّر تبديل الرتبة")
                        },
                        onRemove = { pending = SupervisorAction.Remove(a) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminRow(
    admin: DashAdmin,
    busy: Boolean,
    showActions: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onSwapRole: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val muted = admin.removed
    val ink = if (muted) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.onSurface
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🟢 نقطة الحضور: خضراء للمتّصل الآن، رماديّة لغيره.
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (admin.online && !muted) adminGreen else scheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        admin.label,
                        fontWeight = FontWeight.SemiBold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (admin.displayName.isNotEmpty()) {
                        Text(admin.email, fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.size(8.dp))
                RoleBadge(admin)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                presenceLine(admin),
                fontSize = 12.sp,
                color = if (admin.online && !muted) adminGreen else scheme.onSurfaceVariant,
            )
            if (admin.addedAtMs > 0L) {
                Text("انضمّ في ${dayLabel(admin.addedAtMs)}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
            if (muted && admin.removedAtMs > 0L) {
                Text(
                    "غادر في ${dayLabel(admin.removedAtMs)}" +
                        if (admin.removedBy.isNotEmpty()) " (طرده ${admin.removedBy.substringBefore('@')})" else "",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (showActions) {
                Spacer(Modifier.size(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (admin.blocked) {
                        OutlinedButton(onClick = onUnblock, enabled = !busy) { Text("إلغاء الحظر") }
                    } else {
                        OutlinedButton(onClick = onBlock, enabled = !busy) { Text("حظر", color = scheme.error) }
                        OutlinedButton(onClick = onSwapRole, enabled = !busy) {
                            Text(if (admin.role == "admin") "اجعله مشرفاً" else "اجعله مديراً")
                        }
                    }
                    OutlinedButton(onClick = onRemove, enabled = !busy) {
                        if (busy) {
                            Spin(color = scheme.error, size = 14)
                            Spacer(Modifier.size(4.dp))
                        }
                        Text("طرد", color = scheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(admin: DashAdmin) {
    val scheme = MaterialTheme.colorScheme
    val dark = isAdminDarkTheme()
    val (label, color) = when (admin.role) {
        "owner" -> "المالك" to (if (dark) Gold300 else kOwnerBadge)
        "admin" -> "مدير" to scheme.primary
        "blocked" -> "محظور" to scheme.error
        "removed" -> "غادر" to scheme.onSurfaceVariant
        else -> "مشرف" to scheme.primary
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold) }
}

private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.ROOT)

private fun dayLabel(ms: Long): String = dateFormat.format(Date(ms))

/** «متّصل الآن» أو «آخر ظهور منذ ٣ ساعات» — ولمن لم يظهر قطّ: «لم يدخل بعد». */
internal fun presenceLine(admin: DashAdmin, now: Long = System.currentTimeMillis()): String = when {
    admin.removed -> "غادر"
    admin.online -> "متّصل الآن"
    admin.lastSeenMs <= 0L -> "لم يدخل بعد"
    else -> "آخر ظهور ${relativeTime(admin.lastSeenMs, now)}"
}

/** «قبل ٥ دقائق» / «أمس» / «قبل شهرين» — بأرقام لاتينيّة كبقيّة اللوحة. */
internal fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val diff = now - ms
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        diff < 60_000 -> "الآن"
        minutes < 60 -> "قبل ${arabicCount(minutes.toInt(), "دقيقة", "دقيقتين", "دقائق", "دقيقة")}"
        hours < 24 -> "قبل ${arabicCount(hours.toInt(), "ساعة", "ساعتين", "ساعات", "ساعة")}"
        days < 2 -> "أمس"
        days < 30 -> "قبل ${arabicCount(days.toInt(), "يوم", "يومين", "أيام", "يوماً")}"
        else -> "قبل ${arabicCount((days / 30).toInt(), "شهر", "شهرين", "أشهر", "شهراً")}"
    }
}
