package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.arabicReason

/**
 * 🕘 «آخر ما جرى» — من عدّل ماذا ومتى.
 *
 * لماذا: اللوحة يعمل عليها عدّة مشرفين، ولم تكن هناك شاشة واحدة تُظهر من
 * غيّر ماذا؛ فإذا اختفى عنوان أو تبدّل قسم لم يعرف أحد لمن يسأل. والبيانات
 * كانت موجودة أصلاً في القاعدة (`updatedByEmail` و`updatedAt` تُكتبان مع كل
 * تعديل) — هذه الشاشة تقرؤها فقط ولا تكتب شيئاً جديداً.
 *
 * وهي **خفيفة عمداً**: جلبة واحدة عند الفتح (بلا مستمع حيّ يستهلك الشبكة)،
 * بحدٍّ أقصى خمسين تغييراً، وزرّ تحديث يدويّ متى شاء المشرف.
 */
@Composable
fun RecentChangesScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<AdminRepository.RecentChange>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    // تغيير هذا العدّاد يُعيد الجلب — زرّ التحديث لا أكثر.
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = ""
        runCatching { AdminRepository.fetchRecentChanges() }
            .onSuccess { items = it }
            .onFailure { error = "تعذّر جلب آخر ما جرى: ${it.arabicReason()}" }
        loading = false
    }

    AdminScaffold(
        title = "آخر ما جرى",
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (!loading) reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && items.isEmpty() -> FullScreenLoader()

                error.isNotEmpty() && items.isEmpty() -> EmptyHint(error)

                items.isEmpty() -> EmptyHint(
                    "لا تعديلات مسجّلة بعد — سيظهر هنا كل تغيير يقوم به المشرفون.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(items, key = { it.kind + it.id }) { change ->
                        ChangeRow(change)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: AdminRepository.RecentChange) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            // 48dp: صفّ كامل بارتفاع هدف اللمس ولو لم يكن قابلاً للنقر.
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                iconFor(change.kind),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                changeSentence(change),
                fontSize = 13.5.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                humanDay(change.atMs),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "category" -> Icons.Filled.Folder
    "subcategory" -> Icons.Filled.AccountTree
    "lesson" -> Icons.Filled.Audiotrack
    else -> Icons.Filled.History
}

/** «عدّل فلان درس «كذا»» — جملة بشريّة لا حقول قاعدة بيانات. */
internal fun changeSentence(change: AdminRepository.RecentChange): String {
    val who = personName(change.byEmail)
    val what = when (change.kind) {
        "category" -> "قسماً رئيسيّاً"
        "subcategory" -> "قسماً فرعيّاً"
        else -> "درس"
    }
    val name = change.name.ifBlank { "بدون عنوان" }
    return "عدّل $who $what «$name»"
}

/**
 * اسم المشرف من بريده: ما قبل علامة البريد فقط. عرض البريد كاملاً يملأ
 * السطر بما لا يُقرأ، والاسم الحقيقيّ ليس مخزَّناً مع التعديل.
 */
private fun personName(email: String): String {
    if (email.isBlank()) return "أحد المشرفين"
    return email.substringBefore('@').ifBlank { "أحد المشرفين" }
}

/**
 * تاريخ بشريّ: «اليوم» و«أمس» و«قبل 3 أيام» — لا طابع زمنيّ خام.
 * الأرقام لاتينيّة كبقيّة اللوحة.
 */
internal fun humanDay(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val diff = now - ms
    if (diff < 0) return "الآن"
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل ${arabicCount(minutes.toInt(), "دقيقة", "دقيقتين", "دقائق", "دقيقة")}"
        hours < 24 -> "قبل ${arabicCount(hours.toInt(), "ساعة", "ساعتين", "ساعات", "ساعة")}"
        days < 1 -> "اليوم"
        days < 2 -> "أمس"
        days < 30 -> "قبل ${arabicCount(days.toInt(), "يوم", "يومين", "أيام", "يوماً")}"
        else -> "قبل ${arabicCount((days / 30).toInt(), "شهر", "شهرين", "أشهر", "شهراً")}"
    }
}
