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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Lesson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** خيارات مدّة التمييز المعروضة للمشرف. */
enum class FeatureDuration(val label: String, val hours: Long?) {
    H12("١٢ ساعة", 12),
    H24("٢٤ ساعة", 24),
    D2("يومان", 48),
    D3("٣ أيام", 72),
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
    var confirmClean by remember { mutableStateOf(false) }

    val expired = lessons.filter { it.featuredUntilMs != null && it.featuredUntilMs <= now }
    val active = lessons.filterNot { it.featuredUntilMs != null && it.featuredUntilMs <= now }

    fun unfeature(lesson: Lesson) {
        busyId = lesson.id
        scope.launch {
            runCatching { AdminRepository.setLessonFeatured(lesson.id, false) }
                .onSuccess { snack("أُزيل «${lesson.title}» من مختارات المنبر.") }
                .onFailure { snack("تعذّرت الإزالة: ${it.message ?: it}") }
            busyId = ""
        }
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
                    }.onSuccess { snack("مدّة التمييز الآن: ${duration.label}") }
                        .onFailure { snack("تعذّر التعديل: ${it.message ?: it}") }
                }
            },
        )
    }

    if (confirmClean && expired.isNotEmpty()) {
        // العدد المشمول مذكور صراحةً: التنظيف يمسّ دروساً عدّة دفعة واحدة.
        val batch = expired
        ConfirmDialog(
            title = "تنظيف المنتهية؟",
            body = "سيُزال التمييز عن ${batch.size} درساً انتهت مدّتها، فتسقط " +
                "من مختارات المنبر في التطبيق العام. الدروس نفسها لا تُحذف.",
            confirmLabel = "نظّف ${batch.size}",
            confirmColor = kOrange,
            onConfirm = {
                confirmClean = false
                scope.launch {
                    var failed = 0
                    batch.forEach { lesson ->
                        runCatching { AdminRepository.setLessonFeatured(lesson.id, false) }
                            .onFailure { failed++ }
                    }
                    // النجاح لا يُعلن إلّا عمّا نجح فعلاً: إعلان ثابت كان
                    // يُخفي فشل كلّ الكتابات فيظنّها المشرف نُظّفت.
                    snack(
                        if (failed == 0) {
                            "نُظّفت ${batch.size} من المنتهية."
                        } else {
                            "نُظّفت ${batch.size - failed}، وتعذّر $failed — أعد المحاولة."
                        },
                    )
                }
            },
            onDismiss = { confirmClean = false },
        )
    }

    AdminScaffold(
        title = "مختارات المنبر",
        onBack = onBack,
        actions = {
            if (expired.isNotEmpty()) {
                IconButton(onClick = { confirmClean = true }) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = "تنظيف المنتهية")
                }
            }
        },
    ) { padding ->
        if (lessons.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = kMuted,
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
                        color = kMuted,
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(listOf(kGold, kOrange)),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(
                                "${active.size} درساً في مختارات المنبر",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Text(
                                "تظهر أعلى التطبيق العام، وتسقط منه فور انتهاء " +
                                    "المدّة أو إزالة التمييز.",
                                color = Color.White.copy(alpha = 0.9f),
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
        isExpired -> kDanger
        until == null -> kTeal
        until - now < 6 * 3600_000L -> kOrange // أوشك على السقوط
        else -> kGold
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
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
                            tint = kMuted,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.size(3.dp))
                        Text("${lesson.views}", fontSize = 11.sp, color = kMuted)
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
                TextButton(onClick = onChangeDuration) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = kTeal,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("تغيير المدّة", fontSize = 12.sp, color = kTeal)
                }
                TextButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = kDanger,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("إزالة التمييز", fontSize = 12.sp, color = kDanger)
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
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = kGold)
                Spacer(Modifier.size(8.dp))
                Text(
                    "مدّة التمييز",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = kTealDark,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                lessonTitle.ifBlank { "الدرس" },
                fontSize = 12.sp,
                color = kMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (currentUntilMs != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "الحالي: ${remainingLabel(currentUntilMs)}",
                    fontSize = 11.5.sp,
                    color = kOrange,
                )
            }
            Spacer(Modifier.height(14.dp))
            FeatureDuration.entries.forEach { duration ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(kBoxBg, RoundedCornerShape(10.dp))
                        .clickable { onPick(duration) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (duration.hours == null) Icons.Filled.AllInclusive
                        else Icons.Filled.Timer,
                        contentDescription = null,
                        tint = if (duration.hours == null) kTeal else kGold,
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
