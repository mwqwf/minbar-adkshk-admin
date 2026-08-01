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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Lesson?>(null) }
    // الحمولة تُلتقط مرة واحدة وتبقى حتى إغلاق الشاشة.
    val payload = remember { TranscriptIntake.consume() ?: TranscriptIntake.Payload() }

    LaunchedEffect(Unit) {
        runCatching { lessons = AdminRepository.fetchLessons() }
        loading = false
    }

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
                color = kMuted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            AdminTextField(
                value = query,
                onValueChange = { query = it },
                label = "ابحث عن الدرس بعنوانه",
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("جارٍ تحميل الدروس…", color = kMuted) }
                return@Column
            }
            val q = query.trim().lowercase()
            val matches = if (q.length < 2) {
                emptyList()
            } else {
                lessons.filter { it.title.lowercase().contains(q) }.take(20)
            }
            if (q.length < 2) {
                Text(
                    "اكتب حرفين على الأقل من عنوان الدرس.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = kMuted,
                )
            } else if (matches.isEmpty()) {
                Text(
                    "لا نتائج — جرّب كلمة أخرى من العنوان.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = kMuted,
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(matches.size) { i ->
                        val lesson = matches[i]
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                    tint = kTeal,
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    lesson.title.ifEmpty { "بدون عنوان" },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
