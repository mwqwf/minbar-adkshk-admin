package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Lesson

/**
 * 📖 «النص المشروح» من مشاركة خارجية: المشرف شارك صورة صفحة كتاب أو نصاً
 * من تطبيق آخر — يبحث هنا عن الدرس المقصود ثم يفتح المحرر معبّأً بالحمولة.
 */
@Composable
fun TranscriptIntakeScreen(onBack: () -> Unit) {
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var categories by remember {
        mutableStateOf<List<com.ali.ishaqiyin_admin.data.Category>>(emptyList())
    }
    var subcategories by remember {
        mutableStateOf<List<com.ali.ishaqiyin_admin.data.Subcategory>>(emptyList())
    }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Lesson?>(null) }
    // الحمولة تُلتقط مرة واحدة وتبقى حتى إغلاق الشاشة.
    val payload = remember { TranscriptIntake.consume() ?: TranscriptIntake.Payload() }

    LaunchedEffect(Unit) {
        runCatching {
            lessons = AdminRepository.fetchLessons()
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
        loading = false
    }
    fun sectionPath(lesson: Lesson): String = listOfNotNull(
        categories.firstOrNull { it.id == lesson.categoryId }?.name?.takeIf(String::isNotBlank),
        subcategories.firstOrNull { it.id == lesson.subcategoryId }?.name
            ?.takeIf(String::isNotBlank),
    ).joinToString(" ← ")

    target?.let { lesson ->
        TranscriptEditorDialog(
            lessonId = lesson.id,
            lessonTitle = lesson.title,
            initialText = payload.text,
            initialImages = payload.images,
            onDismiss = {
                target = null
                onBack()
            },
        )
    }

    AdminScaffold(title = "النص المشروح — اختر الدرس", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                if (payload.images.isNotEmpty()) {
                    "وصلت ${payload.images.size} صورة من المشاركة — اختر الدرس " +
                        "الذي تشرح صفحاته هذه الصور."
                } else if (payload.text.isNotBlank()) {
                    "وصل نص من المشاركة — اختر الدرس الذي يشرحه."
                } else {
                    "اختر الدرس الذي تريد إضافة/تعديل نصه المشروح."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            AdminTextField(
                value = query,
                onValueChange = { query = it },
                label = "ابحث بالعنوان أو رقم الدرس أو اسم القسم",
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "مثال: «3 الفقه» يجد الدرس رقم 3 في قسم الفقه.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            if (loading) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("جارٍ تحميل الدروس…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@Column
            }
            // بحث عامّ: يقبل رقماً واحداً، وكل كلمة تُطابق العنوان أو القسم
            // الرئيسي أو الفرعي — فالعناوين الرقمية تتمايز بقسمها.
            val tokens = query.trim().lowercase().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            val matches = if (tokens.isEmpty()) {
                emptyList()
            } else {
                lessons.filter { lesson ->
                    val haystack = "${lesson.title} ${sectionPath(lesson)}".lowercase()
                    tokens.all { haystack.contains(it) }
                }.take(25)
            }
            if (tokens.isEmpty()) {
                Text(
                    "اكتب رقم الدرس أو أي كلمة من عنوانه أو قسمه.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (matches.isEmpty()) {
                Text(
                    "لا نتائج — جرّب رقم الدرس مع اسم قسمه، مثل: «3 الفقه».",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(matches.size) { i ->
                        val lesson = matches[i]
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { target = lesson },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text(
                                        lesson.title.ifEmpty { "بدون عنوان" },
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val path = sectionPath(lesson)
                                    if (path.isNotEmpty()) {
                                        Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
