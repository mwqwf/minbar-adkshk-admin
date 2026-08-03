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
import androidx.compose.material3.MaterialTheme
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
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.data.dataMap
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
            // العناوين تُجلب للدروس المشار إليها وحدها (دفعات ٣٠ بمعرّف
            // الوثيقة) بدل سحب مجموعة الدروس كاملة لأجل بضعة تفاعلات.
            val lessonIds = fetchedItems
                .mapNotNull { it["lessonId"]?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val db = FirebaseFirestore.getInstance()
            val fetchedTitles = buildMap<String, String> {
                lessonIds.chunked(30).forEach { chunk ->
                    val snap = db.collection("lessons")
                        .whereIn(FieldPath.documentId(), chunk).get().await()
                    snap.documents.forEach { doc ->
                        put(doc.id, Lesson.fromDoc(doc.id, doc.dataMap()).title)
                    }
                }
            }
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
            confirmColor = MaterialTheme.colorScheme.error,
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
            ) { Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

            items.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("لا توجد تفاعلات بعد.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircleIcon(kind.icon, kind.tone.color())
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (note.isEmpty()) kind.label else "${kind.label} — $note",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * نغمة التفاعل — نوع مجرّد لا لون: `kindOf` دالّة عاديّة لا تُقرأ فيها
 * السمة، فاللون يُحلّ في موضع العرض وحده.
 */
private enum class FeedbackTone { Positive, Problem, Report }

private data class FeedbackKind(val label: String, val icon: ImageVector, val tone: FeedbackTone)

private fun kindOf(type: String): FeedbackKind = when (type) {
    "benefited" -> FeedbackKind("استفاد", Icons.Filled.ThumbUp, FeedbackTone.Positive)
    "audio_issue" ->
        FeedbackKind("مشكلة صوت", Icons.AutoMirrored.Filled.VolumeOff, FeedbackTone.Problem)
    else -> FeedbackKind("بلاغ", Icons.Filled.Report, FeedbackTone.Report)
}

/** لون النغمة المتكيّف مع الوضعين. */
@Composable
private fun FeedbackTone.color(): Color = when (this) {
    FeedbackTone.Positive -> adminGreen
    FeedbackTone.Problem -> MaterialTheme.colorScheme.error
    FeedbackTone.Report -> adminOrange
}
