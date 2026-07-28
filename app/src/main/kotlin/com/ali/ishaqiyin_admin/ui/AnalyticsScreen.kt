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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.Subcategory

/**
 * «التحليلات والأثر» — يحوّل اللوحة من جرد أرقام إلى قصة وصول المحتوى:
 * إجمالي الاستماع، أكثر الدروس والأقسام، الجديد هذا الأسبوع، ولوحة شرف
 * المشرفين. كل الأرقام من عدّاد views المخزّن على كل درس.
 */
@Composable
fun AnalyticsScreen(onBack: () -> Unit) {
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var subs by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching {
            val fetchedLessons = AdminRepository.fetchLessons()
            val fetchedSubs = AdminRepository.fetchSubcategories()
            lessons = fetchedLessons
            subs = fetchedSubs
        }.onFailure { error = "تعذّر جلب البيانات. تحقّق من الاتصال." }
        loading = false
    }

    val totalViews = lessons.sumOf { it.views }
    val now = System.currentTimeMillis()
    val weekMs = 7L * 24 * 3600 * 1000
    val newThisWeek = lessons.count { now - it.createdAtMs < weekMs }
    val scheduled = lessons.count { (it.publishAtMs ?: 0) > now }
    val topLessons = lessons.filter { it.views > 0 }.sortedByDescending { it.views }
    val sectionViews = lessons
        .filter { it.subcategoryId.isNotEmpty() }
        .groupBy { it.subcategoryId }
        .mapValues { entry -> entry.value.sumOf { it.views } }
        .toList()
        .sortedByDescending { it.second }
    val byAdmin = lessons.filter { it.addedBy.isNotEmpty() }
        .groupBy { it.addedBy }
        .map { (email, items) -> Triple(email, items.size, items.sumOf { it.views }) }
        .sortedByDescending { it.third }

    fun subName(id: String) = subs.firstOrNull { it.id == id }?.name ?: "—"

    AdminScaffold(
        title = "التحليلات والأثر",
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (!loading) reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        if (loading) {
            FullScreenLoader()
            return@AdminScaffold
        }
        if (error != null) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(error!!, color = kDanger, textAlign = TextAlign.Center) }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox("إجمالي الاستماع", totalViews, Icons.Filled.Headphones)
                    StatBox("عدد الدروس", lessons.size, Icons.Filled.Audiotrack)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    StatBox("جديد هذا الأسبوع", newThisWeek, Icons.Filled.FiberNew)
                    StatBox("مجدولة للنشر", scheduled, Icons.Filled.Schedule)
                }
                Spacer(Modifier.height(20.dp))
                SectionTitle("الأكثر استماعاً")
            }
            if (topLessons.isEmpty()) {
                item { EmptyHint("لا توجد بيانات استماع بعد.") }
            } else {
                items(minOf(topLessons.size, 10)) { i ->
                    val l = topLessons[i]
                    RankTile(
                        l.title.ifEmpty { "بدون عنوان" },
                        "${l.views} استماع",
                        Icons.Filled.PlayCircleOutline,
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                SectionTitle("أنشط الأقسام")
            }
            if (sectionViews.isEmpty()) {
                item { EmptyHint("لا توجد بيانات بعد.") }
            } else {
                items(minOf(sectionViews.size, 8)) { i ->
                    val (id, views) = sectionViews[i]
                    RankTile(subName(id), "$views استماع", Icons.Filled.FolderOpen)
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                SectionTitle("لوحة شرف المشرفين")
            }
            if (byAdmin.isEmpty()) {
                item { EmptyHint("ستظهر هنا مساهمات المشرفين للدروس الجديدة.") }
            } else {
                items(byAdmin.size) { i ->
                    val (email, count, views) = byAdmin[i]
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
