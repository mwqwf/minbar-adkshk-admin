package com.ali.ishaqiyin_admin.ui

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.RestoreFromTrash
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
import com.ali.ishaqiyin_admin.data.AdminAlert
import com.ali.ishaqiyin_admin.data.AdminAlertsFeed
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AnalyticsRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.ChatNotifications
import com.ali.ishaqiyin_admin.data.ChatRepository
import com.ali.ishaqiyin_admin.data.DmRepository
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.OwnerReviewRepository
import com.ali.ishaqiyin_admin.data.SuspiciousLessonReview
import com.ali.ishaqiyin_admin.data.TranscriptsRepository
import com.ali.ishaqiyin_admin.data.TrashRepository
import com.ali.ishaqiyin_admin.data.SubmissionsRepository
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.ui.chat.ProfileDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 🔗 تدفّقات شارات اللوحة، مشتركة على مستوى العمليّة.
 *
 * كانت كلّ عودة إلى اللوحة تُعيد ربط ستّة مستمعي Firestore من الصفر. هنا
 * تُنشأ مرّة وتبقى حيّة 30 ثانية بعد آخر مشترك (`WhileSubscribed`)، فالتنقّل
 * ذهاباً وإياباً لا يكلّف شيئاً. ومكسب جانبي: «مهامّي اليوم» وشبكة الإجراءات
 * تقرآن الرقم نفسه فلا يختلفان أبداً.
 *
 * ⚠️ تبديل الحساب يُسقط المخزون كلّه — وإلّا بقي مستمع يقرأ ببريد سابق.
 */
private object DashboardBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = SharingStarted.WhileSubscribed(30_000)
    private val cache = HashMap<String, StateFlow<*>>()
    private var boundUid: String = ""

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    private fun <T> shared(key: String, initial: T, create: () -> Flow<T>): StateFlow<T> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid != boundUid) {
            boundUid = uid
            cache.clear()
        }
        return cache.getOrPut(key) { create().stateIn(scope, started, initial) } as StateFlow<T>
    }

    fun chatUnread(): StateFlow<Int> =
        shared("chat", 0) { ChatRepository.unreadCountStream() }

    fun dmUnread(): StateFlow<Int> =
        shared("dm", 0) { DmRepository.unreadThreadsStream() }

    fun pendingAudio(): StateFlow<Int> =
        shared("submissions", 0) { SubmissionsRepository.watchPendingCount() }

    fun pendingTranscripts(): StateFlow<Int> =
        shared("transcripts", 0) { TranscriptsRepository.watchPendingCount() }

    fun trash(): StateFlow<Int> =
        shared("trash", 0) { TrashRepository.watchCount() }

    fun featured(): StateFlow<List<Lesson>> =
        shared("featured", emptyList<Lesson>()) { AdminRepository.watchFeatured() }

    /** مصدر واحد للتنبيهات: منه الشارة ومنه بطاقة المهامّ (لا لقطة ثانية). */
    fun alerts(isOwner: Boolean): StateFlow<List<AdminAlert>> =
        shared("alerts:$isOwner", emptyList<AdminAlert>()) { AdminAlertsFeed.stream(isOwner) }

    /** المراجعة السرّيّة للمالك وحده — لغيره تدفّق فارغ بلا أيّ مستمع. */
    fun suspicious(isOwner: Boolean): StateFlow<List<SuspiciousLessonReview>> =
        shared("suspicious:$isOwner", emptyList<SuspiciousLessonReview>()) {
            if (isOwner) {
                OwnerReviewRepository.watchPending()
            } else {
                flowOf(emptyList<SuspiciousLessonReview>())
            }
        }
}

/** آخر أعداد معروفة للأقسام والصوتيات — تُعرض فور الفتح قبل عدّ الخادم. */
private data class DashboardCounts(
    val categories: Int,
    val subcategories: Int,
    val lessons: Int,
)

private const val COUNTS_PREFS = "dashboard_counts_v1"

private fun loadCounts(context: Context): DashboardCounts {
    val p = context.getSharedPreferences(COUNTS_PREFS, Context.MODE_PRIVATE)
    return DashboardCounts(
        categories = p.getInt("categories", 0),
        subcategories = p.getInt("subcategories", 0),
        lessons = p.getInt("lessons", 0),
    )
}

private fun saveCounts(context: Context, counts: DashboardCounts) {
    context.getSharedPreferences(COUNTS_PREFS, Context.MODE_PRIVATE).edit()
        .putInt("categories", counts.categories)
        .putInt("subcategories", counts.subcategories)
        .putInt("lessons", counts.lessons)
        .apply()
}

/**
 * عدّ تجميعي على الخادم: يعيد رقماً واحداً بدل تنزيل المجموعة كاملة —
 * اللوحة كانت تجلب آلاف الوثائق لتعرض ثلاثة أرقام.
 */
private suspend fun countOf(collection: String): Int =
    FirebaseFirestore.getInstance().collection(collection).count()
        .get(AggregateSource.SERVER).await().count.toInt()

@Composable
fun DashboardScreen(isOwner: Boolean, nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // الأرقام المحفوظة تظهر فوراً؛ عدّ الخادم يحلّ محلّها حين يصل.
    var counts by remember { mutableStateOf(loadCounts(context)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var showProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTrigger) {
        loading = true
        error = null
        try {
            AdminRepository.cleanupResolvedAdminAlerts()
            coroutineScope {
                val c = async { countOf("categories") }
                val s = async { countOf("subcategories") }
                val l = async { countOf("lessons") }
                val fresh = DashboardCounts(c.await(), s.await(), l.await())
                counts = fresh
                saveCounts(context, fresh)
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

    // تدفّق واحد للتنبيهات: منه شارة الجرس ومنه سطر البطاقة — لا لقطة ثانية.
    val alerts by DashboardBadges.alerts(isOwner).collectAsState()
    val unreadAlerts = alerts.size

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
            item {
                TodayTasksCard(isOwner = isOwner, unreadAlerts = unreadAlerts, nav = nav)
                Spacer(Modifier.height(12.dp))
            }
            item {
                // أُزيلت إحصائية «الأجهزة»: مصدرها مجموعة legacy توقفت تغذيتها.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    StatBox(
                        "الأقسام الرئيسية",
                        counts.categories,
                        Icons.Filled.Folder,
                        loading && counts.categories == 0,
                    )
                    StatBox(
                        "الأقسام الفرعية",
                        counts.subcategories,
                        Icons.Filled.FolderOpen,
                        loading && counts.subcategories == 0,
                    )
                    StatBox(
                        "الصوتيات",
                        counts.lessons,
                        Icons.Filled.Audiotrack,
                        loading && counts.lessons == 0,
                    )
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

/** بند واحد في «مهامّي اليوم». */
private data class TodayTask(
    val icon: ImageVector,
    val text: String,
    val onClick: () -> Unit,
)

/**
 * صياغة عربية سليمة للعدد: مفرد ومثنّى وجمع قلّة (3–10) وجمع كثرة (11+).
 */
private fun arabicCount(n: Int, one: String, two: String, few: String, many: String): String =
    when {
        n == 1 -> one
        n == 2 -> two
        n <= 10 -> "$n $few"
        else -> "$n $many"
    }

/**
 * ✅ «مهامّي اليوم»: ما ينتظر قرار المشرف الآن، مرتّباً بالأولويّة، وكلّ
 * سطر ينقل مباشرة إلى شاشته.
 *
 * ⚠️ بلا استعلام واحد جديد: كلّ الأرقام من التدفّقات المشتركة نفسها التي
 * تغذّي شارات شبكة الإجراءات، و«الدروس بلا نصّ مشروح» من لقطة التحليلات
 * المحفوظة محليّاً (قراءة قرص لا شبكة).
 */
@Composable
private fun TodayTasksCard(isOwner: Boolean, unreadAlerts: Int, nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingAudio by DashboardBadges.pendingAudio().collectAsState()
    val pendingTranscripts by DashboardBadges.pendingTranscripts().collectAsState()
    val suspicious by DashboardBadges.suspicious(isOwner).collectAsState()
    val trashCount by DashboardBadges.trash().collectAsState()
    val featured by DashboardBadges.featured().collectAsState()
    val queue by UploadQueue.items.collectAsState()
    val missingTranscripts = remember {
        AnalyticsRepository.loadCache(context)?.missingTranscripts ?: 0
    }

    val now = System.currentTimeMillis()
    val parked = queue.count { it.parked }
    // تمييز يوشك على الانتهاء خلال 24 ساعة (الدائم بلا مهلة لا يُحتسب).
    val expiring = featured.mapNotNull { it.featuredUntilMs }
        .filter { it > now && it - now <= 24L * 3_600_000 }
        .sorted()

    // الترتيب = الأولويّة: ما يضيع عمله أوّلاً، ثمّ ما ينتظر قراراً، ثمّ ما
    // ينتظر متابعة.
    val tasks = buildList {
        if (parked > 0) {
            add(
                TodayTask(
                    icon = Icons.Filled.LibraryMusic,
                    text = arabicCount(
                        parked,
                        "درس تعذّر رفعه — يحتاج قرارك",
                        "درسان تعذّر رفعهما — يحتاجان قرارك",
                        "دروس تعذّر رفعها — تحتاج قرارك",
                        "درساً تعذّر رفعها — تحتاج قرارك",
                    ),
                    onClick = { nav.navigate(Routes.ADD_LESSON) },
                ),
            )
        }
        if (suspicious.isNotEmpty()) {
            add(
                TodayTask(
                    icon = Icons.Filled.GppMaybe,
                    text = arabicCount(
                        suspicious.size,
                        "درس مشبوه ينتظر مراجعتك",
                        "درسان مشبوهان ينتظران مراجعتك",
                        "دروس مشبوهة تنتظر مراجعتك",
                        "درساً مشبوهاً تنتظر مراجعتك",
                    ),
                    onClick = { nav.navigate(Routes.OWNER_REVIEW) },
                ),
            )
        }
        if (pendingAudio > 0) {
            add(
                TodayTask(
                    icon = Icons.Filled.HowToVote,
                    text = arabicCount(
                        pendingAudio,
                        "مساهمة صوتيّة تنتظر قرارك",
                        "مساهمتان صوتيّتان تنتظران قرارك",
                        "مساهمات صوتيّة تنتظر قرارك",
                        "مساهمةً صوتيّة تنتظر قرارك",
                    ),
                    onClick = {
                        SubmissionsTarget.set(0)
                        nav.navigate(Routes.SUBMISSIONS)
                    },
                ),
            )
        }
        if (pendingTranscripts > 0) {
            add(
                TodayTask(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    text = arabicCount(
                        pendingTranscripts,
                        "نصّ مشروح ينتظر مراجعتك",
                        "نصّان مشروحان ينتظران مراجعتك",
                        "نصوص مشروحة تنتظر مراجعتك",
                        "نصّاً مشروحاً تنتظر مراجعتك",
                    ),
                    onClick = {
                        SubmissionsTarget.set(1)
                        nav.navigate(Routes.SUBMISSIONS)
                    },
                ),
            )
        }
        if (expiring.isNotEmpty()) {
            val hours = ((expiring.first() - now) / 3_600_000L).toInt()
            val label = if (expiring.size == 1) {
                if (hours < 1) "تمييز ينتهي خلال أقلّ من ساعة" else "تمييز ينتهي بعد $hours س"
            } else {
                arabicCount(
                    expiring.size,
                    "تمييز ينتهي اليوم",
                    "تمييزان ينتهيان اليوم",
                    "تمييزات تنتهي اليوم",
                    "تمييزاً ينتهي اليوم",
                )
            }
            add(
                TodayTask(
                    icon = Icons.Filled.Star,
                    text = label,
                    onClick = { nav.navigate(Routes.FEATURED) },
                ),
            )
        }
        if (unreadAlerts > 0) {
            add(
                TodayTask(
                    icon = Icons.Filled.NotificationsActive,
                    text = arabicCount(
                        unreadAlerts,
                        "تنبيه جديد لم تطّلع عليه",
                        "تنبيهان جديدان لم تطّلع عليهما",
                        "تنبيهات جديدة لم تطّلع عليها",
                        "تنبيهاً جديداً لم تطّلع عليها",
                    ),
                    onClick = { nav.navigate(Routes.ALERTS) },
                ),
            )
        }
        if (missingTranscripts > 0) {
            add(
                TodayTask(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    text = arabicCount(
                        missingTranscripts,
                        "درس بلا نصّ مشروح",
                        "درسان بلا نصّ مشروح",
                        "دروس بلا نصّ مشروح",
                        "درساً بلا نصّ مشروح",
                    ),
                    onClick = { nav.navigate(Routes.ANALYTICS) },
                ),
            )
        }
        if (trashCount > 0) {
            add(
                TodayTask(
                    icon = Icons.Filled.RestoreFromTrash,
                    text = arabicCount(
                        trashCount,
                        "درس في السلة قبل حذفه نهائياً",
                        "درسان في السلة قبل حذفهما نهائياً",
                        "دروس في السلة قبل حذفها نهائياً",
                        "درساً في السلة قبل حذفها نهائياً",
                    ),
                    onClick = { nav.navigate(Routes.TRASH) },
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(kTeal, kTealDark)),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White)
            Spacer(Modifier.size(8.dp))
            Text(
                "مهامّي اليوم",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (tasks.isEmpty()) {
            Text(
                "لا مهام — عملك مكتمل ✅",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            tasks.forEach { task ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = task.onClick)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        task.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        task.text,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsGrid(isOwner: Boolean, nav: NavHostController) {
    // كلّ الشارات من التدفّقات المشتركة (WhileSubscribed): لا إعادة ربط
    // كاملة لمستمعي Firestore مع كلّ رجوع إلى اللوحة.
    val chatUnread by DashboardBadges.chatUnread().collectAsState()
    val dmUnread by DashboardBadges.dmUnread().collectAsState()
    val pendingAudioSubmissions by DashboardBadges.pendingAudio().collectAsState()
    val pendingTranscripts by DashboardBadges.pendingTranscripts().collectAsState()
    val trashCount by DashboardBadges.trash().collectAsState()
    // شارة «المساهمات» = الدروس الصوتية + النصوص المشروحة المعلّقة معاً.
    val pendingSubmissions = pendingAudioSubmissions + pendingTranscripts
    // عدد المميّزة السارية فقط (ما انتهت مدّته لا يُعدّ).
    val featured by DashboardBadges.featured().collectAsState()
    val featuredActive = featured.count {
        it.featuredUntilMs == null || it.featuredUntilMs > System.currentTimeMillis()
    }
    val suspicious by DashboardBadges.suspicious(isOwner).collectAsState()

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
                Icons.Filled.HowToVote, kGold, "المساهمات",
                "دروس ونصوص المستمعين", pendingSubmissions, Routes.SUBMISSIONS,
            ),
        )
        add(
            ActionSpec(
                Icons.Filled.RestoreFromTrash, kDanger, "سلة المحذوفات",
                "استعادة الدروس المحذوفة (30 يوماً)", trashCount, Routes.TRASH,
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
