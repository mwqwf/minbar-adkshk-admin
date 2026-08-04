package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.core.AppConfig
import com.ali.ishaqiyin_admin.data.AdminAppConfigRepository
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.UpdateConfig
import com.ali.ishaqiyin_admin.data.UpdateConfigRepository
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

/**
 * ⬆️ «تذكير التحديث» — للمالك وحده.
 *
 * يضبط الأرقام التي يقارن بها تطبيق منبر العام إصداره:
 *  - **أحدث إصدار**: من هو أقدم منه يرى شريطاً لطيفاً على الرئيسية يمكنه صرفه.
 *  - **أقدم إصدار مدعوم**: من هو أقدم منه يرى تذكيراً عند كل تشغيل.
 *
 * لا يحجب التطبيق أحداً في الحالتين — الدروس المنزَّلة تبقى قابلة للاستماع
 * مهما قدُم الإصدار. التذكير دعوة لا بوّابة.
 */
@Composable
fun UpdateConfigScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf("") }
    var minSupported by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(UpdateConfigRepository.Target.PublicApp) }
    // إعلان المجموعة: افتراضيّ لتذكير اللوحة — المشرفون كلّهم فيها، وهو
    // أسرع طريق يبلغهم بالإصدار الجديد بلا انتظار فتحهم للوحة.
    var announce by remember { mutableStateOf(true) }

    // تبديل الهدف يعيد التحميل: لكلّ تطبيق وثيقته وأرقامه.
    LaunchedEffect(target) {
        loading = true
        val current = runCatching { UpdateConfigRepository.load(target) }.getOrNull()
        latest = current?.latestVersionCode?.takeIf { it > 0 }?.toString().orEmpty()
        minSupported = current?.minSupportedVersionCode?.takeIf { it > 0 }?.toString().orEmpty()
        message = current?.message.orEmpty()
        loading = false
    }

    AdminScaffold(title = "تذكير التحديث", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // اختيار التطبيق المستهدَف — لكلّ واحد وثيقته وأرقامه.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateConfigRepository.Target.entries.forEach { option ->
                    FilterChip(
                        selected = target == option,
                        onClick = { if (!saving) target = option },
                        label = { Text(option.label) },
                    )
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize()) { FullScreenLoader() }
                return@Column
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "كيف يعمل؟",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (target == UpdateConfigRepository.Target.AdminApp) {
                            "اللوحة تقرأ هذين الرقمين مرّة كل ست ساعات وتحفظهما محلياً، " +
                                "ثم تقارنهما برقم إصدارها فتُظهر للمشرف شاشة تحديث. " +
                                "رابط الاختبار المغلق ثابت لا يتغيّر، ولا يُحجب مشرف عن " +
                                "العمل في أيّ حالة."
                        } else {
                            "التطبيق يقرأ هذين الرقمين مرّة كل ست ساعات ويحفظهما محلياً، " +
                                "ثم يقارنهما برقم إصداره. لا يُحجب أحد عن الاستماع في أي حالة."
                        },
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 🧭 الرقم المعروف لهذا البناء — يملأ الحقل بضغطة بدل أن يُكتب
            // من الذاكرة. نسيان هذه الخطوة هو ما أبقى الرقم عند 10 بينما
            // المنشور 13، فلم يُذكَّر أحد بالتحديث طوال ذلك الوقت.
            val known = when (target) {
                UpdateConfigRepository.Target.AdminApp ->
                    com.ali.ishaqiyin_admin.BuildConfig.VERSION_CODE
                UpdateConfigRepository.Target.PublicApp -> AppConfig.PUBLIC_APP_VERSION_CODE
            }
            if ((latest.toIntOrNull() ?: 0) < known) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = adminOrange.copy(alpha = 0.14f),
                    ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "الرقم المحفوظ (${latest.ifBlank { "0" }}) أقدم من آخر إصدار " +
                                "نعرفه ($known) — لن يُذكَّر أحد بالتحديث ما لم يُرفَع.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Button(onClick = { latest = known.toString() }) {
                            Text("استعمل $known")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = latest,
                onValueChange = { latest = it.filter(Char::isDigit) },
                label = { Text("أحدث إصدار منشور (versionCode)") },
                supportingText = { Text("من هو أقدم منه يرى شريطاً لطيفاً يمكنه صرفه.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = adminFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = minSupported,
                onValueChange = { minSupported = it.filter(Char::isDigit) },
                label = { Text("أقدم إصدار مدعوم (اختياري)") },
                supportingText = {
                    Text("من هو أقدم منه يُذكَّر عند كل تشغيل. اتركه فارغاً إن لم يوجد سبب قويّ.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = adminFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= 300) message = it },
                label = { Text("رسالة مخصّصة (اختياري)") },
                supportingText = { Text("تُترك فارغة ⇒ نصّ افتراضي مناسب. ${message.length}/300") },
                minLines = 3,
                colors = adminFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // 📣 إعلان المجموعة — يخصّ اللوحة وحدها: مشرفوها كلّهم في
            // مجموعة الإدارة، فرسالة واحدة تبلغهم فوراً بإشعار الدردشة
            // نفسه بدل انتظار أن يفتح كلّ واحد اللوحة فيرى شاشة التذكير.
            if (target == UpdateConfigRepository.Target.AdminApp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(checked = announce, onCheckedChange = { announce = it })
                    Column(Modifier.weight(1f)) {
                        Text(
                            "أعلن في مجموعة الإدارة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "تُرسَل رسالة «صدر إصدار جديد من اللوحة» مع رابط " +
                                "التحديث، فيصل إشعارها لكلّ المشرفين.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !saving && latest.isNotBlank(),
                    onClick = {
                        val latestCode = latest.toIntOrNull() ?: 0
                        val minCode = minSupported.toIntOrNull() ?: 0
                        // الحدّ الأدنى فوق الأحدث يجعل التذكير القويّ يظهر
                        // للجميع بمن فيهم من على أحدث نسخة — والقاعدة على
                        // الخادم ترفضه أصلاً، فنمنعه هنا برسالة مفهومة.
                        if (minCode > latestCode) {
                            snack("«أقدم إصدار مدعوم» لا يصحّ أن يتجاوز «أحدث إصدار».")
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            val result = runCatching {
                                UpdateConfigRepository.save(
                                    UpdateConfig(
                                        latestVersionCode = latestCode,
                                        minSupportedVersionCode = minCode,
                                        message = message.trim(),
                                    ),
                                    target = target,
                                )
                            }
                            saving = false
                            result
                                .onSuccess {
                                    val announced =
                                        target == UpdateConfigRepository.Target.AdminApp &&
                                            announce
                                    if (announced) {
                                        ChatRepository.sendText(
                                            buildString {
                                                append("📣 صدر إصدار جديد من لوحة الإدارة ")
                                                append("(رقم $latestCode).\n")
                                                val custom = message.trim()
                                                if (custom.isNotEmpty()) {
                                                    append(custom)
                                                    append("\n")
                                                }
                                                append("حدِّث اللوحة من صفحة الاختبار المغلق:\n")
                                                append(AdminAppConfigRepository.PLAY_URL)
                                            },
                                        )
                                    }
                                    snack(
                                        if (announced) {
                                            "حُفظ إعداد التذكير وأُعلن في المجموعة."
                                        } else {
                                            "حُفظ إعداد التذكير."
                                        },
                                    )
                                }
                                .onFailure { snack("تعذّر الحفظ: ${it.arabicReason()}") }
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text("حفظ")
                }
            }

            Text(
                "ملاحظة: الحفظ متاح للمالك وحده.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
