package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.Subcategory

/**
 * نقل صوتيّة من قسم إلى قسم بخطوتين واضحتين.
 *
 * ⚠️ لماذا بطاقات كبيرة لا قوائم منسدلة: المشرفون لا يتقنون التقنية
 * وإنترنتهم ضعيف، والقائمة المنسدلة الصغيرة تُخطئ باللمس فينتقل الدرس إلى
 * قسم لم يُقصد. البطاقة عريضة (٥٦dp فأكثر) واسمها كامل، والتأكيد يعرض
 * «من ← إلى» صراحةً قبل أيّ كتابة في القاعدة.
 */
@Composable
fun MoveLessonDialog(
    lesson: Lesson,
    currentCategoryName: String,
    currentSubcategoryName: String,
    categories: List<Category>,
    subcategories: List<Subcategory>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (target: Subcategory, targetCategoryName: String) -> Unit,
) {
    // الوجهات الممكنة: كلّ قسم فرعيّ عدا القسم الحالي — النقل إلى المكان
    // نفسه لا معنى له فلا يُعرض أصلاً.
    val destinations = remember(subcategories, lesson.subcategoryId) {
        subcategories.filter { it.id != lesson.subcategoryId }
    }
    // ولا يُعرض قسم رئيسيّ لا وجهة تحته (فتحه يوصل إلى شاشة فارغة).
    val pickableCategories = remember(categories, destinations) {
        val withDestinations = destinations.map { it.categoryId }.toSet()
        categories.filter { it.id in withDestinations }
    }

    var chosenCategory by remember { mutableStateOf<Category?>(null) }
    var chosenSub by remember { mutableStateOf<Subcategory?>(null) }

    val from = buildString {
        append(currentCategoryName.ifBlank { "بدون قسم" })
        if (currentSubcategoryName.isNotBlank()) append(" ← $currentSubcategoryName")
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // رجوع خطوة واحدة بدل إغلاق الحوار كلّه وإعادة البدء.
                    if (chosenCategory != null) {
                        androidx.compose.material3.IconButton(
                            onClick = { chosenCategory = null; chosenSub = null },
                            enabled = !busy,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        "نقل إلى قسم آخر",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    lesson.title.ifBlank { "بدون عنوان" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "الملفّ الصوتيّ لا يُرفع من جديد، ولا يضيع النصّ المشروح " +
                        "ولا عدد الاستماع.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                when {
                    // لا وجهة أصلاً (قسم فرعيّ واحد في القاعدة كلّها).
                    destinations.isEmpty() -> {
                        Text(
                            "لا يوجد قسم آخر يُنقل إليه الدرس. أضِف قسماً فرعيّاً " +
                                "جديداً أوّلاً من شاشة «الأقسام».",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // الخطوة الأولى: القسم الرئيسيّ.
                    chosenCategory == null -> {
                        StepLabel("الخطوة ١ من ٢ — اختر القسم الرئيسيّ")
                        LazyColumn(
                            Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pickableCategories.size) { i ->
                                val c = pickableCategories[i]
                                val count = destinations.count { it.categoryId == c.id }
                                PickCard(
                                    title = c.name.ifBlank { "بدون اسم" },
                                    subtitle = arabicCount(
                                        count,
                                        "قسم فرعيّ واحد",
                                        "قسمان فرعيّان",
                                        "أقسام فرعيّة",
                                        "قسماً فرعيّاً",
                                    ),
                                    enabled = !busy,
                                    onClick = { chosenCategory = c },
                                )
                            }
                        }
                    }

                    // الخطوة الثانية: القسم الفرعيّ.
                    chosenSub == null -> {
                        StepLabel("الخطوة ٢ من ٢ — اختر القسم الفرعيّ")
                        val subs = destinations.filter { it.categoryId == chosenCategory!!.id }
                        LazyColumn(
                            Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(subs.size) { i ->
                                val s = subs[i]
                                PickCard(
                                    title = s.name.ifBlank { "بدون اسم" },
                                    subtitle = chosenCategory!!.name,
                                    enabled = !busy,
                                    onClick = { chosenSub = s },
                                )
                            }
                        }
                    }

                    // المراجعة الأخيرة: «من ← إلى» صريحة قبل أيّ كتابة.
                    else -> {
                        StepLabel("راجِع قبل التأكيد")
                        MoveSummaryRow("من:", from, MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        MoveSummaryRow(
                            "إلى:",
                            "${chosenCategory!!.name} ← ${chosenSub!!.name}",
                            MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "سيوضع الدرس في آخر قائمة القسم الجديد.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") }
                    if (chosenSub != null) {
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(
                            onClick = {
                                onConfirm(chosenSub!!, chosenCategory!!.name)
                            },
                            enabled = !busy,
                            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = contentColorOn(MaterialTheme.colorScheme.primary),
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (busy) Spin(size = 18) else Text("انقل الآن")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** بطاقة اختيار كبيرة: هدف اللمس ٦٤dp فما فوق كي لا يُخطئ الإصبع. */
@Composable
private fun PickCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MoveSummaryRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(44.dp),
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}
