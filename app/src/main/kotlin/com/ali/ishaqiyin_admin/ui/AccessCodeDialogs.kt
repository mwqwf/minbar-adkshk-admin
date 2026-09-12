package com.ali.ishaqiyin_admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 🔗 رموز الدخول بلا Google (أمر المالك 2026-09-12): رمز دعوة مشرف (24 ساعة)
 * ورمز ربط جهاز (10 دقائق) — كلاهما ثماني خانات يُبلَّغ به صاحبه بأي وسيلة
 * ويُدخله في شاشة الدخول («لديّ رمز دخول»). حوار واحد بسيط: الرمز كبيراً،
 * ونسخ، ومشاركة.
 */
@Composable
fun AccessCodeDialog(title: String, code: String, note: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        code,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copyCode(context, code)
                        snack("تم نسخ الرمز.")
                    }) { Text("نسخ") }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "رمز الدخول إلى إدارة منبر ادكصهك: $code\n$note")
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, "مشاركة الرمز")) }
                    }) { Text("مشاركة") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

/** حوار دعوة مشرف: بريد واسم، ثم يُصدر الرمز. */
@Composable
fun InviteAdminDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onInvite: (email: String, name: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val valid = email.trim().contains("@")
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("دعوة مشرف برمز") },
        text = {
            Column {
                Text(
                    "يصله رمز من ثماني خانات يُدخله في شاشة الدخول («لديّ رمز دخول») " +
                        "فيصير مشرفاً بلا حساب Google.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(120) },
                    label = { Text("البريد") },
                    singleLine = true,
                    colors = adminFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("الاسم (اختياري)") },
                    singleLine = true,
                    colors = adminFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onInvite(email.trim().lowercase(), name.trim()) }, enabled = valid && !busy) {
                if (busy) Spin(size = 16) else Text("أصدر الرمز")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } },
    )
}

internal fun copyCode(context: Context, code: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("code", code))
}
