package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.SupportKind
import com.ali.ishaqiyin_admin.data.SupportRepository
import com.ali.ishaqiyin_admin.data.SupportThread
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** أيقونة كلّ نوع — تُميّز البلاغ من الاقتراح بنظرة واحدة. */
internal fun kindIcon(kind: SupportKind): ImageVector = when (kind) {
    SupportKind.Suggestion -> Icons.Filled.TipsAndUpdates
    SupportKind.Bug -> Icons.Filled.BugReport
    SupportKind.LessonHelp -> Icons.Filled.QuestionAnswer
    SupportKind.Idea -> Icons.Filled.Lightbulb
    SupportKind.Supervision -> Icons.Filled.SupervisorAccount
}

@Composable
internal fun kindColor(kind: SupportKind): Color = when (kind) {
    SupportKind.Bug -> MaterialTheme.colorScheme.error
    SupportKind.Supervision -> adminBlue
    SupportKind.Idea -> adminGold
    SupportKind.LessonHelp -> adminGreen
    SupportKind.Suggestion -> MaterialTheme.colorScheme.primary
}

/** «اليوم 14:05» أو «2026/08/12» — أرقام لاتينية في الحالتين. */
private val dayFormat = SimpleDateFormat("yyyy/MM/dd", Locale.ROOT)
private val timeFormat = SimpleDateFormat("HH:mm", Locale.ROOT)

internal fun supportTimeLabel(millis: Long): String {
    if (millis <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = dayFormat.format(Date(millis)) == dayFormat.format(Date(now))
    return if (sameDay) timeFormat.format(Date(millis)) else dayFormat.format(Date(millis))
}

/**
 * 📬 «رسائل المستخدمين» — للمالك وحده (الحارس في `AdminApp`).
 *
 * قائمة واحدة: المحادثات مرتّبة بالأحدث ومرشَّحة بالنوع. (تبويب «طلبات
 * الإشراف» أُزيل ٢٠٢٢/١.٨.٠ — لا خادم له؛ الاعتماد برمز المالك في «المشرفون».)
 */
@Composable
fun SupportInboxScreen(
    onBack: () -> Unit,
    onOpenThread: (SupportThread) -> Unit,
) {
    AdminScaffold(title = "رسائل المستخدمين", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            ThreadsTab(onOpenThread)
        }
    }
}


@Composable
private fun ThreadsTab(onOpenThread: (SupportThread) -> Unit) {
    val threads by rememberSupportFlow(emptyList<SupportThread>()) {
        SupportRepository.watchThreads()
    }
    // مرشِّح النوع — `null` تعني «الكلّ».
    var filter by remember { mutableStateOf<SupportKind?>(null) }
    val shown = remember(threads, filter) {
        threads.filter { filter == null || it.kind == filter }
            // غير المقروء يعلو دائماً: ما ينتظر ردّاً لا يُدفن تحت محادثة قديمة.
            .sortedWith(
                compareByDescending<SupportThread> { it.ownerUnread }
                    .thenByDescending { it.lastMessageAtMs },
            )
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipRow("الكلّ", filter == null) { filter = null }
            SupportKind.entries.forEach { kind ->
                FilterChipRow(kind.label, filter == kind) { filter = kind }
            }
        }
        if (shown.isEmpty()) {
            EmptyHint(
                if (threads.isEmpty()) {
                    "لا رسائل بعد. ما يرسله المستخدمون من التطبيق يصلك هنا."
                } else {
                    "لا رسائل من هذا النوع."
                },
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { thread ->
                ThreadRow(thread) { onOpenThread(thread) }
            }
        }
    }
}

/** كبسولة ترشيح واحدة (لا `FilterChip` كي يبقى هدف اللمس 48dp مضموناً). */
@Composable
private fun FilterChipRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .background(
                if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) scheme.primary else scheme.outline,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThreadRow(thread: SupportThread, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = kindColor(thread.kind)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(color.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                kindIcon(thread.kind),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.name,
                    fontSize = 15.sp,
                    fontWeight = if (thread.ownerUnread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(6.dp))
                Text(thread.kind.label, fontSize = 11.sp, color = color)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                thread.lastMessagePreview.ifBlank { "بلا نصّ" },
                fontSize = 12.5.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (thread.closed || thread.blocked) {
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        thread.blocked -> "المرسِل محظور"
                        else -> "المحادثة مغلقة"
                    },
                    fontSize = 11.sp,
                    color = scheme.error,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                supportTimeLabel(thread.lastMessageAtMs),
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
            if (thread.ownerUnread) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(10.dp).background(scheme.error, CircleShape))
            }
        }
    }
}

@Composable
private fun QuestionAnswerRow(question: String, answer: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            question,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            answer.ifBlank { "بلا جواب" },
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * ⚠️ `remember` حول التدفّق إلزاميّ: بلاه يُنشأ تدفّق جديد مع كلّ إعادة
 * تركيب فيُعاد ربط مستمع Firestore — وهو ما يستنزف إنترنت المشرف الضعيف.
 */
@Composable
internal fun <T> rememberSupportFlow(
    initial: T,
    create: () -> Flow<T>,
): androidx.compose.runtime.State<T> =
    remember { create() }.collectAsState(initial = initial)
