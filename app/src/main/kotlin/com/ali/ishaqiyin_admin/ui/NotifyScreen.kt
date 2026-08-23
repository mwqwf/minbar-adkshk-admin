package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminNotificationService
import com.ali.ishaqiyin_admin.data.NotificationSendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * شاشة «إرسال إشعار» — نظير لوحة الإشعارات في نبراس.
 * تستدعي دالة سحابية ترسل دفعاً عبر FCM لكل مستخدمي تطبيق «منبر ادكصهك».
 *
 * الإرسال كلّه في [AdminNotificationService.sendBroadcast]: هي وحدها التي
 * تكلّم الخادم وتترجم كل فشل إلى عربية. الشاشة لا تعرض أبداً نصّ استثناء
 * خاماً (كان يظهر «تعذّر إرسال الإشعار: INTERNAL» — عربية مختلطة بإنجليزية).
 */
@Composable
fun NotifyScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    // الخادم يقبل العنوان أو النص (أحدهما يكفي)، ويرفض الطلب إن خلا الاثنان.
    val hasContent = title.trim().isNotEmpty() || body.trim().isNotEmpty()
    val canSend = hasContent && !sending

    AdminScaffold(title = "إرسال إشعار", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "يصل هذا الإشعار فوراً (دفع) إلى كل مستخدمي تطبيق «منبر ادكصهك». " +
                        "كما تُرسَل إشعارات تلقائية عند إضافة قسم أو درس أو كتاب.",
                    lineHeight = 24.sp,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            AdminTextField(
                value = title,
                onValueChange = {
                    if (it.length <= AdminNotificationService.TITLE_MAX) title = it
                },
                label = "العنوان (اختياري)",
                enabled = !sending,
                leadingIcon = { Icon(Icons.Filled.Title, contentDescription = null) },
            )
            Spacer(Modifier.height(14.dp))
            AdminTextField(
                value = body,
                onValueChange = {
                    if (it.length <= AdminNotificationService.BODY_MAX) body = it
                },
                label = "نص الإشعار",
                enabled = !sending,
                singleLine = false,
                minLines = 4,
                maxLines = 6,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${body.length}/${AdminNotificationService.BODY_MAX}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(12.dp))
            if (message.isNotEmpty()) {
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    color = if (isError) MaterialTheme.colorScheme.error else adminGreen,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
            Button(
                onClick = {
                    if (!canSend) return@Button
                    sending = true
                    message = ""
                    isError = false
                    scope.launch {
                        try {
                            val outcome = AdminNotificationService.sendBroadcast(title, body)
                            sending = false
                            title = ""
                            body = ""
                            isError = false
                            val sent = outcome.sent
                            val failed = outcome.failed ?: 0
                            message = when {
                                sent == null ->
                                    "تم إرسال الإشعار إلى كل مستخدمي التطبيق."
                                failed > 0 ->
                                    "تم الإرسال إلى ${arabicCount(sent, "جهاز واحد", "جهازين", "أجهزة", "جهازاً")}، وتعذّر على $failed."
                                else ->
                                    "تم الإرسال إلى ${arabicCount(sent, "جهاز واحد", "جهازين", "أجهزة", "جهازاً")}."
                            }
                        } catch (e: CancellationException) {
                            sending = false
                            throw e
                        } catch (e: Exception) {
                            sending = false
                            isError = true
                            // رسالة عربية مضمونة: لا يُعرض e.message الخام أبداً.
                            message = (e as? NotificationSendException)?.message
                                ?: "تعذّر إرسال الإشعار. تحقّق من الاتصال ثم أعد المحاولة."
                        }
                    }
                },
                enabled = canSend,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (sending) {
                    Spin(size = 20)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(if (sending) "جارٍ الإرسال..." else "إرسال الإشعار")
            }
        }
    }
}
