package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonSubmission
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.SubmissionsRepository
import com.ali.ishaqiyin_admin.data.TranscriptSubmission
import com.ali.ishaqiyin_admin.data.TranscriptsRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 🗳️ «المساهمات» — تبويبان:
 * 1. دروس صوتية: مساهمات «شارك درساً» (استماع ثم نشر/تعديل/رفض).
 * 2. نصوص مشروحة: اقتراحات «النص المشروح» (نص المتن/صور صفحاته) مع
 *    الاستماع لصوتية الدرس نفسها أثناء المطابقة، واستخراج النص من
 *    الصورة (OCR خادمي) عند التعديل. النتيجة تصل المساهم إشعاراً.
 * في التبويبين: شريط فلترة (معلّقة/محسومة/الكل) ووضع تحديد متعدّد يحسم
 * عدّة مساهمات دفعة واحدة بتقدّم حيّ.
 */
@Composable
fun SubmissionsScreen(onBack: () -> Unit) {
    // إشعار «نصّ مشروح» يجب أن يفتح تبويبه لا تبويب الصوتيات. الهدف يُستهلك
    // مرّة واحدة فلا يعود إلى تبويبه عند كل إعادة تركيب.
    var tab by rememberSaveable { mutableStateOf(SubmissionsTarget.consumeTab()) }
    val audioPending by remember { SubmissionsRepository.watchPendingCount() }
        .collectAsState(initial = 0)
    val textPending by remember { TranscriptsRepository.watchPendingCount() }
        .collectAsState(initial = 0)

    AdminScaffold(title = "المساهمات", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { TabLabel("دروس صوتية", audioPending) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { TabLabel("نصوص مشروحة", textPending) },
                )
            }
            if (tab == 0) AudioSubmissionsContent() else TranscriptSubmissionsContent()
        }
    }
}

@Composable
private fun TabLabel(label: String, pending: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (pending > 0) {
            Spacer(Modifier.size(6.dp))
            // في الفاتح يبقى لون المحتوى كما كان (لون التبويب) فلا يتغيّر
            // المظهر، وفي الداكن يصير البرتقالي فاتحاً فيحتاج حبراً داكناً.
            Badge(
                containerColor = adminOrange,
                contentColor = if (isAdminDarkTheme()) {
                    contentColorOn(adminOrange)
                } else {
                    LocalContentColor.current
                },
            ) { Text("$pending") }
        }
    }
}

// ─── الفلترة والحسم الجماعي (مشتركان بين التبويبين) ──────────────────

/** فلترة قائمة المساهمات: ما ينتظر قراراً، ما حُسم، أو الكل. */
private enum class SubFilter(val label: String) {
    PENDING("معلّقة"),
    DECIDED("محسومة"),
    ALL("الكل"),
}

@Composable
private fun SubmissionsFilterBar(
    current: SubFilter,
    pendingCount: Int,
    decidedCount: Int,
    onSelect: (SubFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubFilter.entries.forEach { option ->
            val count = when (option) {
                SubFilter.PENDING -> pendingCount
                SubFilter.DECIDED -> decidedCount
                SubFilter.ALL -> pendingCount + decidedCount
            }
            FilterChip(
                selected = current == option,
                onClick = { onSelect(option) },
                label = { Text("${option.label} ($count)", fontSize = 12.sp) },
            )
        }
    }
}

/**
 * شريط الحسم الجماعي — بنفس نمط «اعتماد الكل» في شاشة المالك: تحديد
 * متعدّد ثم نشر/اعتماد أو رفض بسبب واحد، مع تقدّم حيّ (المنجز من الإجمالي).
 */
@Composable
private fun BulkDecisionBar(
    selectMode: Boolean,
    selectedCount: Int,
    pendingVisible: Int,
    approveLabel: String,
    busy: Boolean,
    done: Int,
    total: Int,
    onToggleMode: () -> Unit,
    onSelectAll: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    if (pendingVisible == 0 && !busy) return
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectMode) {
                            "اختير $selectedCount من $pendingVisible معلّقة"
                        } else {
                            "$pendingVisible مساهمة بانتظار قرارك"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        if (selectMode) {
                            "حدّد ما راجعتَه ثم احسمه دفعة واحدة."
                        } else {
                            "لحسم عدّة مساهمات معاً فعّل التحديد المتعدّد."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(6.dp))
                TextButton(onClick = onToggleMode, enabled = !busy) {
                    Icon(
                        Icons.Filled.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(if (selectMode) "إنهاء التحديد" else "تحديد متعدّد", fontSize = 12.sp)
                }
            }
            if (selectMode) {
                Spacer(Modifier.size(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onApprove,
                        enabled = !busy && selectedCount > 0,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(approveLabel, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !busy && selectedCount > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "رفض المحدَّد",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onSelectAll, enabled = !busy) {
                    Text(
                        if (selectedCount >= pendingVisible && pendingVisible > 0) {
                            "إلغاء تحديد الكل"
                        } else {
                            "تحديد كل المعروض"
                        },
                        fontSize = 12.sp,
                    )
                }
            }
            if (busy) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "جارٍ التنفيذ… $done من $total",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── تبويب الدروس الصوتية (سلوك «طلبات النشر» السابق كما هو) ─────────

@Composable
private fun AudioSubmissionsContent() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf<LessonSubmission?>(null) }
    var editing by remember { mutableStateOf<LessonSubmission?>(null) }
    var rejecting by remember { mutableStateOf<LessonSubmission?>(null) }
    // الفلترة والحسم الجماعي.
    var filter by remember { mutableStateOf(SubFilter.PENDING) }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkDone by remember { mutableIntStateOf(0) }
    var bulkTotal by remember { mutableIntStateOf(0) }
    var confirmBulkPublish by remember { mutableStateOf(false) }
    var bulkRejecting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
    }

    val items by remember { SubmissionsRepository.watchAll() }
        .collectAsState(initial = emptyList())

    val pendingCount = items.count { it.isPending }
    val decidedCount = items.size - pendingCount
    val shown = items.filter {
        when (filter) {
            SubFilter.PENDING -> it.isPending
            SubFilter.DECIDED -> !it.isPending
            SubFilter.ALL -> true
        }
    }
    val pendingVisible = shown.count { it.isPending }
    val chosen = shown.filter { it.isPending && selected.contains(it.id) }

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

    fun finishBulk(label: String, done: Int, failed: Int) {
        selected.clear()
        selectMode = false
        bulkBusy = false
        snack(
            if (failed > 0) {
                "$label $done مساهمة، وأخفقت $failed — أعد المحاولة عليها."
            } else {
                "$label $done مساهمة دفعة واحدة. ✅"
            },
        )
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
            presets = audioRejectPresets,
            onDismiss = { rejecting = null },
            onReject = { reason ->
                rejecting = null
                run("رُفضت المساهمة وأُبلغ المساهم بالسبب.") {
                    SubmissionsRepository.reject(s, reason)
                }
            },
        )
    }

    if (confirmBulkPublish) {
        val targets = chosen.toList()
        ConfirmDialog(
            title = "نشر المحدَّد؟",
            body = "سيُنشر ${targets.size} درساً دفعة واحدة كما هي (بلا تعديل " +
                "عنوان أو قسم)، ويصل كل مساهم إشعار شكر.",
            confirmLabel = "نشر المحدَّد",
            onDismiss = { confirmBulkPublish = false },
            onConfirm = {
                confirmBulkPublish = false
                bulkBusy = true
                bulkDone = 0
                bulkTotal = targets.size
                scope.launch {
                    val result = runCatching {
                        SubmissionsRepository.bulkApprove(targets) { d, t ->
                            bulkDone = d
                            bulkTotal = t
                        }
                    }
                    result
                        .onSuccess { finishBulk("نُشرت", it.done, it.failed) }
                        .onFailure {
                            bulkBusy = false
                            snack("تعذّر النشر الجماعي. حاول مجدداً.")
                        }
                }
            },
        )
    }

    if (bulkRejecting) {
        val targets = chosen.toList()
        RejectDialog(
            presets = audioRejectPresets,
            onDismiss = { bulkRejecting = false },
            onReject = { reason ->
                bulkRejecting = false
                bulkBusy = true
                bulkDone = 0
                bulkTotal = targets.size
                scope.launch {
                    val result = runCatching {
                        SubmissionsRepository.bulkReject(targets, reason) { d, t ->
                            bulkDone = d
                            bulkTotal = t
                        }
                    }
                    result
                        .onSuccess { finishBulk("رُفضت", it.done, it.failed) }
                        .onFailure {
                            bulkBusy = false
                            snack("تعذّر الرفض الجماعي. حاول مجدداً.")
                        }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SubmissionsFilterBar(
            current = filter,
            pendingCount = pendingCount,
            decidedCount = decidedCount,
            onSelect = {
                filter = it
                if (it == SubFilter.DECIDED) {
                    selectMode = false
                    selected.clear()
                }
            },
        )
        BulkDecisionBar(
            selectMode = selectMode,
            selectedCount = chosen.size,
            pendingVisible = pendingVisible,
            approveLabel = "نشر المحدَّد",
            busy = bulkBusy,
            done = bulkDone,
            total = bulkTotal,
            onToggleMode = {
                selectMode = !selectMode
                if (!selectMode) selected.clear()
            },
            onSelectAll = {
                if (chosen.size >= pendingVisible) {
                    selected.clear()
                } else {
                    shown.filter { it.isPending }.forEach {
                        if (!selected.contains(it.id)) selected.add(it.id)
                    }
                }
            },
            onApprove = { confirmBulkPublish = true },
            onReject = { bulkRejecting = true },
        )
        if (shown.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (items.isEmpty()) {
                        "لا توجد مساهمات بعد.\nعندما يرسل المستمعون دروساً من " +
                            "«شارك درساً» ستظهر هنا للمراجعة."
                    } else {
                        "لا مساهمات ضمن هذه الفلترة — جرّب «الكل»."
                    },
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown.size) { index ->
                val s = shown[index]
                val isPlaying = player.playingId == s.id && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectMode && s.isPending) {
                                Checkbox(
                                    checked = selected.contains(s.id),
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (!selected.contains(s.id)) selected.add(s.id)
                                        } else {
                                            selected.remove(s.id)
                                        }
                                    },
                                    enabled = !bulkBusy,
                                )
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = { player.toggle(s.id, s.audioUrl) }) {
                                    Icon(
                                        if (isPlaying) {
                                            Icons.Filled.Pause
                                        } else {
                                            Icons.Filled.PlayArrow
                                        },
                                        contentDescription = if (isPlaying) "إيقاف" else "استماع",
                                        tint = MaterialTheme.colorScheme.primary,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(s.status)
                        }
                        // شريط التحكّم يظهر للمقطع الجاري وحده: تقدّم وقفز وسرعة.
                        PreviewPlayerBar(
                            state = player,
                            id = s.id,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "المساهم: ${s.submitterName.ifEmpty { "بدون اسم" }}" +
                                if (s.fileSize > 0) {
                                    " • ${"%.1f".format(s.fileSize / (1024.0 * 1024.0))}MB"
                                } else {
                                    ""
                                },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.note.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text("ملاحظة المساهم: ${s.note}", fontSize = 12.sp)
                        }
                        if (!s.isPending && s.status == "rejected" &&
                            s.rejectReason.isNotEmpty()
                        ) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "سبب الرفض: ${s.rejectReason}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
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
                                    enabled = !busy && !bulkBusy,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
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
                                    enabled = !busy && !bulkBusy,
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
                                IconButton(
                                    onClick = { rejecting = s },
                                    enabled = !busy && !bulkBusy,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "رفض",
                                        tint = MaterialTheme.colorScheme.error,
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
                                    enabled = !busy && !bulkBusy,
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

// ─── تبويب النصوص المشروحة ──────────────────────────────────────────

private val transcriptRejectPresets = listOf(
    "النص لا يطابق ما تشرحه الصوتية",
    "الصورة غير واضحة أو غير مقروءة",
    "النص منقول بأخطاء تحتاج تدقيقاً",
    "الدرس له نص معتمد أدق من المقترح",
)

private val audioRejectPresets = listOf(
    "المحتوى لا يناسب طبيعة التطبيق",
    "جودة الصوت ضعيفة أو غير واضحة",
    "المحتوى مكرّر (منشور سابقاً)",
    "القسم المختار غير مناسب والمحتوى غير مكتمل",
)

@Composable
private fun TranscriptSubmissionsContent() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var busy by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf<TranscriptSubmission?>(null) }
    var editing by remember { mutableStateOf<TranscriptSubmission?>(null) }
    var rejecting by remember { mutableStateOf<TranscriptSubmission?>(null) }
    var viewingImage by remember { mutableStateOf("") }
    // روابط صوتيات الدروس تُجلب كسولاً هنا؛ وروابط صور الاقتراحات صارت
    // مكشوفة من كاش المستودع فلا تُعاد كل زيارة.
    val audioUrls = remember { mutableStateMapOf<String, String>() }
    val imageUrls = remember { mutableStateMapOf<String, String>() }
    val hasApprovedText = remember { mutableStateMapOf<String, Boolean>() }
    // الفلترة والحسم الجماعي.
    var filter by remember { mutableStateOf(SubFilter.PENDING) }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkDone by remember { mutableIntStateOf(0) }
    var bulkTotal by remember { mutableIntStateOf(0) }
    var confirmBulkApprove by remember { mutableStateOf(false) }
    var bulkRejecting by remember { mutableStateOf(false) }

    val items by remember { TranscriptsRepository.watchAll() }
        .collectAsState(initial = emptyList())

    val pendingCount = items.count { it.isPending }
    val decidedCount = items.size - pendingCount
    val shown = items.filter {
        when (filter) {
            SubFilter.PENDING -> it.isPending
            SubFilter.DECIDED -> !it.isPending
            SubFilter.ALL -> true
        }
    }
    val pendingVisible = shown.count { it.isPending }
    val chosen = shown.filter { it.isPending && selected.contains(it.id) }

    /**
     * بعد أيّ اعتماد يصير للدرس نصّ معتمد قطعاً — تثبيت ذلك فوراً هو ما
     * يمنع محو نصّ اعتُمد قبل لحظات: بدونه يبقى التحذير غائباً عن الاقتراح
     * الثاني لنفس الدرس فيعتمده المشرف ظانّاً أنه أوّل نص، بينما الاعتماد
     * يستبدل الوثيقة كاملة ويحذف صور النص السابق.
     */
    fun markApproved(lessonId: String) {
        hasApprovedText[lessonId] = true
        TranscriptsRepository.invalidateTranscript(lessonId, known = true)
    }

    fun run(doneMsg: String, after: () -> Unit = {}, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                after()
                if (doneMsg.isNotEmpty()) snack(doneMsg)
            } catch (_: Exception) {
                snack("تعذّر تنفيذ العملية. حاول مجدداً.")
            }
            busy = false
        }
    }

    fun finishBulk(label: String, done: Int, failed: Int, skipped: Int) {
        selected.clear()
        selectMode = false
        bulkBusy = false
        val extra = buildString {
            if (failed > 0) append("، وأخفقت $failed")
            if (skipped > 0) append("، وتُخطّي $skipped لأن درسها حُسم في الدفعة نفسها")
        }
        snack("$label $done اقتراحاً$extra.")
    }

    fun playLesson(lessonId: String) {
        val cached = audioUrls[lessonId]
        if (cached != null) {
            player.toggle(lessonId, cached)
            return
        }
        scope.launch {
            try {
                val doc = FirebaseFirestore.getInstance()
                    .collection("lessons").document(lessonId).get().await()
                val raw = doc.data ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val unwrapped = (raw["data"] as? Map<String, Any?>) ?: raw
                val url = (unwrapped["audioUrl"] ?: raw["audioUrl"])?.toString().orEmpty()
                if (url.isEmpty()) {
                    snack("لم يُعثر على صوتية هذا الدرس.")
                } else {
                    audioUrls[lessonId] = url
                    player.toggle(lessonId, url)
                }
            } catch (_: Exception) {
                snack("تعذّر جلب صوتية الدرس.")
            }
        }
    }

    approving?.let { s ->
        ConfirmDialog(
            title = "اعتماد النص كما هو؟",
            body = "سيظهر النص المشروح فوراً في درس «${s.lessonTitle}» " +
                "ويصل المساهم إشعار شكر." +
                if (hasApprovedText[s.lessonId] == true) {
                    "\n\n⚠️ يوجد نص معتمد سابق لهذا الدرس وسيُستبدل."
                } else {
                    ""
                },
            confirmLabel = "اعتماد",
            onDismiss = { approving = null },
            onConfirm = {
                approving = null
                run(
                    "اعتُمد النص وأُخطر المساهم. ✅",
                    after = { markApproved(s.lessonId) },
                ) {
                    TranscriptsRepository.approve(s)
                }
            },
        )
    }

    editing?.let { s ->
        EditTranscriptDialog(
            submission = s,
            imageUrls = imageUrls,
            hasExistingApproved = hasApprovedText[s.lessonId] == true,
            onDismiss = { editing = null },
            onApprove = { text, bookTitle, sourceRef, keepImages ->
                editing = null
                run(
                    "اعتُمد النص بعد التعديل وأُخطر المساهم. ✅",
                    after = { markApproved(s.lessonId) },
                ) {
                    TranscriptsRepository.approve(
                        s,
                        editedText = text,
                        editedBookTitle = bookTitle,
                        editedSourceRef = sourceRef,
                        keepImages = keepImages,
                    )
                }
            },
        )
    }

    rejecting?.let { s ->
        RejectDialog(
            presets = transcriptRejectPresets,
            onDismiss = { rejecting = null },
            onReject = { reason ->
                rejecting = null
                run("رُفض الاقتراح وأُبلغ المساهم بالسبب.") {
                    TranscriptsRepository.reject(s, reason)
                }
            },
        )
    }

    if (confirmBulkApprove) {
        val targets = chosen.toList()
        val replaced = targets.count { hasApprovedText[it.lessonId] == true }
        val duplicated = targets.size - targets.map { it.lessonId }.distinct().size
        ConfirmDialog(
            title = "اعتماد المحدَّد؟",
            body = buildString {
                append("سيُعتمد ${targets.size} اقتراحاً كما هو ويصل كل مساهم إشعار شكر.")
                if (replaced > 0) {
                    append("\n\n⚠️ $replaced منها لدروس لها نص معتمد سابق سيُستبدل.")
                }
                if (duplicated > 0) {
                    append(
                        "\n\n⚠️ $duplicated اقتراحاً يخصّ درساً محدَّداً مرّتين — " +
                            "سيُعتمد الأوّل وحده ويُتخطّى الباقي كي لا يُمحى ما اعتُمد للتوّ.",
                    )
                }
            },
            confirmLabel = "اعتماد المحدَّد",
            onDismiss = { confirmBulkApprove = false },
            onConfirm = {
                confirmBulkApprove = false
                bulkBusy = true
                bulkDone = 0
                bulkTotal = targets.size
                scope.launch {
                    val result = runCatching {
                        TranscriptsRepository.bulkApprove(targets) { d, t ->
                            bulkDone = d
                            bulkTotal = t
                        }
                    }
                    result
                        .onSuccess { outcome ->
                            targets.map { it.lessonId }.distinct()
                                .forEach { lessonId -> markApproved(lessonId) }
                            finishBulk(
                                "اعتُمد",
                                outcome.done,
                                outcome.failed,
                                outcome.skippedDuplicates,
                            )
                        }
                        .onFailure {
                            bulkBusy = false
                            snack("تعذّر الاعتماد الجماعي. حاول مجدداً.")
                        }
                }
            },
        )
    }

    if (bulkRejecting) {
        val targets = chosen.toList()
        RejectDialog(
            presets = transcriptRejectPresets,
            onDismiss = { bulkRejecting = false },
            onReject = { reason ->
                bulkRejecting = false
                bulkBusy = true
                bulkDone = 0
                bulkTotal = targets.size
                scope.launch {
                    val result = runCatching {
                        TranscriptsRepository.bulkReject(targets, reason) { d, t ->
                            bulkDone = d
                            bulkTotal = t
                        }
                    }
                    result
                        .onSuccess { finishBulk("رُفض", it.done, it.failed, 0) }
                        .onFailure {
                            bulkBusy = false
                            snack("تعذّر الرفض الجماعي. حاول مجدداً.")
                        }
                }
            },
        )
    }

    if (viewingImage.isNotEmpty()) {
        FullscreenImageDialog(url = viewingImage, onDismiss = { viewingImage = "" })
    }

    Column(Modifier.fillMaxSize()) {
        SubmissionsFilterBar(
            current = filter,
            pendingCount = pendingCount,
            decidedCount = decidedCount,
            onSelect = {
                filter = it
                if (it == SubFilter.DECIDED) {
                    selectMode = false
                    selected.clear()
                }
            },
        )
        BulkDecisionBar(
            selectMode = selectMode,
            selectedCount = chosen.size,
            pendingVisible = pendingVisible,
            approveLabel = "اعتماد المحدَّد",
            busy = bulkBusy,
            done = bulkDone,
            total = bulkTotal,
            onToggleMode = {
                selectMode = !selectMode
                if (!selectMode) selected.clear()
            },
            onSelectAll = {
                if (chosen.size >= pendingVisible) {
                    selected.clear()
                } else {
                    shown.filter { it.isPending }.forEach {
                        if (!selected.contains(it.id)) selected.add(it.id)
                    }
                }
            },
            onApprove = { confirmBulkApprove = true },
            onReject = { bulkRejecting = true },
        )
        if (shown.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (items.isEmpty()) {
                        "لا توجد اقتراحات نصوص بعد.\nعندما يرسل المستمعون نص المقطع " +
                            "المشروح (أو صور صفحاته) من شاشة التشغيل ستظهر هنا للمراجعة."
                    } else {
                        "لا اقتراحات ضمن هذه الفلترة — جرّب «الكل»."
                    },
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown.size) { index ->
                val s = shown[index]
                // جلب كسول: هل للدرس نص معتمد سابق؟ (تحذير الاستبدال عند
                // الاعتماد). الجواب بولياني ومكشوف من كاش المستودع فلا تتكرّر
                // قراءة الوثيقة لكل اقتراح معروض عند كل تمرير.
                LaunchedEffect(s.lessonId) {
                    if (s.isPending && !hasApprovedText.containsKey(s.lessonId)) {
                        runCatching {
                            hasApprovedText[s.lessonId] =
                                TranscriptsRepository.hasTranscript(s.lessonId)
                        }
                    }
                }
                val isPlaying = player.playingId == s.lessonId && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectMode && s.isPending) {
                                Checkbox(
                                    checked = selected.contains(s.id),
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (!selected.contains(s.id)) selected.add(s.id)
                                        } else {
                                            selected.remove(s.id)
                                        }
                                    },
                                    enabled = !bulkBusy,
                                )
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(onClick = { playLesson(s.lessonId) }) {
                                    Icon(
                                        if (isPlaying) {
                                            Icons.Filled.Pause
                                        } else {
                                            Icons.Filled.PlayArrow
                                        },
                                        contentDescription = if (isPlaying) {
                                            "إيقاف"
                                        } else {
                                            "الاستماع لصوتية الدرس"
                                        },
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.lessonTitle.ifEmpty { "درس" },
                                    fontWeight = FontWeight.Bold,
                                )
                                if (s.bookTitle.isNotEmpty()) {
                                    Spacer(Modifier.size(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.MenuBook,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.size(4.dp))
                                        Text(
                                            s.bookTitle,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            StatusChip(s.status)
                        }
                        // شريط التحكّم للصوتية الجارية: مطابقة النص بالصوت
                        // تحتاج قفزاً داخل الدرس لا سماعه من أوّله.
                        PreviewPlayerBar(
                            state = player,
                            id = s.lessonId,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (s.sourceRef.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "المقطع: ${s.sourceRef}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.text.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            var expanded by remember(s.id) { mutableStateOf(false) }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                                    .clickable { expanded = !expanded }
                                    .padding(10.dp),
                            ) {
                                Text(
                                    s.text,
                                    fontSize = 14.sp,
                                    lineHeight = 24.sp,
                                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                                )
                            }
                            if (s.text.length > 160) {
                                TextButton(onClick = { expanded = !expanded }) {
                                    Text(
                                        if (expanded) "طيّ النص" else "عرض النص كاملاً",
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        if (s.imagePaths.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            SubmissionImagesRow(
                                paths = s.imagePaths,
                                imageUrls = imageUrls,
                                onOpen = { viewingImage = it },
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "المساهم: ${s.submitterName.ifEmpty { "بدون اسم" }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.note.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text("ملاحظة المساهم: ${s.note}", fontSize = 12.sp)
                        }
                        if (s.isPending && hasApprovedText[s.lessonId] == true) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "⚠️ للدرس نص معتمد سابق — الاعتماد يستبدله.",
                                fontSize = 12.sp,
                                color = adminOrange,
                            )
                        }
                        if (!s.isPending && s.status == "rejected" &&
                            s.rejectReason.isNotEmpty()
                        ) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "سبب الرفض: ${s.rejectReason}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
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
                                    enabled = !busy && !bulkBusy,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("اعتماد كما هو")
                                }
                                OutlinedButton(
                                    onClick = { editing = s },
                                    enabled = !busy && !bulkBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("تعديل ثم اعتماد")
                                }
                                IconButton(
                                    onClick = { rejecting = s },
                                    enabled = !busy && !bulkBusy,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "رفض",
                                        tint = MaterialTheme.colorScheme.error,
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
                                        run("") { TranscriptsRepository.deleteDecided(s) }
                                    },
                                    enabled = !busy && !bulkBusy,
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

/** صور صفحات الكتاب في الاقتراح: مصغّرات تفتح بالنقر عرضاً كاملاً. */
@Composable
private fun SubmissionImagesRow(
    paths: List<String>,
    imageUrls: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(paths.size) { i ->
            val path = paths[i]
            val url = imageUrls[path]
            LaunchedEffect(path) {
                if (url == null) {
                    scope.launch {
                        runCatching {
                            imageUrls[path] = TranscriptsRepository.submissionImageUrl(path)
                        }
                    }
                }
            }
            Box(
                Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                    .clickable(enabled = url != null) { url?.let(onOpen) },
                contentAlignment = Alignment.Center,
            ) {
                if (url == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    AsyncImage(
                        model = url,
                        contentDescription = "صورة صفحة ${i + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** عرض صورة الصفحة كاملةً للتدقيق. */
@Composable
fun FullscreenImageDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "صورة الصفحة",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * «تعديل ثم اعتماد» للنص المشروح: تحرير النص/الكتاب/المقطع، استخراج النص
 * من كل صورة (OCR خادمي) وإلحاقه، وخيار إبقاء الصور المرفقة أو إسقاطها.
 * مصغّرات الصور تفتح هنا بحجم كامل **داخل الحوار نفسه**، فيقابل المشرف
 * النص المستخرج بصفحة الكتاب بلا إلغاء الحوار وضياع تعديلاته.
 */
@Composable
private fun EditTranscriptDialog(
    submission: TranscriptSubmission,
    imageUrls: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    hasExistingApproved: Boolean,
    onDismiss: () -> Unit,
    onApprove: (String, String, String, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var text by remember { mutableStateOf(submission.text) }
    var bookTitle by remember { mutableStateOf(submission.bookTitle) }
    var sourceRef by remember { mutableStateOf(submission.sourceRef) }
    var keepImages by remember { mutableStateOf(true) }
    var extracting by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل ثم اعتماد") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hasExistingApproved) {
                    Text(
                        "⚠️ للدرس نص معتمد سابق — الاعتماد يستبدله.",
                        fontSize = 12.sp,
                        color = adminOrange,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                AdminTextField(
                    value = bookTitle,
                    onValueChange = { if (it.length <= 200) bookTitle = it },
                    label = "اسم الكتاب/المتن (اختياري)",
                )
                Spacer(Modifier.height(8.dp))
                AdminTextField(
                    value = sourceRef,
                    onValueChange = { if (it.length <= 300) sourceRef = it },
                    label = "المقطع (من … إلى …) — اختياري",
                )
                Spacer(Modifier.height(8.dp))
                AdminTextField(
                    value = text,
                    onValueChange = { if (it.length <= 20000) text = it },
                    label = "النص المشروح",
                    singleLine = false,
                    minLines = 6,
                    maxLines = 14,
                )
                if (submission.imagePaths.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "الصور المرفقة (${submission.imagePaths.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "انقر الصورة لعرضها كاملةً ومقابلة النص بها.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubmissionImagesRow(
                        paths = submission.imagePaths,
                        imageUrls = imageUrls,
                        onOpen = { previewImage = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            extracting = true
                            scope.launch {
                                try {
                                    val parts = submission.imagePaths.mapNotNull { path ->
                                        runCatching {
                                            TranscriptsRepository.extractText(path)
                                        }.getOrNull()?.takeIf { it.isNotBlank() }
                                    }
                                    if (parts.isEmpty()) {
                                        snack("لم يُستخرج نص من الصور.")
                                    } else {
                                        val joined = parts.joinToString("\n\n")
                                        text = if (text.isBlank()) {
                                            joined
                                        } else {
                                            "$text\n\n$joined"
                                        }.take(20000)
                                        snack("أُلحق النص المستخرج — دقّقه قبل الاعتماد.")
                                    }
                                } catch (e: Exception) {
                                    snack(e.message ?: "تعذّر استخراج النص من الصورة.")
                                }
                                extracting = false
                            }
                        },
                        enabled = !extracting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (extracting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("جارٍ الاستخراج…")
                        } else {
                            Icon(
                                Icons.Filled.TextSnippet,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("استخراج النص من الصور (OCR)")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = keepImages, onCheckedChange = { keepImages = it })
                        Text("إبقاء الصور ظاهرة مع النص المعتمد", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApprove(text.trim(), bookTitle.trim(), sourceRef.trim(), keepImages)
                },
                enabled = text.trim().length >= 10 ||
                    (keepImages && submission.imagePaths.isNotEmpty()),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("اعتماد المعدَّل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )

    // نافذة العرض الكامل تُركَّب بعد الحوار فتعلوه، والتعديلات باقية خلفها
    // كما هي — لا حاجة لإلغاء الحوار لمقابلة النص بصورة الصفحة.
    if (previewImage.isNotEmpty()) {
        FullscreenImageDialog(url = previewImage, onDismiss = { previewImage = "" })
    }
}

@Composable
private fun StatusChip(status: String) {
    val (color, label) = when (status) {
        "approved" -> adminGreen to "نُشرت"
        "approved_edited" -> MaterialTheme.colorScheme.primary to "نُشرت معدَّلة"
        "rejected" -> MaterialTheme.colorScheme.error to "مرفوضة"
        else -> adminOrange to "معلّقة"
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("نشر المعدَّل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun RejectDialog(
    presets: List<String>,
    onDismiss: () -> Unit,
    onReject: (String) -> Unit,
) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("رفض") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
