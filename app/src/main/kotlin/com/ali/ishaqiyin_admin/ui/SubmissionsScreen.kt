package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonSubmission
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.SubmissionsRepository
import com.ali.ishaqiyin_admin.data.TranscriptSubmission
import com.ali.ishaqiyin_admin.data.TranscriptsRepository
import com.ali.ishaqiyin_admin.data.arabicReason
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
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
    // ومعه معرّف المساهمة المعنيّة: يُستهلَك مرّة واحدة كذلك، ولا يُمرَّر
    // إلّا لتبويبه هو، فلا يُمرَّر بحثاً عن معرّف في التبويب الآخر.
    val targetTab = rememberSaveable { tab }
    val targetId = rememberSaveable { SubmissionsTarget.consumeRefId() }
    // العددان من مخزون شارات اللوحة نفسه: لا مستمعَي Firestore إضافيَّين عند
    // كل فتح، ولا رقم يخالف ما تعرضه اللوحة.
    val audioPending by DashboardBadges.pendingAudio().collectAsState()
    val textPending by DashboardBadges.pendingTranscripts().collectAsState()

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
            if (tab == 0) {
                AudioSubmissionsContent(
                    targetId = if (targetTab == 0) targetId else "",
                )
            } else {
                TranscriptSubmissionsContent(
                    targetId = if (targetTab == 1) targetId else "",
                )
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, pending: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (pending > 0) {
            Spacer(Modifier.size(6.dp))
            // حبر الشارة يُشتقّ من لون خلفيتها لا من لون التبويب: حبر
            // التبويب الفاتح على البرتقالي الغامق تباينه 1.2:1 — رقم لا يُقرأ.
            Badge(
                containerColor = adminOrange,
                contentColor = contentColorOn(adminOrange),
            ) { Text("$pending") }
        }
    }
}

/** مدّة تمييز المساهمة القادمة من الإشعار قبل أن يخبو لونها. */
private const val HIGHLIGHT_MS = 4_000L

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

/** اسم المساهم كما يُعرض في الشرائح وفي بطاقة المساهمة (بلا اسم = مجهول). */
private const val NO_NAME = "بدون اسم"

/**
 * مرشِّح باسم المساهم.
 *
 * ⚠️ لماذا شرائح لا حقل كتابة: المساهمات تأتي **دفعاتٍ** من شخص واحد يرفع
 * سلسلةً كاملة، والمشرف لا يتقن الكتابة العربية بالضرورة — فالأسماء تُعرض
 * كما هي في القائمة الحالية ويُنقَر عليها. ولا يظهر الشريط أصلاً ما لم
 * يكن هناك مساهمان فأكثر (شريط بشريحة واحدة عبء بلا فائدة).
 */
@Composable
private fun SubmitterFilterBar(
    names: List<String>,
    counts: Map<String, Int>,
    current: String,
    onSelect: (String) -> Unit,
) {
    if (names.size < 2) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "اعرض مساهمات:",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = current.isEmpty(),
                onClick = { onSelect("") },
                label = { Text("الجميع", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            names.forEach { name ->
                FilterChip(
                    selected = current == name,
                    onClick = { onSelect(if (current == name) "" else name) },
                    label = { Text("$name (${counts[name] ?: 0})", fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
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
                            "${arabicCount(pendingVisible, "مساهمة واحدة", "مساهمتان", "مساهمات", "مساهمة")} بانتظار قرارك"
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

/**
 * سرد أسماء ما سيقع عليه فعل جماعيّ: أوّل ثلاثة بأسمائها، والباقي معدود.
 * الغرض أن يقرأ المشرف **ماذا** يفعل لا **كم** يفعل.
 */
private fun namesPreview(names: List<String>): String {
    val clean = names.map { it.ifBlank { "بدون عنوان" } }
    val head = clean.take(3).joinToString("\n") { "• $it" }
    val rest = clean.size - 3
    if (rest <= 0) return head
    return head + "\n• وغيرها: " + rest
}

// ─── تبويب الدروس الصوتية (سلوك «طلبات النشر» السابق كما هو) ─────────

@Composable
private fun AudioSubmissionsContent(targetId: String = "") {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // الحوارات تُحفظ بمعرّف المساهمة لا بكائنها: تدوير الشاشة كان يغلق
    // «تعديل ثم نشر» ويمحو ما كُتب فيه، والمعرّف وحده يعبر إعادة الإنشاء.
    var approvingId by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    var rejectingId by rememberSaveable { mutableStateOf("") }
    var removingId by rememberSaveable { mutableStateOf("") }
    // صور صفحات النصّ المرفق بالمساهمة: تُعرض للمشرف قبل الموافقة لأنّها
    // تُنشر تحت الدرس فور اعتمادها.
    var viewingImage by remember { mutableStateOf("") }
    val imageUrls = remember { mutableStateMapOf<String, String>() }
    // الفلترة والحسم الجماعي.
    var filter by remember { mutableStateOf(SubFilter.PENDING) }
    // مرشِّح المساهم: فارغ = الجميع. المساهمات تأتي دفعاتٍ من شخص واحد،
    // فحصرها فيه يجعل مراجعة سلسلةٍ كاملة دفعةً واحدة أمراً هيّناً.
    var submitter by remember { mutableStateOf("") }
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
    val byStatus = items.filter {
        when (filter) {
            SubFilter.PENDING -> it.isPending
            SubFilter.DECIDED -> !it.isPending
            SubFilter.ALL -> true
        }
    }
    // أسماء المساهمين الموجودين فعلاً في القائمة المعروضة الآن — لا قائمة
    // ثابتة ولا حقل كتابة: الشريحة تُنقر كما هي.
    val submitterNames = byStatus.map { it.submitterName.ifBlank { NO_NAME } }
        .distinct()
        .sorted()
    val submitterCounts = byStatus.groupingBy { it.submitterName.ifBlank { NO_NAME } }
        .eachCount()
    // اسم اختير ثم اختفى من القائمة (حُسمت كل مساهماته) لا يُترك مرشِّحاً
    // خفيّاً يُفرغ الشاشة بلا سبب ظاهر.
    val activeSubmitter = if (submitter in submitterNames) submitter else ""
    val shown = if (activeSubmitter.isEmpty()) {
        byStatus
    } else {
        byStatus.filter { it.submitterName.ifBlank { NO_NAME } == activeSubmitter }
    }
    val pendingVisible = shown.count { it.isPending }
    val chosen = shown.filter { it.isPending && selected.contains(it.id) }
    // 🎯 المساهمة التي جاء الإشعار من أجلها: تمرير إليها وتمييز يخبو.
    // بلا هذا كانت الشاشة تُفتح على رأس القائمة فيبحث المشرف يدوياً عنها
    // بين العشرات — ويضيع الغرض من حمل معرّفها في حمولة الإشعار.
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf("") }
    var seeked by remember { mutableStateOf(false) }
    // ⛔ كان shown.size ضمن المفاتيح: أيّ لقطة Firestore جديدة خلال مدّة
    // التمييز تُعيد تشغيل الأثر فيرتدّ عند `seeked` قبل تصفير highlighted
    // ⇒ يبقى التمييز إلى نهاية عمر الشاشة. فُصل التصفير في أثر مستقلّ.
    LaunchedEffect(targetId, shown.isNotEmpty()) {
        if (targetId.isEmpty() || seeked) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == targetId }
        if (index < 0) return@LaunchedEffect
        seeked = true
        listState.animateScrollToItem(index)
        highlighted = targetId
    }
    LaunchedEffect(highlighted) {
        if (highlighted.isEmpty()) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        highlighted = ""
    }
    val approving = items.firstOrNull { it.id == approvingId }
    val editing = items.firstOrNull { it.id == editingId }
    val rejecting = items.firstOrNull { it.id == rejectingId }
    val removing = items.firstOrNull { it.id == removingId }

    fun run(doneMsg: String, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                if (doneMsg.isNotEmpty()) snack(doneMsg)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // ⛔ الإلغاء ليس خطأً: ابتلاعه كان يعرض رسالة فشل عند مجرّد
                // مغادرة الشاشة. يُعاد رميه كما في AddLessonScreen.
                throw cancel
            } catch (e: Exception) {
                snack(e.arabicReason())
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
                "$label ${arabicCount(done, "مساهمة واحدة", "مساهمتين", "مساهمات", "مساهمة")}، وأخفقت $failed — أعد المحاولة عليها."
            } else {
                "$label ${arabicCount(done, "مساهمة واحدة", "مساهمتين", "مساهمات", "مساهمة")} دفعة واحدة. ✅"
            },
        )
    }

    approving?.let { s ->
        ConfirmDialog(
            title = "نشر المساهمة كما هي؟",
            body = "«${s.title}»\n${s.categoryName} ← ${s.subcategoryName}\n\n" +
                "سيُنشر الدرس فوراً ويصل المساهم إشعار شكر." +
                // النصّ المرفق يُنشر تحت الدرس في اللحظة نفسها، فتذكيره هنا
                // آخر فرصة لمراجعته قبل ظهوره لكل المستمعين.
                if (s.hasTranscript) "\n\n📄 ومعه النصّ المشروح المرفق يُنشر تحت الدرس." else "",
            confirmLabel = "نشر",
            onDismiss = { approvingId = "" },
            onConfirm = {
                approvingId = ""
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
            onDismiss = { editingId = "" },
            onPublish = { title, cat, sub ->
                editingId = ""
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
            onDismiss = { rejectingId = "" },
            onReject = { reason ->
                rejectingId = ""
                run("رُفضت المساهمة وأُبلغ المساهم بالسبب.") {
                    SubmissionsRepository.reject(s, reason)
                }
            },
        )
    }

    // الإزالة حذف نهائي للسجلّ (وللملف الصوتي في المرفوضة) — لا تمرّ بنقرة
    // واحدة كبقيّة الحذف في اللوحة.
    removing?.let { s ->
        ConfirmDialog(
            title = "إزالة من السجل؟",
            body = "سيُحذف سجلّ «${s.title}» نهائياً" +
                if (s.status == "rejected") "، ومعه ملفه الصوتي المرفوض." else ".",
            confirmLabel = "إزالة",
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { removingId = "" },
            onConfirm = {
                removingId = ""
                run("أُزيلت من السجل.") { SubmissionsRepository.deleteDecided(s) }
            },
        )
    }

    if (viewingImage.isNotEmpty()) {
        FullscreenImageDialog(url = viewingImage, onDismiss = { viewingImage = "" })
    }

    if (confirmBulkPublish) {
        val targets = chosen.toList()
        ConfirmDialog(
            title = "نشر المحدَّد؟",
            // ⚠️ العدد وحده لا يُقرأ: تُسرَد **أسماء** ما سيُنشر (وبقيّتها
            // معدودة) كي يرى المشرف ما بين يديه لا رقماً مجرّداً.
            body = "سيُنشر ما يلي دفعة واحدة كما هو (بلا تعديل عنوان أو قسم)، " +
                "ويصل كل مساهم إشعار شكر:\n\n" + namesPreview(targets.map { it.title }),
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
                            snack("تعذّر النشر الجماعي: ${it.arabicReason()}")
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
                            snack("تعذّر الرفض الجماعي: ${it.arabicReason()}")
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
        SubmitterFilterBar(
            names = submitterNames,
            counts = submitterCounts,
            current = activeSubmitter,
            onSelect = {
                submitter = it
                // تغيير المرشِّح يُخفي مساهمات محدَّدة — تُمسح كي لا يبقى
                // تحديدٌ لا يراه المشرف ثمّ يُحسم من حيث لا يشعر.
                selected.clear()
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
                    if (activeSubmitter.isNotEmpty()) {
                        "لا شيء لـ«$activeSubmitter» ضمن هذه الفلترة —\n" +
                            "اضغط «الجميع» لعرض الباقي."
                    } else if (items.isEmpty()) {
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
            state = listState,
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
                        containerColor = if (highlighted == s.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
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
                                    " • ${"%.1f".format(java.util.Locale.ROOT, s.fileSize / (1024.0 * 1024.0))}MB"
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
                        // إقرارا المرسِل: الإقرار بالحقوق اختياري في
                        // التطبيق، فتمييز من أقرّ ممن لم يُقِرّ هو كلّ فائدة
                        // تخزينهما — ومن دون عرضهما لا يصلان المشرف أصلاً.
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "${if (s.rightsConfirmed) "✔" else "✖"} أقرّ بالحقوق  •  " +
                                "${if (s.termsAccepted) "✔" else "✖"} قبل سياسة المحتوى" +
                                if (s.contentPolicyVersion.isNotEmpty()) {
                                    " (${s.contentPolicyVersion})"
                                } else {
                                    ""
                                },
                            fontSize = 12.sp,
                            color = if (s.rightsConfirmed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                adminOrange
                            },
                        )
                        // النصّ المشروح المرفق يُنشر تحت الدرس فور الموافقة،
                        // فعرضه هنا شرطُ صدق الوعد للمساهم: «تُعرض على
                        // المشرفين قبل النشر».
                        if (s.hasTranscript) {
                            Spacer(Modifier.size(8.dp))
                            SubmissionTranscriptPreview(
                                submission = s,
                                imageUrls = imageUrls,
                                onOpenImage = { viewingImage = it },
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
                                    onClick = { approvingId = s.id },
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
                                    onClick = { editingId = s.id },
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
                                // ⛔ قاعدة واحدة لأزرار الرفض/الحذف: أحمر،
                                // وآخر الصفّ، ومفصول بخطّ عمّا قبله كي لا
                                // يُضغط بالخطأ وهو ملاصق لأزرار النشر.
                                VerticalDivider(
                                    modifier = Modifier
                                        .height(26.dp)
                                        .padding(horizontal = 4.dp),
                                )
                                IconButton(
                                    onClick = { rejectingId = s.id },
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
                            // ⛔ الحذف أحمر دائماً، وآخر ما في البطاقة،
                            // ومفصول بخطّ عمّا قبله.
                            HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { removingId = s.id },
                                    enabled = !busy && !bulkBusy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        "إزالة من السجل",
                                        color = MaterialTheme.colorScheme.error,
                                    )
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
private fun TranscriptSubmissionsContent(targetId: String = "") {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var busy by remember { mutableStateOf(false) }
    // بالمعرّف لا بالكائن: تدوير الشاشة كان يغلق «تعديل ثم اعتماد» ويمحو
    // نصّ الـOCR المدقَّق قبل اعتماده.
    var approvingId by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    var rejectingId by rememberSaveable { mutableStateOf("") }
    var removingId by rememberSaveable { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf("") }
    // روابط صوتيات الدروس تُجلب كسولاً هنا؛ وروابط صور الاقتراحات صارت
    // مكشوفة من كاش المستودع فلا تُعاد كل زيارة.
    val audioUrls = remember { mutableStateMapOf<String, String>() }
    val imageUrls = remember { mutableStateMapOf<String, String>() }
    val hasApprovedText = remember { mutableStateMapOf<String, Boolean>() }
    // الفلترة والحسم الجماعي.
    var filter by remember { mutableStateOf(SubFilter.PENDING) }
    // مرشِّح المساهم: فارغ = الجميع. المساهمات تأتي دفعاتٍ من شخص واحد،
    // فحصرها فيه يجعل مراجعة سلسلةٍ كاملة دفعةً واحدة أمراً هيّناً.
    var submitter by remember { mutableStateOf("") }
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
    val byStatus = items.filter {
        when (filter) {
            SubFilter.PENDING -> it.isPending
            SubFilter.DECIDED -> !it.isPending
            SubFilter.ALL -> true
        }
    }
    // أسماء المساهمين الموجودين فعلاً في القائمة المعروضة الآن — لا قائمة
    // ثابتة ولا حقل كتابة: الشريحة تُنقر كما هي.
    val submitterNames = byStatus.map { it.submitterName.ifBlank { NO_NAME } }
        .distinct()
        .sorted()
    val submitterCounts = byStatus.groupingBy { it.submitterName.ifBlank { NO_NAME } }
        .eachCount()
    // اسم اختير ثم اختفى من القائمة (حُسمت كل مساهماته) لا يُترك مرشِّحاً
    // خفيّاً يُفرغ الشاشة بلا سبب ظاهر.
    val activeSubmitter = if (submitter in submitterNames) submitter else ""
    val shown = if (activeSubmitter.isEmpty()) {
        byStatus
    } else {
        byStatus.filter { it.submitterName.ifBlank { NO_NAME } == activeSubmitter }
    }
    val pendingVisible = shown.count { it.isPending }
    val chosen = shown.filter { it.isPending && selected.contains(it.id) }
    // 🎯 نظير تبويب الصوتيات: تمرير إلى الاقتراح الذي جاء الإشعار من أجله.
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf("") }
    var seeked by remember { mutableStateOf(false) }
    // ⛔ كان shown.size ضمن المفاتيح: أيّ لقطة Firestore جديدة خلال مدّة
    // التمييز تُعيد تشغيل الأثر فيرتدّ عند `seeked` قبل تصفير highlighted
    // ⇒ يبقى التمييز إلى نهاية عمر الشاشة. فُصل التصفير في أثر مستقلّ.
    LaunchedEffect(targetId, shown.isNotEmpty()) {
        if (targetId.isEmpty() || seeked) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == targetId }
        if (index < 0) return@LaunchedEffect
        seeked = true
        listState.animateScrollToItem(index)
        highlighted = targetId
    }
    LaunchedEffect(highlighted) {
        if (highlighted.isEmpty()) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        highlighted = ""
    }
    val approving = items.firstOrNull { it.id == approvingId }
    val editing = items.firstOrNull { it.id == editingId }
    val rejecting = items.firstOrNull { it.id == rejectingId }
    val removing = items.firstOrNull { it.id == removingId }

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
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // ⛔ الإلغاء ليس خطأً: ابتلاعه كان يعرض رسالة فشل عند مجرّد
                // مغادرة الشاشة. يُعاد رميه كما في AddLessonScreen.
                throw cancel
            } catch (e: Exception) {
                snack(e.arabicReason())
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
            if (skipped > 0) append("، وتُخُطّي $skipped لأن درسها حُسم في الدفعة نفسها")
        }
        snack("$label ${arabicCount(done, "اقتراحاً واحداً", "اقتراحين", "اقتراحات", "اقتراحاً")}$extra.")
    }

    /**
     * المشغّل يُفتاح بمعرّف الاقتراح لا بمعرّف الدرس: اقتراحان لدرس واحد
     * كانا يعرضان شريطي تشغيل لصوتية واحدة. والكاش يبقى على معرّف الدرس
     * فلا يتكرّر جلب الرابط نفسه.
     */
    fun playLesson(submissionId: String, lessonId: String) {
        val cached = audioUrls[lessonId]
        if (cached != null) {
            player.toggle(submissionId, cached)
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
                    player.toggle(submissionId, url)
                }
            } catch (e: Exception) {
                snack("تعذّر جلب صوتية الدرس: ${e.arabicReason()}")
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
            onDismiss = { approvingId = "" },
            onConfirm = {
                approvingId = ""
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
            onDismiss = { editingId = "" },
            onApprove = { text, bookTitle, sourceRef, keepImages ->
                editingId = ""
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
            onDismiss = { rejectingId = "" },
            onReject = { reason ->
                rejectingId = ""
                run("رُفض الاقتراح وأُبلغ المساهم بالسبب.") {
                    TranscriptsRepository.reject(s, reason)
                }
            },
        )
    }

    // الحذف خادميّاً نهائي وشامل لصور الاقتراح — فلا يقع بنقرة واحدة.
    removing?.let { s ->
        ConfirmDialog(
            title = "إزالة من السجل؟",
            // الاسم لا الوصف المجرّد: المشرف يقرأ **أيّ** اقتراح يحذف.
            body = buildString {
                append("«${s.lessonTitle}»\n\n")
                append("سيُحذف سجلّ هذا الاقتراح")
                if (s.imagePaths.isNotEmpty()) append(" وصوره")
                append(" نهائياً. لا يمكن التراجع.")
            },
            confirmLabel = "إزالة",
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { removingId = "" },
            onConfirm = {
                removingId = ""
                run("أُزيل من السجل.") { TranscriptsRepository.deleteDecided(s) }
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
                // أسماء الدروس المعنيّة أوّلاً — الرقم وحده لا يُراجَع.
                append("سيُعتمد ما يلي كما هو ويصل كل مساهم إشعار شكر:\n\n")
                append(namesPreview(targets.map { it.lessonTitle }))
                if (replaced > 0) {
                    append("\n\n⚠️ $replaced منها لدروس لها نص معتمد سابق سيُستبدل.")
                }
                if (duplicated > 0) {
                    append(
                        "\n\n⚠️ ${arabicCount(duplicated, "اقتراح واحد", "اقتراحان", "اقتراحات", "اقتراحاً")} يخصّ درساً محدَّداً مرّتين — " +
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
                            // ما اعتُمد فعلاً وحده يُعلَّم «له نص معتمد»؛ ما
                            // أخفق أو تُخطّي يُنسى كاشه كي يُسأل عنه من جديد
                            // بدل تحذير استبدال كاذب.
                            outcome.approvedLessonIds.forEach { markApproved(it) }
                            targets.map { it.lessonId }.distinct()
                                .filterNot { it in outcome.approvedLessonIds }
                                .forEach { lessonId ->
                                    hasApprovedText.remove(lessonId)
                                    TranscriptsRepository.invalidateTranscript(lessonId)
                                }
                            finishBulk(
                                "اعتُمد",
                                outcome.done,
                                outcome.failed,
                                outcome.skippedDuplicates,
                            )
                        }
                        .onFailure {
                            bulkBusy = false
                            snack("تعذّر الاعتماد الجماعي: ${it.arabicReason()}")
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
                            snack("تعذّر الرفض الجماعي: ${it.arabicReason()}")
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
        SubmitterFilterBar(
            names = submitterNames,
            counts = submitterCounts,
            current = activeSubmitter,
            onSelect = {
                submitter = it
                // تغيير المرشِّح يُخفي مساهمات محدَّدة — تُمسح كي لا يبقى
                // تحديدٌ لا يراه المشرف ثمّ يُحسم من حيث لا يشعر.
                selected.clear()
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
                    if (activeSubmitter.isNotEmpty()) {
                        "لا شيء لـ«$activeSubmitter» ضمن هذه الفلترة —\n" +
                            "اضغط «الجميع» لعرض الباقي."
                    } else if (items.isEmpty()) {
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
            state = listState,
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
                val isPlaying = player.playingId == s.id && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (highlighted == s.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
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
                                IconButton(onClick = { playLesson(s.id, s.lessonId) }) {
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
                            id = s.id,
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
                            // «…» والزرّ يتبعان الفيض الحقيقي لا عدد الحروف:
                            // نصّ من أربعة أسطر طويلة كان يُقصّ بلا أيّ إشارة.
                            var overflowed by remember(s.id) { mutableStateOf(false) }
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
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { overflowed = it.hasVisualOverflow },
                                )
                            }
                            if (expanded || overflowed) {
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
                                    onClick = { approvingId = s.id },
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
                                    onClick = { editingId = s.id },
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
                                // ⛔ قاعدة واحدة لأزرار الرفض/الحذف: أحمر،
                                // وآخر الصفّ، ومفصول بخطّ عمّا قبله كي لا
                                // يُضغط بالخطأ وهو ملاصق لأزرار النشر.
                                VerticalDivider(
                                    modifier = Modifier
                                        .height(26.dp)
                                        .padding(horizontal = 4.dp),
                                )
                                IconButton(
                                    onClick = { rejectingId = s.id },
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
                            // ⛔ الحذف أحمر دائماً، وآخر ما في البطاقة،
                            // ومفصول بخطّ عمّا قبله.
                            HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { removingId = s.id },
                                    enabled = !busy && !bulkBusy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        "إزالة من السجل",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 📄 معاينة النصّ المشروح المرفق بمساهمة صوتيّة — قبل زرّ الموافقة.
 *
 * الدالّة السحابيّة تنشره في `lesson_transcripts` لحظةَ الاعتماد، فبلا هذه
 * المعاينة يوافق المشرف على نصٍّ وصورِ صفحاتٍ لم يرَها قطّ.
 */
@Composable
private fun SubmissionTranscriptPreview(
    submission: LessonSubmission,
    imageUrls: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    onOpenImage: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.TextSnippet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "نصّ مشروح مرفق — يُنشر تحت الدرس عند الموافقة",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val source = listOf(submission.transcriptBookTitle, submission.transcriptSourceRef)
            .filter { it.isNotEmpty() }
            .joinToString(" — ")
        if (source.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (submission.transcriptText.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            var expanded by remember(submission.id) { mutableStateOf(false) }
            // «عرض كاملاً» يتبع الفيض الحقيقي لا عدد الحروف — كما في تبويب
            // النصوص المشروحة تماماً.
            var overflowed by remember(submission.id) { mutableStateOf(false) }
            Text(
                submission.transcriptText,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflowed = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            )
            if (expanded || overflowed) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "طيّ النص" else "عرض النص كاملاً",
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (submission.transcriptImagePaths.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(
                "صور الصفحات (${submission.transcriptImagePaths.size}) — انقرها للعرض الكامل",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            SubmissionImagesRow(
                paths = submission.transcriptImagePaths,
                imageUrls = imageUrls,
                onOpen = onOpenImage,
            )
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(paths.size) { i ->
            val path = paths[i]
            val url = imageUrls[path]
            // الجلب داخل الأثر نفسه: بإطلاقه في scope الشاشة كان ينجو من
            // خروج العنصر من القائمة فلا يُلغى.
            LaunchedEffect(path) {
                if (imageUrls[path] == null) {
                    runCatching {
                        imageUrls[path] = TranscriptsRepository.submissionImageUrl(path)
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

/**
 * عرض صورة الصفحة كاملةً للتدقيق — ملء الشاشة وبعرضها كاملاً مع تمرير
 * رأسي: صفحة طويلة (وأشدّها الناتجة عن الدمج) كانت تُحشر داخل ارتفاع
 * الحوار فتُرسم شريطاً رفيعاً لا يُقرأ.
 */
@Composable
fun FullscreenImageDialog(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "صورة الصفحة",
                contentScale = ContentScale.FillWidth,
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
    // ما يكتبه المشرف هنا (ونصّ الـOCR المدقَّق) يعبر إعادة إنشاء النشاط.
    var text by rememberSaveable(submission.id) { mutableStateOf(submission.text) }
    var bookTitle by rememberSaveable(submission.id) { mutableStateOf(submission.bookTitle) }
    var sourceRef by rememberSaveable(submission.id) { mutableStateOf(submission.sourceRef) }
    var keepImages by rememberSaveable(submission.id) { mutableStateOf(true) }
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
                                // سبب فشل الخادم (مثل «فعّل Cloud Vision API»)
                                // كان يُبتلع فيُعرض الفشل كأن الصور بلا نص.
                                var lastError: Throwable? = null
                                val parts = submission.imagePaths.mapNotNull { path ->
                                    runCatching {
                                        TranscriptsRepository.extractText(path)
                                    }.onFailure { lastError = it }
                                        .getOrNull()?.takeIf { it.isNotBlank() }
                                }
                                if (parts.isEmpty()) {
                                    snack(
                                        lastError?.let { "تعذّر الاستخراج: ${it.arabicReason()}" }
                                            ?: "لم يُستخرج نص من الصور.",
                                    )
                                } else {
                                    val joined = parts.joinToString("\n\n")
                                    val combined =
                                        if (text.isBlank()) joined else "$text\n\n$joined"
                                    val trimmed = combined.length > 20000
                                    text = combined.take(20000)
                                    snack(
                                        if (trimmed) {
                                            "أُلحق النص وقُصّ عند 20 ألف حرف — راجع آخره."
                                        } else {
                                            "أُلحق النص المستخرج — دقّقه قبل الاعتماد."
                                        },
                                    )
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
                    // النقص يُشرح بعينه — كما في محرّر النص المشروح — بدل زرّ
                    // معطَّل بلا سبب ظاهر.
                    if (text.trim().length < 10 &&
                        !(keepImages && submission.imagePaths.isNotEmpty())
                    ) {
                        snack("أدخل نص المقطع (10 أحرف على الأقل) أو أبقِ الصور المرفقة.")
                        return@Button
                    }
                    onApprove(text.trim(), bookTitle.trim(), sourceRef.trim(), keepImages)
                },
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
    // العنوان المكتوب يعبر إعادة إنشاء النشاط؛ والقسمان يُشتقّان من الطلب
    // متى وصلت قوائمهما (بعد إعادة الإنشاء تُجلب من جديد).
    var title by rememberSaveable(submission.id) { mutableStateOf(submission.title) }
    var category by remember(submission.id, categories) {
        mutableStateOf(categories.firstOrNull { it.id == submission.categoryId })
    }
    var subcategory by remember(submission.id, subcategories) {
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
