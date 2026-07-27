package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ali.ishaqiyin_admin.data.AdminAlertsFeed
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.OwnerReviewRepository
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.SubmissionsRepository
import com.ali.ishaqiyin_admin.ui.chat.ProfileDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(isOwner: Boolean, nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var showProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTrigger) {
        loading = true
        error = null
        try {
            AdminRepository.cleanupResolvedAdminAlerts()
            val email = AuthService.currentUser?.email.orEmpty()
            coroutineScope {
                val c = async { AdminRepository.fetchCategories() }
                val s = async { AdminRepository.fetchSubcategories() }
                val l = async { AdminRepository.fetchLessons() }
                val a = async { AdminRepository.fetchAdminAlerts(email, isOwner) }
                listOf(c, s, l, a).awaitAll()
                categories = c.await()
                subcategories = s.await()
                lessons = l.await()
                alerts = a.await()
            }
            loading = false
        } catch (_: Exception) {
            loading = false
            error = "تعذّر جلب البيانات. تحقّق من الاتصال بالإنترنت."
        }
    }

    // انضمام تلقائي للمجموعة، ثم حوار «اسمك وصورتك» مرة واحدة أول دخول
    // (نمط نبراس) — لا يُلحّ إن تعذّر الجلب أو سبق ضبط الملف الشخصي.
    LaunchedEffect(Unit) {
        ChatRepository.upsertSelf(role = if (isOwner) "owner" else "supervisor")
        val me = ChatRepository.fetchSelf()
        if (me != null && !me.profileSet) showProfileDialog = true
        ChatNotifications.syncSubscription()
    }

    // 📤 ملفّات صوتيّة وصلت بالمشاركة — افتح نموذج «إضافة درس» معبّأً بها.
    val pendingShared by ShareIntake.pending.collectAsState()
    LaunchedEffect(pendingShared.size) {
        if (pendingShared.isNotEmpty()) nav.navigate(Routes.ADD_LESSON)
    }

    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val unreadAlerts by remember(isOwner) { AdminAlertsFeed.unreadCount(isOwner) }
        .collectAsState(initial = 0)

    if (showProfileDialog) {
        ProfileDialog(firstRun = true, onDismiss = { showProfileDialog = false })
    }

    AdminScaffold(
        title = "لوحة الإدارة",
        actions = {
            CountBadge(unreadAlerts) {
                IconButton(onClick = { nav.navigate(Routes.ALERTS) }) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = "تنبيهاتك")
                }
            }
            IconButton(onClick = { nav.navigate(Routes.NOTIFY) }) {
                Icon(Icons.Filled.Campaign, contentDescription = "إرسال إشعار")
            }
            IconButton(onClick = { if (!loading) reloadTrigger++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث")
            }
            IconButton(onClick = { scope.launch { AuthService.signOut(context) } }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "تسجيل الخروج")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            // الرفع الجاري مرئيّ من اللوحة أيضاً، لا من شاشة الإضافة وحدها.
            item { UploadQueueBanner() }
            if (error != null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(kDanger.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            error!!,
                            color = kDanger,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (alerts.isNotEmpty()) {
                item {
                    AlertsCard(alerts) { nav.navigate(Routes.ALERTS) }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                // أُزيلت إحصائية «الأجهزة»: مصدرها مجموعة legacy توقفت تغذيتها.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    StatBox("الأقسام الرئيسية", categories.size, Icons.Filled.Folder, loading)
                    StatBox("الأقسام الفرعية", subcategories.size, Icons.Filled.FolderOpen, loading)
                    StatBox("الصوتيات", lessons.size, Icons.Filled.Audiotrack, loading)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "الإجراءات",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = kTealDark,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item { ActionsGrid(isOwner = isOwner, nav = nav) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ActionsGrid(isOwner: Boolean, nav: NavHostController) {
    // ⚠️ remember إلزاميّ: بلاه يُنشأ تدفّق جديد مع كل إعادة تركيب
    // فيُعاد ربط مستمع Firestore في كلّ مرّة (قراءات وبطء بلا داعٍ).
    val chatUnread by remember { ChatRepository.unreadCountStream() }
        .collectAsState(initial = 0)
    val dmUnread by remember { DmRepository.unreadThreadsStream() }
        .collectAsState(initial = 0)
    val pendingSubmissions by remember { SubmissionsRepository.watchPendingCount() }
        .collectAsState(initial = 0)
    // عدد المميّزة السارية فقط (ما انتهت مدّته لا يُعدّ).
    val featured by remember { AdminRepository.watchFeatured() }
        .collectAsState(initial = emptyList())
    val featuredActive = featured.count {
        it.featuredUntilMs == null || it.featuredUntilMs > System.currentTimeMillis()
    }
    val suspicious by remember(isOwner) {
        if (isOwner) {
            OwnerReviewRepository.watchPending()
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    data class ActionSpec(
        val icon: ImageVector,
        val color: Color,
        val label: String,
        val subtitle: String,
        val badge: Int,
        val route: String,
    )

    val cards = buildList {
        add(
            ActionSpec(
                Icons.Filled.Groups, kTeal, "مجموعة الإدارة",
                "دردشة المالك والمشرفين", chatUnread, Routes.CHAT,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.Forum, kBlue, "الرسائل الخاصّة",
                "مراسلة مشرف على حدة", dmUnread, Routes.DM_LIST,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.HowToVote, kGold, "طلبات النشر",
                "مساهمات المستمعين", pendingSubmissions, Routes.SUBMISSIONS,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.LibraryMusic, kOrange, "إضافة درس صوتي",
                "رفع ملفات أو تسجيل مباشر", 0, Routes.ADD_LESSON,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.AccountTree, kTealDark, "إدارة الأقسام",
                "إنشاء رئيسي وفرعي", 0, Routes.MANAGE_SECTIONS,
            ),
        )
        add(
            ActionSpec(
                Icons.AutoMirrored.Filled.ManageSearch, kBlue, "التعديل والبحث",
                "تعديل وحذف وجدولة", 0, Routes.MANAGE_ALL,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.Star, kGold, "مختارات المنبر",
                "الدروس المميّزة ومدّتها", featuredActive, Routes.FEATURED,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.Insights, kPurple, "التحليلات والأثر",
                "الاستماع والأقسام النشطة", 0, Routes.ANALYTICS,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.Forum, kGreen, "تفاعل المستمعين",
                "الملاحظات والبلاغات", 0, Routes.FEEDBACK,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.VerifiedUser, kTeal,
                if (isOwner) "الحساب والمشرفون" else "الحساب والصلاحية",
                if (isOwner) "الاعتماد والحظر والحذف" else "صلاحيتك وخروجك",
                0, Routes.ADMINS,
            ),
        )
        // لا تُنشأ البطاقة ولا البثّ لغير المالك، فلا يظهر له أي أثر.
        if (isOwner) {
            add(
                ActionSpec(
                    Icons.Filled.GppMaybe, kDanger, "الدروس المشبوهة",
                    "مراجعة خاصة بالمالك", suspicious.size, Routes.OWNER_REVIEW,
                ),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { spec ->
                    Box(Modifier.weight(1f)) {
                        ActionCard(
                            icon = spec.icon,
                            color = spec.color,
                            label = spec.label,
                            subtitle = spec.subtitle,
                            badge = spec.badge,
                            onClick = { nav.navigate(spec.route) },
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** بطاقة إجراء واحدة في شبكة اللوحة. */
@Composable
private fun ActionCard(
    icon: ImageVector,
    color: Color,
    label: String,
    subtitle: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Surface(
        color = color.copy(alpha = 0.05f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(122.dp)
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(color.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                if (badge > 0) {
                    Box(
                        Modifier
                            .background(color, RoundedCornerShape(999.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            if (badge > 99) "+99" else "$badge",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = kTealDark,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                    color = kMuted,
                )
            }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<Map<String, Any?>>, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(kTeal, kTealDark)),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(
                    "تنبيهاتك",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            alerts.take(5).forEach { alert ->
                Text(
                    "• ${alert["title"] ?: ""} — ${alert["body"] ?: ""}",
                    color = Color.White,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}
