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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * شاشة «إرسال إشعار» — نظير لوحة الإشعارات في نبراس.
 * تستدعي دالة سحابية ترسل دفعاً عبر FCM لكل مستخدمي تطبيق «منبر ادكصهك».
 */
@Composable
fun NotifyScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val canSend = body.trim().isNotEmpty() && body.trim().length <= 500 && !sending

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
                    .background(kBoxBg, RoundedCornerShape(12.dp))
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
                value = body,
                onValueChange = { if (it.length <= 500) body = it },
                label = "نص الإشعار",
                singleLine = false,
                minLines = 4,
                maxLines = 6,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
            )
            Spacer(Modifier.height(18.dp))
            if (message.isNotEmpty()) {
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    color = if (isError) kDanger else kGreen,
                    fontWeight = FontWeight.SemiBold,
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
                            val response = FirebaseFunctions.getInstance()
                                .getHttpsCallable("sendNotification")
                                .call(
                                    mapOf(
                                        "body" to body.trim(),
                                    ),
                                ).await()
                            val data = response.getData()
                            if (data is Map<*, *> && data["success"] == false) {
                                error((data["error"] ?: "رفض الخادم الإرسال").toString())
                            }
                            val sent = (data as? Map<*, *>)?.get("sent") as? Number
                            val failed = (data as? Map<*, *>)?.get("failed") as? Number
                            sending = false
                            body = ""
                            message = if (sent == null) {
                                "أكّد الخادم استلام الإشعار وإرساله."
                            } else {
                                "أكّد الخادم الإرسال إلى ${sent.toInt()} جهاز" +
                                    if ((failed?.toInt() ?: 0) > 0) {
                                        "، وتعذّر على ${failed?.toInt()}."
                                    } else {
                                        "."
                                    }
                            }
                        } catch (e: Exception) {
                            sending = false
                            isError = true
                            message = "تعذّر إرسال الإشعار: ${e.message ?: e}"
                        }
                    }
                },
                enabled = canSend,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = kTeal),
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
