package com.ali.ishaqiyin_admin.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AnalyticsRepository
import com.ali.ishaqiyin_admin.data.AnalyticsSnapshot
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.HonorRange
import com.ali.ishaqiyin_admin.ui.chat.MemberAvatar

/**
 * «التحليلات والأثر» — بكاش محلّي: تفتح فوراً من آخر لقطة محفوظة (حتى بلا
 * إنترنت)، ولا يُعاد الجلب الكامل إلا إذا تغيّرت مقاييس المحتوى الخفيفة
 * (الأعداد + أحدث تعديل على الدروس) أو تجاوزت اللقطة ست ساعات أو طُلب
 * التحديث يدوياً. تشمل بطاقتَي تغطية «النص المشروح»: كم درساً له نص وكم
 * درساً ما زال ناقصاً، ولوحة شرف بأسماء المشرفين وصورهم على ثلاثة نطاقات.
 */
@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    onOpenLessons: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var honorRange by remember { mutableStateOf(HonorRange.ALL) }

    // أسماء المشرفين وصورهم من مجموعة الدردشة — البريد الخام لا يُعرَف.
    val members by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())

    LaunchedEffect(reload) {
        error = null
        offline = false
        val cached = stats ?: AnalyticsRepository.loadCache(context)
        if (cached != null && stats == null) {
            // الكاش يظهر فوراً — لا شاشة انتظار لمجرّد فتح الإحصائيات.
            stats = cached
            loading = false
        } else if (cached == null) {
            loading = true
        }
        runCatching {
            if (reload > 0) {
                // تحديث يدوي: إعادة حساب صريحة بلا مسبار ولا حارس.
                stats = AnalyticsRepository.compute(context, null)
            } else {
                val check = AnalyticsRepository.checkFreshness(cached)
                if (check.stale) {
                    stats = AnalyticsRepository.compute(context, check.counts)
                }
            }
        }.onFailure {
            // بلا شبكة: الكاش يكفي بصمت؛ الخطأ يظهر فقط إن لم يوجد كاش أصلاً.
            if (stats == null) {
                error = "تعذّر جلب البيانات. تحقّق من الاتصال."
            } else {
                offline = true
            }
        }
        loading = false
    }

    AdminScaffold(
        title = "التحليلات والأثر",
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (!loading) reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        val s = stats
        if (loading && s == null) {
            FullScreenLoader()
            return@AdminScaffold
        }
        if (error != null && s == null) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            return@AdminScaffold
        }
        if (s == null) return@AdminScaffold
        val honor = s.adminsIn(honorRange)
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                if (offline) {
                    Text(
                        "بلا اتصال — تُعرض آخر إحصائيات محفوظة.",
                        fontSize = 12.sp,
                        color = adminOrange,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                // أربع بطاقات في صفَّين متوازنين بعد إزالة «إجمالي الاستماع»
                // و«مجدولة للنشر» (الأولى بلا فائدة إداريّة، والثانية سقطت مع
                // إزالة النشر المجدول من المنظومة كلّها).
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox("عدد الدروس", s.lessonsCount, Icons.Filled.Audiotrack)
                    StatBox("جديد هذا الأسبوع", s.newThisWeek, Icons.Filled.FiberNew)
                }
                Spacer(Modifier.height(10.dp))
                // تغطية «النص المشروح»: كم درساً وُثّق نصّه وكم بقي ناقصاً.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox(
                        "لها نص مشروح",
                        s.transcriptsCount,
                        Icons.AutoMirrored.Filled.MenuBook,
                    )
                    // البطاقة تفتح إدارة الدروس لمعرفة **أيّ** الدروس بلا نص
                    // بدل رقم لا سبيل لتتبّعه.
                    Box(
                        Modifier.then(
                            if (onOpenLessons != null) {
                                Modifier.clickable { onOpenLessons?.invoke() }
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        StatBox(
                            "بلا نص بعد",
                            s.missingTranscripts,
                            Icons.Filled.HourglassBottom,
                        )
                    }
                }
                if (onOpenLessons != null && s.missingTranscripts > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "انقر «بلا نص بعد» لفتح إدارة الدروس وإضافة النصوص الناقصة.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                SectionTitle("الأكثر استماعاً")
            }
            if (s.topLessons.isEmpty()) {
                item { EmptyHint("لا توجد بيانات استماع بعد.") }
            } else {
                items(s.topLessons.size) { i ->
                    val (title, views) = s.topLessons[i]
                    RankTile(title, "$views استماع", Icons.Filled.PlayCircleOutline)
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                SectionTitle("أنشط الأقسام")
            }
            if (s.topSections.isEmpty()) {
                item { EmptyHint("لا توجد بيانات بعد.") }
            } else {
                items(s.topSections.size) { i ->
                    val (name, views) = s.topSections[i]
                    RankTile(name, "$views استماع", Icons.Filled.FolderOpen)
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                SectionTitle("لوحة شرف المشرفين")
                HonorRangeBar(current = honorRange, onSelect = { honorRange = it })
                Spacer(Modifier.height(6.dp))
            }
            if (honor.isEmpty()) {
                item {
                    EmptyHint(
                        when (honorRange) {
                            HonorRange.WEEK -> "لا مساهمات من المشرفين هذا الأسبوع."
                            HonorRange.MONTH -> "لا مساهمات من المشرفين هذا الشهر."
                            HonorRange.ALL ->
                                "ستظهر هنا مساهمات المشرفين للدروس الجديدة."
                        },
                    )
                }
            } else {
                items(honor.size) { i ->
                    val (email, count, views) = honor[i]
                    AdminRankTile(
                        rank = i + 1,
                        email = email,
                        member = memberFor(members, email),
                        trailing = "${lessonsCountLabel(count)} · $views استماع",
                    )
                }
            }
        }
    }
}

/** مطابقة بريد المشرف بعضو المجموعة (البريد يُقارَن مطبَّعاً). */
private fun memberFor(members: List<ChatMember>, email: String): ChatMember? {
    val key = email.trim().lowercase()
    if (key.isEmpty()) return null
    return members.firstOrNull { it.email.trim().lowercase() == key }
}

@Composable
private fun HonorRangeBar(current: HonorRange, onSelect: (HonorRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HonorRange.entries.forEach { range ->
            val label = when (range) {
                HonorRange.WEEK -> "هذا الأسبوع"
                HonorRange.MONTH -> "هذا الشهر"
                HonorRange.ALL -> "الكل"
            }
            FilterChip(
                selected = current == range,
                onClick = { onSelect(range) },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

/** بطاقة مشرف في لوحة الشرف: رتبته وصورته واسمه (البريد سطراً ثانياً). */
@Composable
private fun AdminRankTile(
    rank: Int,
    email: String,
    member: ChatMember?,
    trailing: String,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank",
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (member != null) {
                MemberAvatar(
                    uid = member.uid,
                    name = member.displayName,
                    photo = member.displayPhoto,
                    radius = 16,
                )
            } else {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    member?.displayName?.takeIf { it.isNotBlank() }
                        ?: email.ifEmpty { "مشرف" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                if (member != null && email.isNotEmpty()) {
                    Text(
                        email,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(trailing, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RankTile(title: String, trailing: String, icon: ImageVector) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(trailing, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
    }
}
