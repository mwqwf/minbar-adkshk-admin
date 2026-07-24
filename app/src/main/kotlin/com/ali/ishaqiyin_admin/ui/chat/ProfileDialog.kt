package com.ali.ishaqiyin_admin.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.ChatMember
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.ui.AdminTextField
import com.ali.ishaqiyin_admin.ui.LocalSnack
import com.ali.ishaqiyin_admin.ui.Spin
import com.ali.ishaqiyin_admin.ui.kTeal
import com.ali.ishaqiyin_admin.util.pickedFileFrom
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * حوار الملفّ الشخصي في المجموعة: اختيار الاسم المعروض والصورة الشخصيّة.
 * يظهر تلقائيّاً مرّة واحدة بعد أوّل تسجيل دخول (firstRun)، ويُفتح لاحقاً
 * من «معلومات المجموعة» متى شاء العضو.
 */
@Composable
fun ProfileDialog(firstRun: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var me by remember { mutableStateOf<ChatMember?>(null) }
    var name by remember { mutableStateOf("") }
    var pickedPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    val googleName = FirebaseAuth.getInstance().currentUser?.displayName
        ?: FirebaseAuth.getInstance().currentUser?.email?.substringBefore('@')
        ?: "مستخدم"

    LaunchedEffect(Unit) {
        me = ChatRepository.fetchSelf()
        name = me?.customName?.takeIf { it.isNotEmpty() } ?: googleName
        loaded = true
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) pickedPhoto = uri }

    if (!loaded) return

    AlertDialog(
        onDismissRequest = { if (!firstRun) onDismiss() },
        title = { Text(if (firstRun) "أهلاً بك في المجموعة! 👋" else "ملفّي الشخصي") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (firstRun) {
                    Text(
                        "اختر اسمك وصورتك — هكذا سيراك بقيّة الأعضاء في دردشة الإدارة. " +
                            "يمكنك تغييرهما لاحقاً من معلومات المجموعة.",
                        fontSize = 12.sp,
                        color = ChatColors.textMuted,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                Box(
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !saving) { picker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = pickedPhoto
                    if (photo != null) {
                        AsyncImage(
                            model = photo,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp).clip(CircleShape),
                        )
                    } else {
                        MemberAvatar(
                            uid = me?.uid.orEmpty(),
                            name = name,
                            photo = me?.displayPhoto.orEmpty(),
                            radius = 40,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .background(ChatColors.accentDark, CircleShape)
                            .padding(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "اضغط الصورة لتغييرها",
                    fontSize = 10.5.sp,
                    color = ChatColors.textMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                AdminTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = "اسمي في المجموعة",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) return@Button
                    saving = true
                    scope.launch {
                        try {
                            ChatRepository.setMyName(trimmed)
                            pickedPhoto?.let { uri ->
                                val file = context.pickedFileFrom(uri)
                                ChatRepository.setMyPhoto(uri, file.name)
                            }
                            onDismiss()
                        } catch (e: Exception) {
                            saving = false
                            snack("تعذّر الحفظ: ${e.message ?: e}")
                        }
                    }
                },
                enabled = !saving && name.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = kTeal),
            ) {
                if (saving) {
                    Spin(size = 14)
                } else {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(if (firstRun) "ابدأ" else "حفظ")
            }
        },
        dismissButton = {
            if (!firstRun) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("إلغاء") }
            }
        },
    )
}
