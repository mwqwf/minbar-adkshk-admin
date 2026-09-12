package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuditEntry
import com.ali.ishaqiyin_admin.data.arabicReason

/**
 * 📜 سجل التدقيق — من فعل ماذا ومتى، من `GET /admin/audit` (يكتبه الخادم مع
 * كل تعديل وسلّة واستعادة وإتلاف وتغيير رتبة). يراه كل المشرفين.
 *
 * خفيف عمداً: جلبة واحدة لآخر ثلاثين يوماً عند الفتح، وزرّ تحديث يدويّ.
 */
@Composable
fun AuditLogScreen(onBack: () -> Unit) {
    val snack = LocalSnack.current
    var items by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = ""
        runCatching { AdminRepository.fetchAuditLog() }
            .onSuccess { items = it }
            .onFailure {
                val message = "تعذّر جلب السجل: ${it.arabicReason()}"
                if (items.isEmpty()) error = message else snack(message)
            }
        loading = false
    }

    AdminScaffold(
        title = "سجل التدقيق",
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (!loading) reload++ }) {
                if (loading) Spin(size = 20) else Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && items.isEmpty() -> FullScreenLoader()
                error.isNotEmpty() && items.isEmpty() -> EmptyHint(error)
                items.isEmpty() -> EmptyHint("لا شيء مسجّل في الثلاثين يوماً الأخيرة.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(items, key = { it.id }) { entry ->
                        AuditRow(entry)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    val destructive = entry.action in setOf("purge", "trash.empty", "admin.remove")
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(entry.action), contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(auditSentence(entry), fontSize = 13.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(2.dp))
            Text(relativeTime(entry.atMs), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun iconFor(action: String): ImageVector = when (action) {
    "upsert", "patch" -> Icons.Filled.Edit
    "trash", "delete" -> Icons.Filled.Delete
    "restore" -> Icons.Filled.RestoreFromTrash
    "purge", "trash.empty" -> Icons.Filled.DeleteForever
    "admin.add", "admin.role", "admin.invite" -> Icons.Filled.PersonAdd
    "admin.remove" -> Icons.Filled.PersonOff
    "session.migrate", "session.redeem" -> Icons.Filled.Login
    else -> Icons.Filled.History
}

/** «نقل فلان درس «كذا» إلى السلة» — جملة بشريّة لا حقول قاعدة بيانات. */
internal fun auditSentence(entry: AuditEntry): String {
    val who = entry.actor.substringBefore('@').ifBlank { "أحد المشرفين" }
    val kind = when (entry.entity) {
        "categories" -> "القسم الرئيسيّ"
        "subcategories" -> "القسم الفرعيّ"
        "lessons" -> "الدرس"
        "transcripts" -> "النصّ المشروح"
        "admin" -> "المشرف"
        else -> entry.entity
    }
    val name = entry.title.ifBlank { if (entry.entity == "admin") entry.entityId.substringBefore('@') else "" }
    val target = if (name.isBlank()) kind else "$kind «$name»"
    return when (entry.action) {
        "upsert" -> "أضاف أو عدّل $who $target"
        "patch" -> "عدّل $who $target"
        "trash" -> "نقل $who $target إلى السلة"
        "restore" -> "استعاد $who $target من السلة"
        "purge" -> "أتلف $who $target نهائياً"
        "trash.empty" -> "أفرغ $who السلة كلّها"
        "delete" -> "حذف $who $target"
        "admin.add" -> "أضاف $who مشرفاً: ${name.ifBlank { entry.entityId }}"
        "admin.role" -> "غيّر $who رتبة ${name.ifBlank { entry.entityId }} إلى ${roleName(entry.details.optString("to"))}"
        "admin.remove" -> "طرد $who ${name.ifBlank { entry.entityId }} من الإدارة"
        "session.migrate" -> "انتقل $who إلى جلسة منبر"
        "session.redeem" -> "دخل $who برمز ربط"
        "admin.invite" -> "دعا $who ${name.ifBlank { entry.entityId.substringBefore('@') }} برمز"
        else -> "${entry.action} — $who: $target"
    }
}

private fun roleName(role: String): String = when (role) {
    "owner" -> "المالك"
    "admin" -> "مدير"
    "blocked" -> "محظور"
    "removed" -> "مطرود"
    else -> "مشرف"
}
