package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.TrashRepository
import com.ali.ishaqiyin_admin.data.TrashedLesson
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🗑️ «سلة المحذوفات» — كل درس حُذف (منفرداً أو بحذف قسم كامل) يبقى هنا
 * 30 يوماً بملفه الصوتي ونصّه المشروح: استمع للتأكد، ثم استعد بنقرة أو
 * احذف نهائياً. ما تجاوز مهلته يُنظَّف تلقائياً كل ليلة.
 */
@Composable
fun TrashScreen(isOwner: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<TrashedLesson?>(null) }
    var purging by remember { mutableStateOf<TrashedLesson?>(null) }
    var emptying by remember { mutableStateOf(false) }

    val items by remember { TrashRepository.watchAll() }
        .collectAsState(initial = emptyList())

    // 🗂️ الأقسام المحذوفة: تبويب ثانٍ بجانب الدروس — القسم صار يُنقل إلى
    // السلة كالدرس تماماً، فحذفٌ بالخطأ يعود بنقرة بلا إعادة بناء يدويّة.
    val sections by remember { DeletedSectionsRepository.watchAll() }
        .collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var restoringSection by remember { mutableStateOf<TrashedSection?>(null) }
    var purgingSection by remember { mutableStateOf<TrashedSection?>(null) }

    // الأقسام تُجلب مرّة لحلّ اسم القسم محليّاً للدروس القديمة التي حُذفت
    // بلا `categoryName/subcategoryName` في وثيقتها — بلا هذا يسقط سطر
    // القسم كلّه فلا يميّز المشرف بين دروس متشابهة العناوين قبل قراره.
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
    }

    /** اسم القسم المعروض: المخزَّن في الوثيقة، وإلّا يُحلّ من المعرّفات. */
    fun sectionOf(item: TrashedLesson): String {
        val sub = subcategories.firstOrNull { it.id == item.subcategoryId }
        val catId = item.categoryId.ifEmpty { sub?.categoryId.orEmpty() }
        val catName = item.categoryName.ifBlank {
            categories.firstOrNull { it.id == catId }?.name.orEmpty()
        }
        val subName = item.subcategoryName.ifBlank { sub?.name.orEmpty() }
        return listOf(catName, subName).filter(String::isNotBlank).joinToString(" ← ")
    }

    fun run(doneMsg: String, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                if (doneMsg.isNotEmpty()) snack(doneMsg)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // ⛔ الإلغاء ليس خطأً: ابتلاعه كان يعرض «تعذّر تنفيذ العمليّة»
                // كاذبةً على مضيف التطبيق بعد مغادرة الشاشة أثناء استعادة أو
                // حذف نجح على الخادم غالباً. يُعاد رميه كما في SubmissionsScreen.
                throw cancel
            } catch (e: Exception) {
                snack("تعذّر تنفيذ العملية: ${e.arabicReason()}")
            }
            busy = false
        }
    }

    restoringSection?.let { item ->
        ConfirmDialog(
            title = "استعادة القسم؟",
            body = "«${item.name.ifEmpty { "بدون اسم" }}»\n" +
                "يعود القسم بمعرّفه الأصلي، فيرجع إليه كل درس تستعيده من السلة.",
            confirmLabel = "استعادة",
            onDismiss = { restoringSection = null },
            onConfirm = {
                restoringSection = null
                run("استُعيد القسم. ✅") { DeletedSectionsRepository.restore(item) }
            },
        )
    }

    purgingSection?.let { item ->
        ConfirmDialog(
            title = "حذف نهائي؟",
            body = "«${item.name.ifEmpty { "بدون اسم" }}»\n" +
                "⚠️ يُحذف القسم من السلة نهائياً ولا يمكن استعادته بعدها أبداً.",
            confirmLabel = "حذف نهائياً",
            onDismiss = { purgingSection = null },
            onConfirm = {
                purgingSection = null
                run("حُذف القسم نهائياً.") { DeletedSectionsRepository.purge(item) }
            },
        )
    }

    restoring?.let { item ->
        ConfirmDialog(
            title = "استعادة الدرس؟",
            body = "«${item.title.ifEmpty { "بدون عنوان" }}»\n" +
                "يعود الدرس إلى مكانه في التطبيق فوراً" +
                if (item.hasTranscript) " مع نصّه المشروح." else ".",
            confirmLabel = "استعادة",
            onDismiss = { restoring = null },
            onConfirm = {
                restoring = null
                // البطاقة تختفي مع الوثيقة فيختفي شريط التحكّم، والصوت يواصل
                // التشغيل بلا وسيلة إيقاف — فيُوقَف قبل زوالها.
                if (player.playingId == item.id) player.stop()
                run("استُعيد الدرس. ✅") { TrashRepository.restore(item) }
            },
        )
    }

    purging?.let { item ->
        ConfirmDialog(
            title = "حذف نهائي؟",
            body = "«${item.title.ifEmpty { "بدون عنوان" }}»\n" +
                "⚠️ يُحذف الدرس وملفه الصوتي نهائياً ولا يمكن استعادته بعدها أبداً.",
            confirmLabel = "حذف نهائياً",
            onDismiss = { purging = null },
            onConfirm = {
                purging = null
                if (player.playingId == item.id) player.stop()
                run("حُذف نهائياً.") { TrashRepository.purge(item) }
            },
        )
    }

    if (emptying) {
        // ⚠️ الفعل الوحيد في اللوحة بلا مصدر استرجاع (لا سلّة للسلّة) —
        // فلا يكفيه زرّ «نعم»: ضغطٌ متّصل ثلاث ثوانٍ يمتلئ أمام العين.
        //
        // ⛔ العدد المعروض عدٌّ خادميّ كامل لا `items.size` المحدود ببثّ
        // الـ100: كان المالك يوافق على رقم أقلّ ممّا سيُحذف فعلاً في فعل لا
        // رجوع فيه. و«كاملةً» أُسقطت من العنوان — التفريغ للدروس وحدها ولا
        // يمسّ تبويب «الأقسام».
        var total by remember { mutableStateOf(-1) }
        LaunchedEffect(Unit) {
            total = runCatching { TrashRepository.watchCount().first() }
                .getOrDefault(items.size)
        }
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text("تفريغ سلة الدروس؟") },
            text = {
                Column {
                    Text(
                        if (total < 0) {
                            "جارٍ عدّ ما في السلة…"
                        } else {
                            // صياغة محايدة للعدد: «درس واحد وملفاتها» كانت
                            // لحناً (ضمير جمع مؤنّث يعود على مفرد مذكّر).
                            "⚠️ سيُحذف كل ما في تبويب «الدروس» " +
                                "(${lessonsCountLabel(total)}) مع الملفات الصوتية " +
                                "نهائياً ولا يمكن استعادة أي منها بعدها أبداً.\n" +
                                "الأقسام المحذوفة لا يشملها التفريغ.\n\n" +
                                "هذا الإجراء للمالك فقط."
                        },
                    )
                    Spacer(Modifier.size(16.dp))
                    HoldToConfirmButton(
                        label = "اضغط مع الاستمرار 3 ثوانٍ للحذف النهائي",
                        // لا تأكيد قبل وصول العدّ — موافقة على معلومة ناقصة.
                        enabled = total >= 0,
                        onConfirmed = {
                            emptying = false
                            // كل البطاقات ستزول، فأيّ معاينة جارية تفقد شريطها.
                            player.stop()
                            run("") {
                                val purged = TrashRepository.emptyAll()
                                snack("فُرّغت السلة — حُذف ${lessonsCountLabel(purged)} نهائياً.")
                            }
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { emptying = false }) { Text("إلغاء") }
            },
        )
    }

    AdminScaffold(
        title = "سلة المحذوفات",
        onBack = onBack,
        actions = {
            // تفريغ السلة دفعة واحدة: زرّ للمالك وحده (والخادم يتحقق أيضاً).
            // يظهر دائماً كي يعرف المالك مكانه، ويتعطّل فقط والسلة فارغة.
            if (isOwner) {
                IconButton(
                    onClick = { emptying = true },
                    enabled = !busy && items.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "تفريغ السلة",
                        tint = if (items.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // تبويبان: الدروس المحذوفة والأقسام المحذوفة — كلاهما بنفس أسلوب
            // البطاقات وزرّ الاستعادة، فلا يتعلّم المشرف شيئاً جديداً.
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    // البثّ محدود بـ100 (سقف WATCH_LIMIT): بلوغه يعني «مئة
                    // فأكثر» لا عدّاً كاملاً — تُعلَّم بـ«+» كي لا يناقض
                    // العدّ الخادميّ على بطاقة اللوحة وحوار التفريغ.
                    text = {
                        Text("الدروس (${if (items.size >= 100) "+100" else "${items.size}"})")
                    },
                )
                Tab(
                    selected = tab == 1,
                    onClick = {
                        // بطاقات الدروس تُزال مع تبويبها ومعها شريط التحكّم،
                        // فكانت معاينةٌ جارية تواصل الصوت بلا زرّ إيقاف ظاهر.
                        if (tab != 1) player.stop()
                        tab = 1
                    },
                    text = { Text("الأقسام (${sections.size})") },
                )
            }
            if (tab == 0) {
            if (items.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "السلة فارغة.\nكل درس تحذفه يبقى هنا 30 يوماً قابلاً " +
                            "للاستعادة قبل حذفه النهائي تلقائياً.",
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "الدروس المحذوفة تبقى 30 يوماً ثم تُحذف نهائياً تلقائياً.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                items(items.size) { index ->
                    val item = items[index]
                    val isPlaying = player.playingId == item.id && player.playing
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainer,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IconButton(
                                        onClick = { player.toggle(item.id, item.audioUrl) },
                                        enabled = item.audioUrl.isNotEmpty(),
                                    ) {
                                        Icon(
                                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = "استماع",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title.ifEmpty { "بدون عنوان" },
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val section = sectionOf(item)
                                    if (section.isNotEmpty()) {
                                        Text(
                                            section,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // برتقالي المهلة: نظيره الفاتح في الوضع الداكن.
                                val deadline = adminOrange
                                Box(
                                    Modifier
                                        .background(
                                            deadline.copy(alpha = 0.12f),
                                            RoundedCornerShape(999.dp),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        arabicCount(item.daysLeft, "يوم واحد", "يومان", "أيام", "يوماً"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = deadline,
                                    )
                                }
                            }
                            // شريط المعاينة: تقدّم قابل للسحب وقفز ±٣٠ث وسرعة —
                            // يراجع المشرف الدرس المحذوف قبل استعادته أو محوه.
                            PreviewPlayerBar(
                                state = player,
                                id = item.id,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                buildString {
                                    append("حذفه ${item.deletedBy.ifEmpty { "غير معروف" }}")
                                    if (item.deletedAtMs > 0) {
                                        append(
                                            " • " + SimpleDateFormat(
                                                "yyyy/MM/dd HH:mm",
                                                Locale.ENGLISH,
                                            ).format(Date(item.deletedAtMs)),
                                        )
                                    }
                                    if (item.hasTranscript) append(" • معه نص مشروح 📖")
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { restoring = item },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = adminGreen,
                                        // الحبر يُشتقّ من الخلفيّة في الوضعين: لون
                                        // محتوى البطاقة فوق الأخضر الداكن كان يرسب
                                        // في التباين بالوضع الفاتح.
                                        contentColor = contentColorOn(adminGreen),
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.RestoreFromTrash,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("استعادة")
                                }
                                OutlinedButton(
                                    onClick = { purging = item },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("حذف نهائي", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            } else {
                if (sections.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "لا أقسام محذوفة.\nكل قسم تحذفه يبقى هنا 30 يوماً " +
                                "قابلاً للاستعادة بمعرّفه الأصلي.",
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "استعادة القسم تعيده بمعرّفه الأصلي، فيرجع إليه كل درس " +
                                "تستعيده من تبويب الدروس بلا نقل يدويّ.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    items(sections.size) { index ->
                        val item = sections[index]
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircleIcon(
                                        Icons.Filled.Folder,
                                        MaterialTheme.colorScheme.surfaceContainer,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.name.ifEmpty { "بدون اسم" },
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (item.isCategory) "قسم رئيسي" else "قسم فرعي",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    val deadline = adminOrange
                                    Box(
                                        Modifier
                                            .background(
                                                deadline.copy(alpha = 0.12f),
                                                RoundedCornerShape(999.dp),
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            arabicCount(
                                                item.daysLeft,
                                                "يوم واحد",
                                                "يومان",
                                                "أيام",
                                                "يوماً",
                                            ),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = deadline,
                                        )
                                    }
                                }
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    buildString {
                                        append("حذفه ${item.deletedBy.ifEmpty { "غير معروف" }}")
                                        if (item.deletedAtMs > 0) {
                                            append(
                                                " • " + SimpleDateFormat(
                                                    "yyyy/MM/dd HH:mm",
                                                    Locale.ENGLISH,
                                                ).format(Date(item.deletedAtMs)),
                                            )
                                        }
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(8.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { restoringSection = item },
                                        enabled = !busy,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = adminGreen,
                                            contentColor = contentColorOn(adminGreen),
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.Filled.RestoreFromTrash,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.size(4.dp))
                                        Text("استعادة")
                                    }
                                    OutlinedButton(
                                        onClick = { purgingSection = item },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.Filled.DeleteForever,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.size(4.dp))
                                        Text("حذف نهائي", color = MaterialTheme.colorScheme.error)
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
