package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonSubmission
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.SubmissionsRepository
import kotlinx.coroutines.launch

/**
 * 🗳️ «طلبات النشر» — مراجعة مساهمات المستمعين القادمة من تطبيق منبر
 * العام. المشرف يستمع للصوت ثم: يوافق كما هي / يعدّل (العنوان/الأقسام)
 * وينشر / يرفض بسبب. النتيجة تصل المساهم إشعاراً.
 */
@Composable
fun SubmissionsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf<LessonSubmission?>(null) }
    var editing by remember { mutableStateOf<LessonSubmission?>(null) }
    var rejecting by remember { mutableStateOf<LessonSubmission?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
    }

    val items by remember { SubmissionsRepository.watchAll() }
        .collectAsState(initial = emptyList())

    fun run(doneMsg: String, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                if (doneMsg.isNotEmpty()) snack(doneMsg)
            } catch (_: Exception) {
                snack("تعذّر تنفيذ العملية. حاول مجدداً.")
            }
            busy = false
        }
    }

    approving?.let { s ->
        ConfirmDialog(
            title = "نشر المساهمة كما هي؟",
            body = "«${s.title}»\n${s.categoryName} ← ${s.subcategoryName}\n\n" +
                "سيُنشر الدرس فوراً ويصل المساهم إشعار شكر.",
            confirmLabel = "نشر",
            onDismiss = { approving = null },
            onConfirm = {
                approving = null
                run("نُشرت المساهمة وأُخطر المساهم. ✅") {
                    SubmissionsRepository.approveAndPublish(s)
                }
            },
        )
    }

    editing?.let { s ->
        EditAndPublishDialog(
            submission = s,
            categories = categories,
            subcategories = subcategories,
            onDismiss = { editing = null },
            onPublish = { title, cat, sub ->
                editing = null
                run("نُشرت المساهمة بعد التعديل وأُخطر المساهم. ✅") {
                    SubmissionsRepository.approveAndPublish(
                        s,
                        editedTitle = title,
                        editedCategoryId = cat.id,
                        editedCategoryName = cat.name,
                        editedSubcategoryId = sub.id,
                        editedSubcategoryName = sub.name,
                    )
                }
            },
        )
    }

    rejecting?.let { s ->
        RejectDialog(
            onDismiss = { rejecting = null },
            onReject = { reason ->
                rejecting = null
                run("رُفضت المساهمة وأُبلغ المساهم بالسبب.") {
                    SubmissionsRepository.reject(s, reason)
                }
            },
        )
    }

    AdminScaffold(title = "طلبات النشر (مساهمات المستمعين)", onBack = onBack) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "لا توجد مساهمات بعد.\nعندما يرسل المستمعون دروساً من " +
                        "«شارك درساً» ستظهر هنا للمراجعة.",
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )
            }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items.size) { index ->
                val s = items[index]
                val isPlaying = player.playingId == s.id && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(kBoxBg, CircleShape)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = { player.toggle(s.id, s.audioUrl) }) {
                                    Icon(
                                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "إيقاف" else "استماع",
                                        tint = kTeal,
                                    )
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.size(2.dp))
                                Text(
                                    "${s.categoryName} ← ${s.subcategoryName}",
                                    fontSize = 12.sp,
                                    color = kMuted,
                                )
                            }
                            StatusChip(s.status)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "المساهم: ${s.submitterName.ifEmpty { "بدون اسم" }}" +
                                if (s.fileSize > 0) {
                                    " • ${"%.1f".format(s.fileSize / (1024.0 * 1024.0))}MB"
                                } else {
                                    ""
                                },
                            fontSize = 12.sp,
                            color = kMuted,
                        )
                        if (s.note.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text("ملاحظة المساهم: ${s.note}", fontSize = 12.sp)
                        }
                        if (!s.isPending && s.status == "rejected" && s.rejectReason.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "سبب الرفض: ${s.rejectReason}",
                                fontSize = 12.sp,
                                color = kDanger,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        if (s.isPending) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { approving = s },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("نشر كما هي")
                                }
                                OutlinedButton(
                                    onClick = { editing = s },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("تعديل ثم نشر")
                                }
                                IconButton(onClick = { rejecting = s }, enabled = !busy) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "رفض",
                                        tint = kDanger,
                                    )
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        run("") { SubmissionsRepository.deleteDecided(s) }
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("إزالة من السجل")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (color, label) = when (status) {
        "approved" -> kGreen to "نُشرت"
        "approved_edited" -> kTeal to "نُشرت معدَّلة"
        "rejected" -> kDanger to "مرفوضة"
        else -> kOrange to "معلّقة"
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun EditAndPublishDialog(
    submission: LessonSubmission,
    categories: List<Category>,
    subcategories: List<Subcategory>,
    onDismiss: () -> Unit,
    onPublish: (String, Category, Subcategory) -> Unit,
) {
    var title by remember { mutableStateOf(submission.title) }
    var category by remember {
        mutableStateOf(categories.firstOrNull { it.id == submission.categoryId })
    }
    var subcategory by remember {
        mutableStateOf(
            subcategories.firstOrNull {
                it.id == submission.subcategoryId && it.categoryId == submission.categoryId
            },
        )
    }
    val subs = subcategories.filter { it.categoryId == category?.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل ثم نشر") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdminTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    label = "العنوان",
                )
                Spacer(Modifier.height(8.dp))
                AdminDropdown(
                    label = "القسم الرئيسي",
                    items = categories,
                    selected = category,
                    itemLabel = { it.name },
                    onSelected = {
                        category = it
                        subcategory = null
                    },
                )
                Spacer(Modifier.height(8.dp))
                AdminDropdown(
                    label = "القسم الفرعي",
                    items = subs,
                    selected = subcategory,
                    itemLabel = { it.name },
                    onSelected = { subcategory = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val c = category ?: return@Button
                    val s = subcategory ?: return@Button
                    onPublish(title.trim(), c, s)
                },
                enabled = title.trim().isNotEmpty() && category != null && subcategory != null,
                colors = ButtonDefaults.buttonColors(containerColor = kTeal),
            ) { Text("نشر المعدَّل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun RejectDialog(onDismiss: () -> Unit, onReject: (String) -> Unit) {
    val presets = listOf(
        "المحتوى لا يناسب طبيعة التطبيق",
        "جودة الصوت ضعيفة أو غير واضحة",
        "المحتوى مكرّر (منشور سابقاً)",
        "القسم المختار غير مناسب والمحتوى غير مكتمل",
    )
    var selected by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سبب الرفض") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "يصل السبب للمساهم كما هو — اكتبه بلطف ووضوح.",
                    fontSize = 12.sp,
                    color = kMuted,
                )
                Spacer(Modifier.height(8.dp))
                (presets + "_other").forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == option,
                                onClick = { selected = option },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = { selected = option })
                        Text(
                            if (option == "_other") "سبب آخر…" else option,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (selected == "_other") {
                    AdminTextField(
                        value = note,
                        onValueChange = { if (it.length <= 300) note = it },
                        label = "اكتب السبب",
                        singleLine = false,
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val reason = if (selected == "_other") note.trim() else selected.orEmpty()
                    onReject(reason)
                },
                // «سبب آخر» بلا نص فعلي يرفضه الخادم (سبب ≥ حرفين) — نعطّل
                // الزر بدل تركه يفشل برسالة مبهمة.
                enabled = selected != null &&
                    !(selected == "_other" && note.trim().length < 2),
                colors = ButtonDefaults.buttonColors(containerColor = kDanger),
            ) { Text("رفض") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
