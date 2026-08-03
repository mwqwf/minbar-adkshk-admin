package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AuthService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة الدخول — مطابقة لنمط نبراس: ترحيب ثم Google فقط (لا بريد/كلمة مرور).
 * بعد نجاح الدخول تحدّد بوّابة المصادقة الوجهة (لوحة/رمز اعتماد/محظور).
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "لوحة إدارة منبر ادكصهك",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "سجّل الدخول بحساب Google. المالك يدخل مباشرة، " +
                        "وأي حساب جديد يحتاج رمز اعتماد من المالك.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        loading = true
                        error = ""
                        scope.launch {
                            val err = AuthService.signInWithGoogle(context)
                            loading = false
                            if (err != null) error = err
                        }
                    },
                    enabled = !loading,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (loading) Spin(size = 18) else Icon(
                        Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (loading) "جارٍ الدخول..." else "تسجيل الدخول بـ Google")
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * شاشة رمز الاعتماد — نظير step='owner_code' في نبراس، بفارق واحد فقط:
 * نبراس يُرسل الرمز إلى بريد المالك (SMTP)؛ هنا لا بريد مُعَدّاً، فالرمز
 * يظهر حيّاً للمالك داخل تطبيقه (شاشة «الحساب والمشرفون»)، وعلى المرشّح أن
 * يطلب من المالك إبلاغه به ثم يُدخله هنا. صلاحية الرمز 10 دقائق، 5 محاولات،
 * وحدّ معدّل 60 ثانية بين الطلبات — مطابق تماماً لنبراس.
 */
@Composable
fun OwnerCodeScreen(onApproved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }

    suspend fun requestCode(silent: Boolean) {
        if (busy) return
        busy = true
        error = ""
        if (!silent) info = ""
        val res = AuthService.requestOwnerCode()
        busy = false
        if (res.ok) {
            requested = true
            info = "تمّ توليد الرمز. اطلب من المالك فتح «الحساب والمشرفون» في " +
                "تطبيقه ليرى الرمز ويُبلّغك به (صلاحيته 10 دقائق)."
            cooldown = 60
            return
        }
        when (res.reason) {
            "rate_limited" -> {
                requested = true
                cooldown = res.retryAfterSec ?: 60
                if (!silent) {
                    error = "رمز سابق ما يزال صالحاً. انتظر قليلاً قبل طلب رمز جديد."
                }
            }

            "blocked" -> error = "تم تعليق وصولك من قبل الإدارة."
            "already_authorized" -> onApproved()
            else -> if (!silent) error = "تعذّر توليد الرمز. حاول مرة أخرى."
        }
    }

    LaunchedEffect(Unit) { requestCode(silent = true) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    suspend fun verify() {
        if (code.trim().length != 6) {
            error = "أدخل الرمز المكوّن من 6 أرقام."
            return
        }
        busy = true
        error = ""
        val res = AuthService.verifyOwnerCode(code.trim())
        if (res.ok) {
            onApproved()
            return
        }
        busy = false
        when (res.reason) {
            "expired" -> {
                error = "انتهت صلاحية الرمز. اطلب رمزاً جديداً."
                requested = false
            }

            "too_many_attempts" -> {
                error = "محاولات كثيرة فاشلة. اطلب رمزاً جديداً."
                code = ""
                requested = false
            }

            "no_code" -> {
                error = "لا يوجد رمز معلَّق. اطلب رمزاً جديداً."
                requested = false
            }

            else -> error = "الرمز غير صحيح."
        }
    }

    val email = AuthService.currentUser?.email.orEmpty()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("رمز اعتماد المشرف", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "سجّلت الدخول بـ:\n$email",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(18.dp))
                if (info.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Text(info, textAlign = TextAlign.Center, lineHeight = 24.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (requested) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
                        placeholder = {
                            Text(
                                "••••••",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 28.sp,
                            letterSpacing = 10.sp,
                            textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        colors = adminFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { scope.launch { verify() } },
                        enabled = !busy,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (busy) Spin(size = 18) else Text("تحقّق")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { scope.launch { requestCode(silent = false) } },
                        enabled = !busy && cooldown <= 0,
                    ) {
                        Text(
                            if (cooldown > 0) "إعادة الإرسال خلال $cooldown ث" else "طلب رمز جديد",
                        )
                    }
                } else {
                    Button(
                        onClick = { scope.launch { requestCode(silent = false) } },
                        enabled = !busy,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (busy) Spin(size = 18) else Icon(
                            Icons.Filled.LockClock,
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("طلب رمز اعتماد")
                    }
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(18.dp))
                // لا يُعطَّل أبداً: تعطيله أثناء busy يحبس المستخدم في الشاشة
                // إن تعذّر ردّ الخادم على طلب الرمز.
                TextButton(
                    onClick = { scope.launch { AuthService.signOut(context) } },
                ) { Text("إلغاء وتسجيل الخروج") }
            }
        }
    }
}
