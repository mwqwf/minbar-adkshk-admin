package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * عناصر «التصفّح بالأقسام» في شاشة التعديل والحذف.
 *
 * ⚠️ لماذا وُجدت: كان الوصول إلى أيّ درس قديم يمرّ بكتابة بحث، والمشرف قد
 * لا يجيد الكتابة أصلاً — فكان التعديل عمليّاً مغلقاً أمامه. التصفّح
 * بالنقر (رئيسيّ ← فرعيّ ← الدروس) هو الطريق الأوّل، والبحث يبقى لمن
 * يعرفه.
 */

/** بطاقة قسم كبيرة: النقر عليها يفتحها، ومعها تعديل الاسم وحذف القسم. */
@Composable
fun BrowseSectionCard(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // مساحة الفتح واسعة جداً عمداً: هي الفعل المقصود في ٩ من ١٠ مرّات.
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "بدون اسم" },
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
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "تعديل الاسم",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "حذف القسم",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** شريط «أين أنا الآن» مع زرّ رجوع كبير — لا يضيع المشرف بين المستويات. */
@Composable
fun BrowseBreadcrumb(path: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            path,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** فشل تحميل + «أعد المحاولة»: على الإنترنت الضعيف هذه الحالة هي الأكثر. */
@Composable
fun RetryBox(message: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            FilledTonalButton(
                onClick = onRetry,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = contentColorOn(MaterialTheme.colorScheme.primary),
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("أعد المحاولة") }
        }
    }
}
