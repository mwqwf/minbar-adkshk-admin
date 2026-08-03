package com.ali.ishaqiyin_admin.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ali.ishaqiyin_admin.data.isArabicText
import com.ali.ishaqiyin_admin.util.AudioRecorderController
import kotlinx.coroutines.delay
import java.io.File

/**
 * سبب فشل التسجيل بالعربية. المسجّل محليّ بالكامل، ولذلك **لا** يمرّ عطله
 * على `Throwable.arabicReason()`: تلك الطبقة تترجم `IOException` إلى «لا يوجد
 * اتصال بالإنترنت» — وهي رسالة مضلّلة هنا لأنّ `MediaRecorder.prepare` يرمي
 * `IOException` عند انشغال الميكروفون أو امتلاء التخزين لا عند انقطاع الشبكة.
 * المهمّ أنّ نصّ الاستثناء الإنجليزي الخام لا يصل المشرف بأي حال.
 */
private fun recorderReason(error: Throwable): String {
    val text = error.message.orEmpty().trim()
    return if (text.isNotEmpty() && isArabicText(text)) {
        text
    } else {
        "تحقّق من إذن الميكروفون وخلوّ مساحة التخزين ثم أعد المحاولة."
    }
}

/**
 * ورقة تسجيل صوتي مباشر — تعيد الملفّ المسجَّل واسمه المقترح (أو لا شيء إن
 * أُلغي)، بنفس تدفّق record_sheet.dart: بدء / إيقاف مؤقّت / إنهاء / استخدام.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(onDismiss: () -> Unit, onRecorded: (File, String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recorder = remember { AudioRecorderController() }

    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) error = "لم يُسمح باستخدام الميكروفون." }

    DisposableEffect(Unit) {
        onDispose { recorder.release() }
    }

    LaunchedEffect(recording, paused) {
        while (recording && !paused) {
            delay(1000)
            seconds += 1
        }
    }

    fun start() {
        error = null
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            file = recorder.start(context, "rec")
            recording = true
            paused = false
            seconds = 0
        } catch (e: Exception) {
            error = "تعذّر بدء التسجيل: ${recorderReason(e)}"
        }
    }

    fun stop() {
        try {
            // إرجاع null يعني أنّ الملفّ حُذف لفساد التسجيل — العودة إلى
            // المرجع القديم كانت تسلّم ملفّاً غير موجود للرفع.
            val result = recorder.stop()
            recording = false
            paused = false
            if (result == null) {
                file = null
                seconds = 0
                error = "تعذّر حفظ التسجيل — أعد المحاولة."
            } else {
                file = result
            }
        } catch (e: Exception) {
            error = "تعذّر إيقاف التسجيل: ${recorderReason(e)}"
        }
    }

    val done = !recording && file != null && seconds > 0
    val timeLabel = "%02d:%02d".format(seconds / 60, seconds % 60)

    ModalBottomSheet(
        onDismissRequest = {
            if (recording) recorder.cancel()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("تسجيل صوتي مباشر", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .size(120.dp)
                    .background(
                        if (recording && !paused) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = when {
                        done -> adminGreen
                        recording && !paused -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(timeLabel, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    recording && paused -> "موقوف مؤقتاً"
                    recording -> "جارٍ التسجيل..."
                    done -> "انتهى التسجيل — جاهز للرفع"
                    else -> "اضغط للبدء"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !recording && !done -> {
                        TextButton(
                            onClick = {
                                recorder.cancel()
                                onDismiss()
                            },
                        ) { Text("إلغاء") }
                        Button(
                            onClick = { start() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(
                                Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                // نقطة التسجيل الحمراء فوق زرّ اللون الأوّل:
                                // الزرّ فاتح في الوضع الداكن (ذهب)، فالأحمر
                                // النقيّ يذوب عليه ⇒ درجته الداكنة هناك.
                                tint = if (isAdminDarkTheme()) Red800 else Color.Red,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("بدء التسجيل")
                        }
                    }

                    recording -> {
                        TextButton(
                            onClick = {
                                paused = if (paused) !recorder.resume() else recorder.pause()
                            },
                        ) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = null,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (paused) "متابعة" else "إيقاف مؤقت")
                        }
                        Button(
                            onClick = { stop() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("إنهاء")
                        }
                    }

                    else -> {
                        TextButton(onClick = { start() }) {
                            Icon(Icons.Filled.Replay, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("إعادة التسجيل")
                        }
                        Button(
                            onClick = {
                                val f = file ?: return@Button
                                onRecorded(f, "تسجيل_${System.currentTimeMillis()}.m4a")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("استخدام هذا التسجيل")
                        }
                    }
                }
            }
        }
    }
}
