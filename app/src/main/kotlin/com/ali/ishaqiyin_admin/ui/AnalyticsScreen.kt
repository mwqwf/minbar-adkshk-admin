package com.ali.ishaqiyin_admin.ui

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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AnalyticsRepository
import com.ali.ishaqiyin_admin.data.AnalyticsSnapshot

/**
 * «التحليلات والأثر» — بكاش محلّي: تفتح فوراً من آخر لقطة محفوظة (حتى بلا
 * إنترنت)، ولا يُعاد الجلب الكامل إلا إذا تغيّر عدد الدروس/الكتب/النصوص
 * المشروحة (استعلامات count خفيفة) أو طُلب التحديث يدوياً. تشمل بطاقتَي
 * تغطية «النص المشروح»: كم درساً له نص وكم درساً ما زال ناقصاً.
 */
@Composable
fun AnalyticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

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
            val counts = AnalyticsRepository.fetchCounts()
            val fresh = cached == null ||
                reload > 0 ||
                !AnalyticsRepository.matches(cached, counts)
            if (fresh) {
                stats = AnalyticsRepository.compute(context, counts)
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
            ) { Text(error!!, color = kDanger, textAlign = TextAlign.Center) }
            return@AdminScaffold
        }
        if (s == null) return@AdminScaffold
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                if (offline) {
                    Text(
                        "بلا اتصال — تُعرض آخر إحصائيات محفوظة.",
                        fontSize = 12.sp,
                        color = kOrange,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox("إجمالي الاستماع", s.totalViews, Icons.Filled.Headphones)
                    StatBox("عدد الدروس", s.lessonsCount, Icons.Filled.Audiotrack)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox("جديد هذا الأسبوع", s.newThisWeek, Icons.Filled.FiberNew)
                    StatBox("مجدولة للنشر", s.scheduled, Icons.Filled.Schedule)
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
                    StatBox(
                        "بلا نص بعد",
                        s.missingTranscripts,
                        Icons.Filled.HourglassBottom,
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
            }
            if (s.admins.isEmpty()) {
                item { EmptyHint("ستظهر هنا مساهمات المشرفين للدروس الجديدة.") }
            } else {
                items(s.admins.size) { i ->
                    val (email, count, views) = s.admins[i]
                    RankTile(email, "$count درساً · $views استماع", Icons.Filled.EmojiEvents)
                }
            }
        }
    }
}

@Composable
private fun RankTile(title: String, trailing: String, icon: ImageVector) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = kTeal)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(trailing, fontWeight = FontWeight.Bold, color = kTeal, fontSize = 13.sp)
        }
    }
}
