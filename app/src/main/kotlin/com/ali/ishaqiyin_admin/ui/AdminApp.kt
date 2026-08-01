package com.ali.ishaqiyin_admin.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SecurityUpdateWarning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ali.ishaqiyin_admin.data.AccessState
import com.ali.ishaqiyin_admin.data.AccessVerificationException
import com.ali.ishaqiyin_admin.data.AdminNotificationService
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.ChatUploadTarget
import com.ali.ishaqiyin_admin.data.ChatUploader
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.chatTypeForMime
import com.ali.ishaqiyin_admin.ui.chat.ChatColors
import com.ali.ishaqiyin_admin.ui.chat.ChatScreen
import com.ali.ishaqiyin_admin.ui.chat.DmListScreen
import com.ali.ishaqiyin_admin.ui.chat.DmScreen
import com.ali.ishaqiyin_admin.ui.chat.GroupInfoScreen
import com.ali.ishaqiyin_admin.ui.chat.MemberAvatar
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

object Routes {
    const val DASHBOARD = "dashboard"
    const val ALERTS = "alerts"
    const val NOTIFY = "notify"
    const val ADD_LESSON = "add_lesson"
    const val MANAGE_SECTIONS = "manage_sections"
    const val MANAGE_ALL = "manage_all"
    const val ANALYTICS = "analytics"
    const val FEEDBACK = "feedback"
    const val ADMINS = "admins"
    const val SUPERVISORS = "supervisors"
    const val SUBMISSIONS = "submissions"
    const val TRANSCRIPT_INTAKE = "transcript_intake"
    const val OWNER_REVIEW = "owner_review"
    const val FEATURED = "featured"
    const val CHAT = "chat"
    const val GROUP_INFO = "group_info"
    const val DM_LIST = "dm_list"
    const val DM = "dm/{threadId}/{otherUid}/{otherName}"

    fun dm(threadId: String, otherUid: String, otherName: String): String =
        "dm/${Uri.encode(threadId)}/${Uri.encode(otherUid)}/${Uri.encode(otherName)}"
}

/**
 * بوّابة المصادقة + التنقّل — تُوجّه حسب الصلاحية: دخول / لوحة (مالك أو
 * مشرف) / رمز اعتماد / محظور (نفس منطق _AuthGate في main.dart).
 */
@Composable
fun AdminApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var access by remember { mutableStateOf(AccessState.SignedOut) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolveTrigger by remember { mutableIntStateOf(0) }

    // إعادة المحاولة عند عودة التطبيق للمقدمة: إقلاع خلف قفل الشاشة يخنق
    // الشبكة فيفشل التحقق مؤقتاً، وشاشة الخطأ المسدودة توحي بانهيار.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (error != null && !loading) resolveTrigger++
    }

    LaunchedEffect(Unit) {
        AuthService.authState().collect { resolveTrigger++ }
    }

    LaunchedEffect(resolveTrigger) {
        loading = true
        error = null
        // ثلاث محاولات بمهلة متصاعدة قبل إظهار شاشة الخطأ — الفشل الشائع
        // عابر (شبكة/إقلاع)، وشاشة الخطأ توحي للمستخدم بانهيار التطبيق.
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(attempt * 2000L)
            try {
                // مهلة صريحة: بلاها يبقى الدوّار أبديّاً إن علق أيّ نداء
                // شبكيّ، والمستخدم بلا زرّ إعادة محاولة.
                val state = withTimeout(25_000) { AuthService.resolveAccess() }
                access = state
                loading = false
                if (state == AccessState.Owner || state == AccessState.Supervisor) {
                    scope.launch {
                        AdminNotificationService.registerCurrentDevice(
                            isOwner = state == AccessState.Owner,
                        )
                    }
                }
                return@LaunchedEffect
            } catch (e: Throwable) {
                // إلغاء التركيب يجب أن يمرّ (وإلّا استمرّت الحلقة تكتب الحالة
                // بعد زوال الشاشة)؛ مهلة withTimeout وحدها تُعاد محاولتها.
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                lastError = e
            }
        }
        loading = false
        error = (lastError as? AccessVerificationException)?.message
            ?: "تعذّر التحقق من الصلاحية. تحقق من الاتصال ثم أعد المحاولة."
    }

    val showSnack: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalSnack provides showSnack) {
        Box(Modifier.fillMaxSize()) {
            when {
                loading -> Surface(color = kBg, modifier = Modifier.fillMaxSize()) {
                    FullScreenLoader()
                }

                error != null -> AccessErrorView(
                    message = error!!,
                    onRetry = { resolveTrigger++ },
                    onSignOut = { scope.launch { AuthService.signOut(context) } },
                )

                access == AccessState.Owner || access == AccessState.Supervisor ->
                    AdminNavHost(isOwner = access == AccessState.Owner)

                access == AccessState.NeedsOwnerCode -> OwnerCodeScreen(
                    onApproved = { resolveTrigger++ },
                )

                access == AccessState.Blocked -> BlockedView(
                    onSignOut = { scope.launch { AuthService.signOut(context) } },
                )

                else -> LoginScreen()
            }
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun AdminNavHost(isOwner: Boolean) {
    val nav: NavHostController = rememberNavController()
    // نصّ وصل بالمشاركة الخارجية (بلا ملفات): يفتح اختيار الدرس مباشرة.
    val transcriptPayload by TranscriptIntake.payload.collectAsState()
    LaunchedEffect(transcriptPayload) {
        if (transcriptPayload != null &&
            nav.currentDestination?.route != Routes.TRANSCRIPT_INTAKE
        ) {
            nav.navigate(
                Routes.TRANSCRIPT_INTAKE,
                NavOptions.Builder().setLaunchSingleTop(true).build(),
            )
        }
    }
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(isOwner = isOwner, nav = nav) }
        composable(Routes.ALERTS) { AlertsScreen(isOwner = isOwner, onBack = { nav.popBackStack() }) }
        composable(Routes.NOTIFY) { NotifyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ADD_LESSON) { AddLessonScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MANAGE_SECTIONS) {
            ManageSectionsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.MANAGE_ALL) { ManageAllScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ANALYTICS) { AnalyticsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.FEEDBACK) { FeedbackScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ADMINS) {
            AdminsScreen(
                isOwner = isOwner,
                onBack = { nav.popBackStack() },
                onOpenSupervisors = { nav.navigate(Routes.SUPERVISORS) },
            )
        }
        composable(Routes.SUPERVISORS) { SupervisorsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SUBMISSIONS) { SubmissionsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TRANSCRIPT_INTAKE) {
            TranscriptIntakeScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.OWNER_REVIEW) { OwnerReviewScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.FEATURED) { FeaturedScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CHAT) { ChatScreen(isOwner = isOwner, nav = nav) }
        composable(Routes.GROUP_INFO) {
            GroupInfoScreen(isOwner = isOwner, nav = nav, onBack = { nav.popBackStack() })
        }
        composable(Routes.DM_LIST) { DmListScreen(nav = nav, onBack = { nav.popBackStack() }) }
        composable(Routes.DM) { entry ->
            DmScreen(
                threadId = entry.arguments?.getString("threadId").orEmpty(),
                otherUid = entry.arguments?.getString("otherUid").orEmpty(),
                otherName = entry.arguments?.getString("otherName").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }

    // 📤 ورقة وجهة المشاركة الخارجية — فوق أيّ شاشة، فالمشاركة قد تصل
    // والمستخدم داخل الدردشة لا على اللوحة.
    ShareDestinationSheets(nav)
}

/**
 * 📤 خيارات المشاركة الخارجية (نمط واتساب): تظهر فور وصول ملفّات من تطبيق
 * آخر، وتترك للمستخدم اختيار الوجهة — نموذج الدرس الصوتي، أو مجموعة
 * الإدارة، أو محادثة خاصّة مع مشرف. لا شيء يُرسَل قبل الاختيار.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareDestinationSheets(nav: NavHostController) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val incoming by ShareIntake.incoming.collectAsState()

    // لا مستمع أعضاء ولا ورقة ما لم تصل مشاركة — والخروج المبكر يُسقط حالة
    // «قائمة المشرفين» فتبدأ كلّ دفعة جديدة من قائمة الوجهات.
    if (incoming.isEmpty()) return

    var pickAdmin by remember { mutableStateOf(false) }
    val preparing by ShareIntake.preparing.collectAsState()
    val hasAudio = remember(incoming) {
        incoming.any { context.shareContentType(it).startsWith("audio/") }
    }
    val hasImage = remember(incoming) {
        incoming.any { context.shareContentType(it).startsWith("image/") }
    }
    val label = if (incoming.size == 1) {
        incoming.first().name
    } else {
        "${incoming.size} ملفّات"
    }

    // ⚠️ النسخ إلى الكاش قبل الرفع مقصود: إذن قراءة الـUri الوارد مع
    // ACTION_SEND مؤقّت ومربوط بحياة النشاط ولا يقبل التثبيت، بينما
    // ChatUploader يرفع في نطاق على مستوى العمليّة — فمغادرة التطبيق أثناء
    // رفع ملفّ كبير كانت تُسقط الإذن ويضيع الملفّ بصمت.
    fun send(target: ChatUploadTarget, notice: String, onSent: () -> Unit) {
        if (preparing) return
        val files = incoming
        ShareIntake.prepareForChat(
            context = context,
            files = files,
            onReady = { prepared ->
                prepared.forEach { item ->
                    // نوع المحتوى يُقرأ من الوارد الأصلي: اسم النسخة هو نفسه
                    // والـContentResolver لا يفيد مع `file://`.
                    val contentType = context.shareContentType(item.source)
                    ChatUploader.enqueue(
                        target = target,
                        file = item.file,
                        type = chatTypeForMime(contentType),
                        contentType = contentType,
                        deleteAfter = item.cached,
                    )
                }
                ShareIntake.consumeIncoming(files)
                snack(
                    if (prepared.size < files.size) {
                        "تعذّر تجهيز بعض الملفّات — يُرسَل ${prepared.size} من ${files.size}."
                    } else {
                        notice
                    },
                )
                runCatching { onSent() }
            },
            onFailure = { message -> snack(message) },
        )
    }

    if (!pickAdmin) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            // الصرف ممنوع أثناء التجهيز كي لا تختفي الورقة وسط نسخ الملفّ.
            onDismissRequest = { if (!preparing) ShareIntake.clearIncoming() },
            sheetState = sheetState,
            containerColor = ChatColors.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    "مشاركة إلى إدارة منبر",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Text(
                    label,
                    fontSize = 12.sp,
                    color = ChatColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
                if (preparing) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spin(color = kTeal, size = 16)
                        Spacer(Modifier.size(10.dp))
                        Text("جارٍ التجهيز…", fontSize = 13.sp, color = ChatColors.textMuted)
                    }
                }
                HorizontalDivider()
                if (hasAudio) {
                    ShareOptionRow(
                        icon = Icons.Filled.LibraryMusic,
                        tint = kOrange,
                        title = "إضافة درس صوتي",
                        subtitle = "تعبئة نموذج الدرس بالملفّ المشترَك",
                        enabled = !preparing,
                    ) {
                        ShareIntake.chooseLesson()
                        // انتقال مباشر إلى النموذج: الرجوع القسري إلى اللوحة
                        // كان يمحو مكدّس التنقّل ومعه أيّ نموذج قيد التعبئة.
                        // واللوحة نفسها تفتح النموذج عند امتلاء الطابور، فلا
                        // ننتقل مرّتين حين تكون هي الشاشة الحاليّة.
                        if (nav.currentDestination?.route != Routes.DASHBOARD) {
                            nav.navigate(
                                Routes.ADD_LESSON,
                                NavOptions.Builder().setLaunchSingleTop(true).build(),
                            )
                        }
                    }
                }
                if (hasImage) {
                    ShareOptionRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        tint = kGreen,
                        title = "النص المشروح لدرس",
                        subtitle = "إرفاق صورة صفحة الكتاب بنص درس (مع القصّ والدمج)",
                        enabled = !preparing,
                    ) {
                        // نسخ الصور للكاش أولاً (إذن الـUri الوارد مؤقت) ثم فتح
                        // اختيار الدرس معبّأً بها.
                        val files = incoming.filter {
                            context.shareContentType(it).startsWith("image/")
                        }
                        ShareIntake.prepareForChat(
                            context = context,
                            files = files,
                            onReady = { prepared ->
                                TranscriptIntake.set(
                                    text = "",
                                    images = prepared.map { it.file.uri },
                                )
                                ShareIntake.consumeIncoming(files)
                                if (ShareIntake.incoming.value.isEmpty()) {
                                    ShareIntake.clearIncoming()
                                }
                                nav.navigate(
                                    Routes.TRANSCRIPT_INTAKE,
                                    NavOptions.Builder().setLaunchSingleTop(true).build(),
                                )
                            },
                            onFailure = { failure -> snack(failure) },
                        )
                    }
                }
                ShareOptionRow(
                    icon = Icons.Filled.Groups,
                    tint = kTeal,
                    title = "إرسال إلى مجموعة الإدارة",
                    subtitle = "يظهر للمشرفين جميعاً في دردشة المجموعة",
                    enabled = !preparing,
                ) {
                    send(ChatUploadTarget.Group, "جارٍ الإرسال إلى مجموعة الإدارة…") {
                        nav.navigate(Routes.CHAT)
                    }
                }
                ShareOptionRow(
                    icon = Icons.Filled.Person,
                    tint = kBlue,
                    title = "إرسال إلى محادثة خاصّة",
                    subtitle = "اختر مشرفاً واحداً لإرسال الملفّ إليه",
                    enabled = !preparing,
                ) {
                    pickAdmin = true
                }
            }
        }
        return
    }

    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة.
    val membersList by remember { ChatRepository.membersStream() }
        .collectAsState(initial = emptyList())
    val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val others = membersList.filter { it.uid != myUid }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!preparing) pickAdmin = false },
        sheetState = sheetState,
        containerColor = ChatColors.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                "إرسال إلى مشرف",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
            if (preparing) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spin(color = kTeal, size = 16)
                    Spacer(Modifier.size(10.dp))
                    Text("جارٍ التجهيز…", fontSize = 13.sp, color = ChatColors.textMuted)
                }
            }
            HorizontalDivider()
            if (others.isEmpty()) {
                Text(
                    "لا يوجد مشرفون آخرون بعد.",
                    color = ChatColors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                others.forEach { member ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !preparing) {
                                val threadId = DmRepository.ensureThread(member.uid)
                                send(
                                    ChatUploadTarget.Dm(threadId, member.uid),
                                    "جارٍ الإرسال إلى ${member.displayName}…",
                                ) {
                                    nav.navigate(
                                        Routes.dm(threadId, member.uid, member.displayName),
                                    )
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberAvatar(
                            uid = member.uid,
                            name = member.displayName,
                            photo = member.displayPhoto,
                            radius = 20,
                            showOnline = true,
                            online = member.isOnline,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    member.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (member.isOwner) {
                                    Spacer(Modifier.size(5.dp))
                                    Text("👑", fontSize = 12.sp)
                                }
                            }
                            Text(
                                if (member.isOnline) "متصل الآن" else member.email,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (member.isOnline) {
                                    ChatColors.online
                                } else {
                                    ChatColors.textMuted
                                },
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = { pickAdmin = false },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("رجوع إلى الخيارات")
            }
        }
    }
}

/** صفّ خيار واحد في ورقة وجهة المشاركة. */
@Composable
private fun ShareOptionRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = ChatColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccessErrorView(message: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Surface(color = kBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.SecurityUpdateWarning,
                    contentDescription = null,
                    tint = kDanger,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "تعذّر التحقق من صلاحية الحساب",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("إعادة المحاولة")
                }
                TextButton(onClick = onSignOut) { Text("تسجيل الخروج") }
            }
        }
    }
}

@Composable
private fun BlockedView(onSignOut: () -> Unit) {
    Surface(color = kBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Block,
                    contentDescription = null,
                    tint = kDanger,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "تم تعليق وصولك",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = kDanger,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "تواصل مع المالك لإعادة التفعيل.",
                    textAlign = TextAlign.Center,
                    color = kMuted,
                )
                Spacer(Modifier.height(22.dp))
                TextButton(onClick = onSignOut) { Text("تسجيل الخروج") }
            }
        }
    }
}

/** شاشة تعذّر تهيئة Firebase (نظير _FirebaseInitErrorView). */
@Composable
fun FirebaseInitErrorView(retrying: Boolean, onRetry: () -> Unit) {
    Surface(color = kBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = kDanger,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "تعذّر الاتصال بخدمات التطبيق",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "لم تكتمل تهيئة Firebase أو App Check. لن نعرض شاشة دخول " +
                        "مضلّلة؛ أعد المحاولة بعد التحقق من الإنترنت.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    enabled = !retrying,
                    colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                ) {
                    if (retrying) {
                        Spin(color = Color.White, size = 18)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("إعادة المحاولة")
                }
            }
        }
    }
}
