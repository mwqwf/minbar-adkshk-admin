package com.ali.ishaqiyin_admin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 📸 «أرفِق آخر لقطة شاشة» — بطاقة صغيرة فوق منطقة الإرفاق، بنقرة واحدة
 * تفتح **منتقي الصور** (PickVisualMedia) ويكون أحدث ما التقطتَه أوّل ما
 * تراه، فتختاره وتُرفق.
 *
 * ⛔ درسٌ لا يُنسى (2026-08-25): كانت البطاقة تستعلم MediaStore وتطلب
 * READ_MEDIA_IMAGES، و**Play يرفض** كلّ تطبيق يطلب أذونات الصور ما لم يقدّم
 * إقرار «الوظيفة الأساسيّة للصور والفيديوهات» — وقد رفض إصدار اللوحة فعلاً.
 * منتقي الصور يعطي النتيجة نفسها بلا **أيّ إذن** وعلى كلّ إصدار أندرويد
 * (وقبل أندرويد 11 يرجع androidx تلقائياً إلى محدّد الملفات)، وبلا أيّ
 * إقرار في Play. فلا تُعِد الاستعلام ولا الإذن.
 *
 * وهو أفضل للخصوصيّة أيضاً: التطبيق لا يرى إلّا الصورة التي اختارها
 * المستخدم بنفسه، لا استوديوه كلّه.
 */

/**
 * إخفاء البطاقة لبقيّة جلسة التطبيق عند إغلاقها — في الذاكرة لا في
 * التخزين: نقترح مجدّداً في جلسة قادمة، ولا نلاحق المستخدم في نفس الجلسة
 * على كلّ شاشة.
 */
private object SessionDismiss {
    var hidden = false
}

/**
 * البطاقة. تُوضع فوق حقل الإدخال/منطقة الإرفاق.
 * [enabled] يطفئها مؤقّتاً (رفع جارٍ، مجموعة مقفلة، بلوغ سقف الصور…).
 * التوقيع لم يتغيّر عن النسخة السابقة كي تبقى مواضع ندائها الأربعة كما هي.
 */
@Composable
fun RecentScreenshotChip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    // مرآة حالة الإغلاق الجلسويّ: المتغيّر الساكن لا يُعيد التركيب بنفسه.
    var hidden by remember { mutableStateOf(SessionDismiss.hidden) }

    // ImageOnly: الصور وحدها — يمنع اختيار الفيديو من أصله (قرار: لا فيديو).
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPick(uri) }

    if (!enabled || hidden) return

    SuggestionCard(
        modifier = modifier,
        onAttach = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onDismiss = {
            SessionDismiss.hidden = true
            hidden = true
        },
    )
}

/**
 * شكل البطاقة — نفس لغة ClipboardImageSuggestion البصريّة (سطح مستدير
 * بحدود خفيفة) والسمتان تعملان عبر ألوان MaterialTheme، وكلّ أهداف اللمس
 * ≥ 48dp (جمهور غير تقنيّ).
 */
@Composable
private fun SuggestionCard(
    modifier: Modifier,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                // البطاقة كلّها هدف نقر واحد كبير — أسهل من إصابة زرّ صغير.
                .clickable(onClick = onAttach)
                .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // لا مصغّرة بعد اليوم: لا نقرأ الاستوديو أصلاً، والمنتقي نفسه
            // يعرض أحدث اللقطات أوّلاً.
            Box(
                Modifier.size(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Screenshot,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("أرفِق آخر لقطة شاشة", fontSize = 12.5.sp)
                Text(
                    "بنقرة واحدة",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onAttach,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("إرفاق") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إخفاء الاقتراح")
            }
        }
    }
}
