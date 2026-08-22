package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** «درس واحد»/«درسان»/«ن دروس» — عربيّة سليمة بلا جدول صيغ. */
fun lessonsCountLabel(count: Int): String = when {
    count == 1 -> "درس واحد"
    count == 2 -> "درسان"
    count <= 10 -> "$count دروس"
    else -> "$count درساً"
}

/**
 * ❓ السؤال الواحد عند اختيار أكثر من ملفّ: درس واحد مدموج، أم درس لكلّ
 * ملفّ؟ ⛔ سؤال واحد لا معالج خطوات: النيّتان لا ثالث لهما، وكلاهما يبقى
 * على بُعد نقرة واحدة من «تغيير» في النموذج.
 *
 * الدمج يبقى **السلوك القائم** ويُعرض أوّلاً؛ ويُعطَّل وحده حين يتجاوز
 * العدد سقف الدمج ([mergeLimit]) مع ذكر السبب — بدل أن تُقصّ الملفّات
 * الزائدة صامتةً كما كان.
 */
@Composable
fun MultiFilesIntentDialog(
    count: Int,
    mergeLimit: Int,
    onMerge: () -> Unit,
    onSeparate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mergeAllowed = count <= mergeLimit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اخترتَ $count ملفّات") },
        text = {
            Column {
                Text(
                    "ماذا تريد بها؟",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                IntentOption(
                    icon = Icons.AutoMirrored.Filled.MergeType,
                    title = "ادمجها في درس واحد",
                    subtitle = if (mergeAllowed) {
                        "مقطع واحد متّصل بعنوان واحد — بالترتيب الذي تراه."
                    } else {
                        "غير متاح: الدمج حتى $mergeLimit ملفّات للدرس الواحد."
                    },
                    enabled = mergeAllowed,
                    onClick = onMerge,
                )
                Spacer(Modifier.height(8.dp))
                IntentOption(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = "ارفعها دروساً منفصلة",
                    subtitle = "${lessonsCountLabel(count)} — تُرفع واحداً تلو الآخر " +
                        "بترتيب القائمة، وهو ترتيب ظهورها في التطبيق.",
                    enabled = true,
                    onClick = onSeparate,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun IntentOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tone = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                tone.copy(alpha = if (enabled) 0.09f else 0.05f),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tone)
            Text(
                subtitle,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 📋 ورقة تأكيد رفع مجلّد: الدروس مرقَّمة **بترتيب رفعها**، ومعها سطر صريح
 * بأنّه ترتيب ظهورها في التطبيق.
 *
 * ⛔ الترتيب هنا ليس زينة: الرفع تسلسليّ بالدور (`UploadQueue.peek` يعيد
 * أصغر `seq`)، فما تراه في هذه القائمة هو ما يقع حرفيّاً. ولو رُفعت الملفّات
 * متوازيةً لوصل الأخفّ أوّلاً وانقلب ترتيب السلسلة كلّها — ولذلك لا تُعرض
 * القائمة إلّا بترتيبها النهائيّ.
 *
 * تعديل أيّ عنوان بنقرة عليه (حقل نصّ واحد) — بلا شاشة تحرير.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchUploadSheet(
    titles: List<String>,
    fileNames: List<String>,
    sectionLabel: String,
    notes: List<String>,
    onRename: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "رفع ${lessonsCountLabel(titles.size)}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "تُرفع واحداً تلو الآخر بهذا الترتيب تماماً — وهو ترتيب " +
                        "ظهورها في التطبيق، لا ترتيب انتهاء رفعها.",
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "تُطبَّق على الجميع: $sectionLabel",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            notes.forEach { note ->
                Spacer(Modifier.height(3.dp))
                Text(note, fontSize = 11.5.sp, lineHeight = 17.sp, color = adminOrange)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "انقر أيّ عنوان لتعديله.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // سقف لا ارتفاع ثابت — تنكمش القائمة في الوضع الأفقي بدل أن
                // يُقصّ زرّ الرفع أسفلها.
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
            ) {
                itemsIndexed(titles) { index, lessonTitle ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { editing = index }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                lessonTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                fileNames.getOrElse(index) { "" },
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "تعديل العنوان",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("رفع الكلّ (${titles.size})")
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    editing?.let { index ->
        EditTextDialog(
            title = "عنوان الدرس ${index + 1}",
            initial = titles.getOrElse(index) { "" },
            hint = "عنوان الدرس",
            onSave = { newTitle ->
                // العنوان الفارغ لا يُحفظ: يبقى المشتقّ من اسم الملفّ.
                if (newTitle.isNotBlank()) onRename(index, newTitle)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}
