package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

/** هدف حذف مؤكَّد — نحتفظ بوصفه العربيّ كي يذكره الحوار بدقّة. */
private data class PendingFeedbackDelete(
    val id: String,
    val label: String,
    val lessonTitle: String,
)

/**
 * «تفاعل المستمعين» — يعرض التقييمات والبلاغات الواردة من التطبيق العام،
 * ليطّلع المشرف على نبض جمهوره ويصلح مشاكل الصوت.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var pendingDelete by remember { mutableStateOf<PendingFeedbackDelete?>(null) }
    var items by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching {
            // الإسناد بعد نجاح الجلبتين معاً: فشل جلب الدروس وحده كان يترك
            // العناوين فارغة فيظهر كل تفاعل بعنوان «(درس محذوف)».
            val fetchedItems = AdminRepository.fetchFeedback()
            val fetchedTitles = AdminRepository.fetchLessons().associate { it.id to it.title }
            items = fetchedItems
            titles = fetchedTitles
        }.onFailure { error = "تعذّر جلب البيانات. تحقّق من الاتصال." }
        loading = false
    }

    // الحذف كان يقع بضغطة واحدة بلا تأكيد، وفشله يُبتلع بصمت فيظنّه المشرف
    // نجح حتى يعود التفاعل بعد التحديث — الآن يؤكَّد وتُعلَن حصيلته.
    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "تأكيد الحذف",
            body = "سيُحذف «${target.label}» المسجَّل على \"${target.lessonTitle}\" " +
                "نهائياً. لا يمكن التراجع.",
            confirmLabel = "حذف",
            confirmColor = kDanger,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    runCatching { AdminRepository.deleteFeedback(target.id) }
                        .onSuccess { snack("حُذف التفاعل.") }
                        .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                    reload++
                }
            },
        )
    }

    AdminScaffold(
        title = "تفاعل المستمعين",
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

            items.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("لا توجد تفاعلات بعد.", fontSize = 16.sp, color = kMuted)
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(items.size) { index ->
                    val m = items[index]
                    val kind = kindOf((m["type"] ?: "").toString())
                    val title = titles[m["lessonId"]] ?: "(درس محذوف)"
                    val note = (m["note"] ?: "").toString()
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircleIcon(kind.icon, kind.color)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (note.isEmpty()) kind.label else "${kind.label} — $note",
                                    fontSize = 12.sp,
                                    color = kMuted,
                                )
                            }
                            IconButton(
                                onClick = {
                                    pendingDelete = PendingFeedbackDelete(
                                        id = m["id"].toString(),
                                        label = kind.label,
                                        lessonTitle = title,
                                    )
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = kDanger)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FeedbackKind(val label: String, val icon: ImageVector, val color: Color)

private fun kindOf(type: String): FeedbackKind = when (type) {
    "benefited" -> FeedbackKind("استفاد", Icons.Filled.ThumbUp, kGreen)
    "audio_issue" -> FeedbackKind("مشكلة صوت", Icons.AutoMirrored.Filled.VolumeOff, kDanger)
    else -> FeedbackKind("بلاغ", Icons.Filled.Report, kOrange)
}
