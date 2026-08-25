package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** خيارات مدّة التمييز المعروضة للمشرف. */
enum class FeatureDuration(val label: String, val hours: Long?) {
    H12("12 ساعة", 12),
    H24("24 ساعة", 24),
    D2("يومان", 48),
    D3("3 أيام", 72),
    W1("أسبوع", 24 * 7),
    Forever("دائم — بلا مدّة", null);

    /** لحظة الانتهاء المطلقة، أو null للتمييز الدائم. */
    fun untilMs(): Long? = hours?.let { System.currentTimeMillis() + it * 3600_000L }
}

/** «يبقى ٥ س ٢٠ د» — نصّ المدّة المتبقّية بالعربية. */
fun remainingLabel(untilMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (untilMs == null) return "دائم"
    val left = untilMs - now
    if (left <= 0) return "انتهت مدّته"
    val days = left / (24 * 3600_000L)
    val hours = (left % (24 * 3600_000L)) / 3600_000L
    val minutes = (left % 3600_000L) / 60_000L
    return when {
        days > 0 -> "يبقى $days ي و$hours س"
        hours > 0 -> "يبقى $hours س و$minutes د"
        else -> "يبقى $minutes د"
    }
}

/**
 * ⭐ «مختارات المنبر» — المكان المخصّص لإدارة الدروس المميّزة:
 * عرض كلّ ما هو مميّز الآن، والمدّة المتبقّية لكلّ درس، وإزالة التمييز
 * فوراً (فيسقط من التطبيق العام في الحال)، وتغيير المدّة متى شئت.
 */
@Composable
fun FeaturedScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    // ↩️ إلغاء التمييز فعل رخيص قابل للرجوع تماماً — يقع فوراً ويُتاح
    // التراجع عنه عشر ثوانٍ، بدل حوار تأكيد يُقرأ مرّة ويُتخطّى دائماً.
    val undoBar = rememberUndoBar()
    val lessons by remember { AdminRepository.watchFeatured() }
        .collectAsState(initial = emptyList())

    // نبضة كل نصف دقيقة تُحدّث العدّ التنازلي بلا إعادة قراءة من الشبكة.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    var durationFor by remember { mutableStateOf<Lesson?>(null) }
    var busyId by remember { mutableStateOf("") }
    // التنظيف تسلسليّ وقد يطول: بلا هذا العلم يبقى الزرّ مفعّلاً فيُطلَق مرّتين
    // وتظهر رسالتان متناقضتان.
    var cleaning by remember { mutableStateOf(false) }

    // الدروس التي أُزيل تمييزها في هذه اللحظة ولم تنقضِ مهلة تراجعها:
    // تُخفى من القائمة فوراً بينما الكتابة في القاعدة مؤجَّلة، فالتراجع
    // يعيد الدرس إلى موضعه ومدّته بعينهما بلا كتابة أصلاً.
    val pendingRemoval = remember { mutableStateListOf<String>() }

    // ما ينتظر انقضاء مهلة تراجعه لا يُعرض — كأنّه أُزيل، وهو لم يُكتب بعد.
    val shown = lessons.filterNot { pendingRemoval.contains(it.id) }
    val expired = shown.filter { it.featuredUntilMs != null && it.featuredUntilMs <= now }
    val active = shown.filterNot { it.featuredUntilMs != null && it.featuredUntilMs <= now }

    fun unfeature(lesson: Lesson) {
        pendingRemoval.add(lesson.id)
        undoBar.show(
            message = "أُزيل «${lesson.title.ifBlank { "الدرس" }}» من مختارات المنبر.",
            onUndo = { pendingRemoval.remove(lesson.id) },
            // ⛔ بلا scope.launch: الكتابة تجري في نطاق UndoBar الدائم —
            // لفّها بنطاق الشاشة كان يجعل مغادرتها قبل انقضاء المهلة تُبطل
            // الإزالة بصمت (launch على نطاق مُلغى لا ينفّذ شيئاً) فيبقى
            // الدرس مميّزاً في القاعدة والتطبيق العام إلى الأبد.
            onCommit = {
                busyId = lesson.id
                runCatching { AdminRepository.setLessonFeatured(lesson.id, false) }
                    .onFailure {
                        // فشل الكتابة يعيد الدرس للعرض: إخفاؤه وهو
                        // مميّز في القاعدة يخدع المشرف.
                        pendingRemoval.remove(lesson.id)
                        snack("تعذّرت الإزالة: ${it.arabicReason()}")
                    }
                busyId = ""
                pendingRemoval.remove(lesson.id)
            },
        )
    }

    /**
     * تنظيف المنتهية: كان حوار تأكيد بعدد مجرّد. صار يقع فوراً بشريط تراجع
     * واحد يُعيد كلّ ما أُخفي — والكتابة لا تقع إلّا بعد انقضاء المهلة.
     */
    fun cleanExpired(batch: List<Lesson>) {
        val ids = batch.map { it.id }
        pendingRemoval.addAll(ids)
        undoBar.show(
            message = "أُزيل التمييز عن ${lessonsCountLabel(batch.size)} انتهت مدّتها.",
            onUndo = { pendingRemoval.removeAll(ids) },
            // ⛔ بلا scope.launch — نفس علّة [unfeature]: نطاق الشاشة يُلغى
            // بمغادرتها فلا يُنظَّف شيء رغم شريط «أُزيل التمييز».
            onCommit = {
                cleaning = true
                var failed = 0
                batch.forEach { lesson ->
                    runCatching { AdminRepository.setLessonFeatured(lesson.id, false) }
                        .onFailure {
                            failed++
                            pendingRemoval.remove(lesson.id)
                        }
                }
                if (failed > 0) {
                    snack("نُظّفت ${batch.size - failed}، وتعذّر $failed — أعد المحاولة.")
                }
                pendingRemoval.removeAll(ids)
                cleaning = false
            },
        )
    }

    durationFor?.let { lesson ->
        FeatureDurationSheet(
            lessonTitle = lesson.title,
            currentUntilMs = lesson.featuredUntilMs,
            onDismiss = { durationFor = null },
            onPick = { duration ->
                durationFor = null
                scope.launch {
                    runCatching {
                        AdminRepository.setLessonFeatured(lesson.id, true, duration.untilMs())
                    // arabicReason: نصّ الاستثناء الخام إنجليزيّ تقنيّ لا
                    // يفهمه جمهور اللوحة.
                    }.onSuccess { snack("مدّة التمييز الآن: ${duration.label}") }
                        .onFailure { snack("تعذّر التعديل: ${it.arabicReason()}") }
                }
            },
        )
    }

    UndoBarOverlay(undoBar) {
        AdminScaffold(
            title = "مختارات المنبر",
            onBack = onBack,
            actions = {
                if (expired.isNotEmpty()) {
                    IconButton(onClick = { cleanExpired(expired.toList()) }, enabled = !cleaning) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = "تنظيف المنتهية")
                    }
                }
            },
        ) { padding ->
            if (shown.isEmpty()) {
                Box(
                    Modifier.padding(padding).fillMaxSize().padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(58.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا دروس مميّزة الآن",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "ميّز درساً من «التعديل والبحث» بالنجمة ⭐ ليظهر أعلى " +
                                "التطبيق العام، واختر مدّة بقائه.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            lineHeight = 21.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                return@AdminScaffold
            }

            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    // تدرّج الترويسة يتبع السمة، وحبره يُشتقّ من أوّل لونيه لأنّ
                    // الذهب في الوضع الداكن فاتح لا يحتمل نصّاً أبيض.
                    val bannerInk = contentColorOn(adminGold)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(listOf(adminGold, adminOrange)),
                                RoundedCornerShape(14.dp),
                            )
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = bannerInk,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(
                                    "${lessonsCountLabel(active.size)} في مختارات المنبر",
                                    color = bannerInk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    "تظهر أعلى التطبيق العام، وتسقط منه فور انتهاء " +
                                        "المدّة أو إزالة التمييز.",
                                    color = bannerInk.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }

                if (expired.isNotEmpty()) {
                    item { SectionTitle("انتهت مدّتها (${expired.size})") }
                    items(expired, key = { it.id }) { lesson ->
                        FeaturedRow(
                            lesson = lesson,
                            now = now,
                            busy = busyId == lesson.id,
                            onChangeDuration = { durationFor = lesson },
                            onRemove = { unfeature(lesson) },
                        )
                    }
                }

                if (active.isNotEmpty()) {
                    item { SectionTitle("مميّزة الآن (${active.size})") }
                    items(active, key = { it.id }) { lesson ->
                        FeaturedRow(
                            lesson = lesson,
                            now = now,
                            busy = busyId == lesson.id,
                            onChangeDuration = { durationFor = lesson },
                            onRemove = { unfeature(lesson) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedRow(
    lesson: Lesson,
    now: Long,
    busy: Boolean,
    onChangeDuration: () -> Unit,
    onRemove: () -> Unit,
) {
    val until = lesson.featuredUntilMs
    val isExpired = until != null && until <= now
    val accent = when {
        isExpired -> MaterialTheme.colorScheme.error
        until == null -> MaterialTheme.colorScheme.primary
        until - now < 6 * 3600_000L -> adminOrange // أوشك على السقوط
        else -> adminGold
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(accent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (until == null) Icons.Filled.AllInclusive else Icons.Filled.Timer,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    lesson.title.ifBlank { "بدون عنوان" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccessTime,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        remainingLabel(until, now),
                        fontSize = 11.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (lesson.views > 0) {
                        Spacer(Modifier.size(10.dp))
                        Icon(
                            Icons.Filled.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.size(3.dp))
                        Text("${lesson.views}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = accent,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // هدف لمس 48dp لكلا الزرّين (جمهور اللوحة كبار سنّ).
                TextButton(
                    onClick = onChangeDuration,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("تغيير المدّة", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                // ⛔ قاعدة بصريّة واحدة للحذف في اللوحة كلّها: أحمر دائماً،
                // وآخر عنصر دائماً، ومفصول بخطّ عمّا قبله — كي لا يُضغَط
                // بالخطأ وهو مجاور لأزرار عاديّة.
                VerticalDivider(
                    modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
                )
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Filled.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("إزالة التمييز", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * ورقة اختيار مدّة التمييز — تُستعمل عند التمييز لأوّل مرّة ومن شاشة
 * المختارات لتغيير المدّة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureDurationSheet(
    lessonTitle: String,
    currentUntilMs: Long?,
    onDismiss: () -> Unit,
    onPick: (FeatureDuration) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = adminGold)
                Spacer(Modifier.size(8.dp))
                Text(
                    "مدّة التمييز",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                lessonTitle.ifBlank { "الدرس" },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (currentUntilMs != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "الحالي: ${remainingLabel(currentUntilMs)}",
                    fontSize = 11.5.sp,
                    color = adminOrange,
                )
            }
            Spacer(Modifier.height(14.dp))
            FeatureDuration.entries.forEach { duration ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                        .clickable { onPick(duration) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (duration.hours == null) Icons.Filled.AllInclusive
                        else Icons.Filled.Timer,
                        contentDescription = null,
                        tint = if (duration.hours == null) MaterialTheme.colorScheme.primary else adminGold,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(duration.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
