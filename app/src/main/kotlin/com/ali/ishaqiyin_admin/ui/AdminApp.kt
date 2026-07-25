package com.ali.ishaqiyin_admin.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SecurityUpdateWarning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ali.ishaqiyin_admin.data.AccessState
import com.ali.ishaqiyin_admin.data.AccessVerificationException
import com.ali.ishaqiyin_admin.data.AdminNotificationService
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.ui.chat.ChatScreen
import com.ali.ishaqiyin_admin.ui.chat.DmListScreen
import com.ali.ishaqiyin_admin.ui.chat.DmScreen
import com.ali.ishaqiyin_admin.ui.chat.GroupInfoScreen
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
    const val OWNER_REVIEW = "owner_review"
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
        composable(Routes.OWNER_REVIEW) { OwnerReviewScreen(onBack = { nav.popBackStack() }) }
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
