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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.ali.ishaqiyin_admin.data.SupportRepository
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.needsAttention
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
import kotlinx.coroutines.withContext

/**
 * 🔗 تدفّقات شارات اللوحة، مشتركة على مستوى العمليّة.
 *
 * كانت كلّ عودة إلى اللوحة تُعيد ربط ستّة مستمعي Firestore من الصفر. هنا
 * تُنشأ مرّة وتبقى حيّة 30 ثانية بعد آخر مشترك (`WhileSubscribed`)، فالتنقّل
 * ذهاباً وإياباً لا يكلّف شيئاً. ومكسب جانبي: «مهامّي اليوم» وشبكة الإجراءات
 * تقرآن الرقم نفسه فلا يختلفان أبداً.
 *
 * ⚠️ تبديل الحساب يُسقط المخزون كلّه — وإلّا بقي مستمع يقرأ ببريد سابق.
 *
 * مرئيّ للوحدة كلّها لا للملفّ وحده: شاشة المساهمات تقرأ العدد نفسه
 * من هذا المخزون بدل ربط مستمعَي Firestore من جديد.
 */
internal object DashboardBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = SharingStarted.WhileSubscribed(30_000)
    private val cache = HashMap<String, StateFlow<*>>()
    private var boundUid: String = ""

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    private fun <T> shared(
        key: String,
        initial: T,
        /** سياسة المشاركة — الافتراض إبقاء المستمع حيّاً 30 ثانية بعد آخر مشترك. */
        sharing: SharingStarted = started,
        create: () -> Flow<T>,
    ): StateFlow<T> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid != boundUid) {
            boundUid = uid
            cache.clear()
        }
        return cache.getOrPut(key) { create().stateIn(scope, sharing, initial) } as StateFlow<T>
    }

    fun chatUnread(): StateFlow<Int> =
        shared("chat", 0) { ChatRepository.unreadCountStream() }

    fun dmUnread(): StateFlow<Int> =
        shared("dm", 0) { DmRepository.unreadThreadsStream() }

    fun pendingAudio(): StateFlow<Int> =
        shared("submissions", 0) { SubmissionsRepository.watchPendingCount() }

    fun pendingTranscripts(): StateFlow<Int> =
        shared("transcripts", 0) { TranscriptsRepository.watchPendingCount() }

    /**
     * 📬 رسائل المستخدمين غير المقروءة — **للمالك وحده** تدفّقٌ حقيقيّ:
     * قواعد الخيوط تخصّه، فربط مستمع لغيره رفضُ صلاحيّة متكرّر بلا فائدة.
     */
    fun supportUnread(isOwner: Boolean): StateFlow<Int> =
        shared("support:$isOwner", 0) {
            if (isOwner) SupportRepository.watchUnreadCount() else flowOf(0)
        }

    // ⛔ watchCount تدفّق يبثّ قيمة واحدة ثم يكتمل: مشاركته بمهلة الـ30 ثانية
    // كانت تُبقي عدّاً قديماً بعد تفريغ السلة والعودة السريعة إلى اللوحة
    // (المهلة لم تنقضِ فلا يُعاد تشغيل تدفّق اكتمل أصلاً). مهلة الصفر تعيد
    // العدّ عند كلّ عودة، وتبقى آخر قيمة معروضة ريثما يصل الجديد.
    fun trash(): StateFlow<Int> =
        shared("trash", 0, sharing = SharingStarted.WhileSubscribed(0)) {
            TrashRepository.watchCount()
        }

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

    // الأرقام المحفوظة تظهر فور قراءتها؛ عدّ الخادم يحلّ محلّها حين يصل.
    // ⛔ كانت `loadCounts` تُستدعى داخل `remember` أثناء التركيب: أوّل وصول
    // لملف SharedPreferences يحمّله من القرص **متزامناً على خيط الواجهة**
    // فيقطع إطاراً عند الدخول — القراءة انتقلت إلى IO داخل الأثر أدناه،
    // وريثما تصل تُظهر المربّعات مؤشّر تحميلها المعتاد (القيمة صفر).
    var counts by remember { mutableStateOf(DashboardCounts(0, 0, 0)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var showProfileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTrigger) {
        // آخر الأعداد المحفوظة — مرّة واحدة عند أوّل تركيب لا مع كلّ «تحديث».
        if (reloadTrigger == 0) {
            counts = withContext(Dispatchers.IO) { loadCounts(context) }
        }
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
                            .background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
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
                    color = MaterialTheme.colorScheme.primary,
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

// ⚠️ arabicCount نُقلت إلى ArabicPlural.kt لتُستعمل في كلّ الشاشات.

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
    val supportUnread by DashboardBadges.supportUnread(isOwner).collectAsState()
    val queue by UploadQueue.items.collectAsState()
    // ⛔ كانت `loadCache` (قراءة SharedPreferences + فكّ JSON للقطة التحليلات)
    // تُنفَّذ داخل `remember` أثناء التركيب على خيط الواجهة — الآن على IO
    // بعد التركيب، والقيمة تبدأ صفراً فلا يظهر السطر إلّا حين تصل فعلاً.
    val missingTranscripts by produceState(0) {
        value = withContext(Dispatchers.IO) {
            AnalyticsRepository.loadCache(context)?.missingTranscripts ?: 0
        }
    }

    val now = System.currentTimeMillis()
    // ⚠️ كان العدّ على `parked` وحدها، والدرس الذي انقطع رفعه أو فشل دون بلوغ
    // سقف المحاولات لا يُركن — فلا تنبيه في أيّ مكان بينما هو عالق فعلاً.
    // («بانتظار الإنترنت» و«انقطع أثناء الرفع» مستثنيان في `needsAttention`:
    // كلاهما يمضي وحده ولا يحتاج قراراً، وعدّهما هنا كان يجعل كلّ إقلاع
    // للجهاز يُظهر «مهمّة عاجلة» لدرس سليم في طريقه.)
    val parked = queue.count { it.needsAttention }
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
                        "درس متعثّر في الرفع — يحتاج قرارك",
                        "درسان متعثّران في الرفع — يحتاجان قرارك",
                        "دروس متعثّرة في الرفع — تحتاج قرارك",
                        "درساً متعثّراً في الرفع — تحتاج قرارك",
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
        // 📬 رسالة مستخدم تنتظر ردّاً: إنسانٌ ينتظر جواباً، فتسبق ما ينتظر
        // مراجعةً. (بلا استعلام جديد — نفس تدفّق شارة اللوحة.)
        if (supportUnread > 0) {
            add(
                TodayTask(
                    icon = Icons.Filled.Forum,
                    text = arabicCount(
                        supportUnread,
                        "رسالة جديدة من مستخدم",
                        "رسالتان جديدتان من مستخدمين",
                        "رسائل جديدة من المستخدمين",
                        "رسالة جديدة من المستخدمين",
                    ),
                    onClick = { nav.navigate(Routes.SUPPORT) },
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
                        "تنبيهاً جديداً لم تطّلع عليه",
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
                        "درساً في السلة قبل حذفه نهائياً",
                    ),
                    onClick = { nav.navigate(Routes.TRASH) },
                ),
            )
        }
    }

    // 🎨 البطاقة تحتفظ بهويّتها الفيروزيّة في الوضعين: تدرّج مصمت فوق الفاتح
    // كما هو، ونظيره الداكن المرتفع فوق الداكن (لا لوح أبيض فوق خلفيّة ليل)،
    // ومحتواها يتبع ما تحته لا لوناً ثابتاً.
    val scheme = MaterialTheme.colorScheme
    val darkTheme = isAdminDarkTheme()
    val cardBrush = if (darkTheme) {
        Brush.horizontalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceVariant))
    } else {
        Brush.horizontalGradient(listOf(kTeal, kTealDark))
    }
    val onCard = if (darkTheme) scheme.onSurface else Color.White

    Column(
        Modifier
            .fillMaxWidth()
            .background(cardBrush, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = null,
                tint = if (darkTheme) scheme.primary else Color.White,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "مهامّي اليوم",
                color = onCard,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (tasks.isEmpty()) {
            Text(
                "لا مهام — عملك مكتمل ✅",
                color = onCard,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            tasks.forEach { task ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        // هدف لمس 48dp (جمهور اللوحة كبار سنّ): الصفّ كان
                        // ~34dp متلاصقاً بجاريه فتصيب النقرة غير المقصود.
                        .heightIn(min = 48.dp)
                        .clickable(onClick = task.onClick)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        task.icon,
                        contentDescription = null,
                        tint = onCard,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        task.text,
                        color = onCard,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 🎨 ألوان بطاقات الشبكة. لونها **دلاليّ** لا زخرفيّ: يميّز كلّ بطاقة عن
 * جارتها، فلا يجوز أن يلتقي لونان في درجة واحدة عند التكيّف مع الداكن.
 * قيم الوضع الفاتح تبقى كما هي حرفاً بحرف.
 */
private class GridColors(
    val teal: Color,
    val tealDeep: Color,
    val blue: Color,
    val gold: Color,
    val danger: Color,
    val orange: Color,
    val purple: Color,
    val green: Color,
)

/**
 * بنفسجيّ «التحليلات» على الداكن: لا سلّم بنفسجيّاً في `Theme.kt`، وهذه
 * الدرجة تعطي 8.65 على السطح الداكن وتبقى متمايزة عن بقيّة ألوان الشبكة.
 */
private val PurpleOnDark = Color(0xFFC9A2E8)

@Composable
private fun gridColors(): GridColors {
    val scheme = MaterialTheme.colorScheme
    val dark = isAdminDarkTheme()
    return GridColors(
        teal = if (dark) scheme.tertiary else kTeal,
        tealDeep = if (dark) scheme.onTertiaryContainer else kTealDark,
        blue = adminBlue,
        gold = adminGold,
        danger = if (dark) scheme.error else kDanger,
        orange = adminOrange,
        purple = if (dark) PurpleOnDark else kPurple,
        green = adminGreen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val supportUnread by DashboardBadges.supportUnread(isOwner).collectAsState()
    val c = gridColors()

    data class ActionSpec(
        val icon: ImageVector,
        val color: Color,
        val label: String,
        val subtitle: String,
        val badge: Int,
        val route: String,
        /**
         * أبواب هذه البطاقة حين تكون **مجمَّعة**: النقر يفتح ورقة تحمل
         * هذه الأبواب بدل الانتقال مباشرةً. فارغة ⇒ بطاقة مباشرة.
         */
        val entries: List<ActionSpec> = emptyList(),
    )

    val cards = buildList {
        // بطاقة واحدة للمحادثات كلّها: المجموعة والخاصّ في قائمة واحدة
        // كواتساب. بطاقتان منفصلتان كانتا تُخفيان محادثات المشرف الخاصّة
        // خلف باب لا يتذكّره أحد.
        add(
            ActionSpec(
                Icons.Filled.Forum, c.teal, "المحادثات",
                "مجموعة الإدارة والرسائل الخاصّة",
                chatUnread + dmUnread, Routes.DM_LIST,
            ),
        )
        // 📚 **المحتوى** — كلّ ما يخصّ الدروس والأقسام في باب واحد: إضافةً
        // وتنظيماً وتعديلاً وتمييزاً واستعادةً. كانت خمس بطاقات متجاورة
        // تؤدّي كلّها إلى الشيء نفسه (المحتوى) فتُغرق الشاشة بلا معنى.
        add(
            ActionSpec(
                Icons.Filled.LibraryMusic, c.orange, "المحتوى",
                "درس جديد، الأقسام، التعديل، المميّزة، المحذوفات",
                trashCount + featuredActive, "",
                entries = listOf(
                    ActionSpec(
                        Icons.Filled.LibraryMusic, c.orange, "إضافة درس صوتي",
                        "رفع ملفات أو تسجيل مباشر", 0, Routes.ADD_LESSON,
                    ),
                    ActionSpec(
                        Icons.Filled.AccountTree, c.tealDeep, "إدارة الأقسام",
                        "إنشاء رئيسي وفرعي", 0, Routes.MANAGE_SECTIONS,
                    ),
                    ActionSpec(
                        Icons.AutoMirrored.Filled.ManageSearch, c.blue, "التعديل والبحث",
                        "تعديل وحذف وجدولة", 0, Routes.MANAGE_ALL,
                    ),
                    ActionSpec(
                        Icons.Filled.Star, c.gold, "مختارات المنبر",
                        "الدروس المميّزة ومدّتها", featuredActive, Routes.FEATURED,
                    ),
                    ActionSpec(
                        Icons.Filled.RestoreFromTrash, c.danger, "سلة المحذوفات",
                        "استعادة الدروس المحذوفة (30 يوماً)", trashCount, Routes.TRASH,
                    ),
                ),
            ),
        )
        // 📥 المساهمات تبقى بطاقةً مستقلّة: صندوق وارد ينتظر قراراً، ودفنه
        // داخل مجموعة يؤخّر ما يجب أن يُرى فوراً.
        add(
            ActionSpec(
                Icons.Filled.HowToVote, c.gold, "المساهمات",
                "دروس ونصوص المستمعين", pendingSubmissions, Routes.SUBMISSIONS,
            ),
        )
        // 📈 قياس الأثر وسماع المستمعين وجهان لشيء واحد.
        add(
            ActionSpec(
                Icons.Filled.Insights, c.purple, "التحليلات والتفاعل",
                "الاستماع والأقسام والملاحظات", 0, "",
                entries = listOf(
                    ActionSpec(
                        Icons.Filled.Insights, c.purple, "التحليلات والأثر",
                        "الاستماع والأقسام النشطة", 0, Routes.ANALYTICS,
                    ),
                    ActionSpec(
                        Icons.Filled.Forum, c.green, "تفاعل المستمعين",
                        "الملاحظات والبلاغات", 0, Routes.FEEDBACK,
                    ),
                ),
            ),
        )
        // ⚙️ الإدارة: الحسابات والصلاحيات، ومراجعة المالك، وضبط تذكير
        // التحديث. (هذا الأخير كان **بلا أيّ مدخل في الواجهة** — شاشة
        // مبنيّة لا يصلها أحد، فبقي رقم الإصدار المنشور قديماً.)
        add(
            ActionSpec(
                Icons.Filled.VerifiedUser, c.teal, "الإدارة",
                if (isOwner) "الرسائل والمشرفون والمراجعة" else "صلاحيتك وحسابك",
                suspicious.size + supportUnread, "",
                entries = buildList {
                    add(
                        ActionSpec(
                            Icons.Filled.VerifiedUser, c.teal,
                            if (isOwner) "الحساب والمشرفون" else "الحساب والصلاحية",
                            if (isOwner) "الاعتماد والحظر والحذف" else "صلاحيتك وخروجك",
                            0, Routes.ADMINS,
                        ),
                    )
                    // 🕘 «آخر ما جرى»: من عدّل ماذا ومتى. البيانات كانت
                    // مكتوبة في القاعدة مع كل تعديل بلا شاشة تعرضها.
                    add(
                        ActionSpec(
                            Icons.Filled.History, c.blue, "آخر ما جرى",
                            "من عدّل ماذا ومتى", 0, ROUTE_RECENT_CHANGES,
                        ),
                    )
                    // لغير المالك لا يُنشأ شيء من هذه، فلا يظهر له أثر.
                    if (isOwner) {
                        // 📬 صندوق رسائل المستخدمين — أوّل أبواب «الإدارة»
                        // لأنّ خلفه إنساناً ينتظر جواباً.
                        add(
                            ActionSpec(
                                Icons.Filled.Forum, c.green, "رسائل المستخدمين",
                                "اقتراحات وبلاغات وطلبات إشراف",
                                supportUnread, Routes.SUPPORT,
                            ),
                        )
                        add(
                            ActionSpec(
                                Icons.Filled.GppMaybe, c.danger, "الدروس المشبوهة",
                                "مراجعة خاصة بالمالك", suspicious.size, Routes.OWNER_REVIEW,
                            ),
                        )
                        add(
                            ActionSpec(
                                Icons.Filled.SystemUpdate, c.blue, "تذكير التحديث",
                                "أرقام إصدار التطبيق واللوحة", 0, Routes.UPDATE_CONFIG,
                            ),
                        )
                    }
                },
            ),
        )
    }

    // البطاقة المجمَّعة المفتوحة الآن (ورقة أبوابها).
    var openHub by remember { mutableStateOf<ActionSpec?>(null) }
    // «آخر ما جرى» شاشة كاملة تُفتح فوق اللوحة بلا مسار تنقّل خاصّ بها.
    var showRecentChanges by remember { mutableStateOf(false) }

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
                            onClick = {
                                if (spec.entries.isEmpty()) {
                                    nav.navigate(spec.route)
                                } else {
                                    openHub = spec
                                }
                            },
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    openHub?.let { hub ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { openHub = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    hub.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
                HorizontalDivider()
                hub.entries.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                openHub = null
                                if (entry.route == ROUTE_RECENT_CHANGES) {
                                    showRecentChanges = true
                                } else {
                                    nav.navigate(entry.route)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(
                                    entry.color.copy(alpha = 0.16f),
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = entry.color,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                entry.subtitle,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // الشارة تُرافق بابها داخل الورقة أيضاً، فيعرف المشرف
                        // أين ينتظره العمل قبل أن يفتح.
                        if (entry.badge > 0) {
                            Box(
                                Modifier
                                    .background(entry.color, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    if (entry.badge > 99) "+99" else "${entry.badge}",
                                    color = contentColorOn(entry.color),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRecentChanges) {
        Dialog(
            onDismissRequest = { showRecentChanges = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            ) {
                RecentChangesScreen(onBack = { showRecentChanges = false })
            }
        }
    }
}

/** مدخل «آخر ما جرى» داخل ورقة «الإدارة» — شاشة لا مسار تنقّل. */
private const val ROUTE_RECENT_CHANGES = "recent_changes_sheet"

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
    // التلوين الخفيف يذوب في الليل: تُرفع نسبته على الداكن وحده كي تبقى
    // البطاقة بطاقةً — أمّا على الفاتح فالنسب كما كانت حرفاً بحرف.
    val dark = isAdminDarkTheme()
    val fillAlpha = if (dark) 0.12f else 0.05f
    val strokeAlpha = if (dark) 0.32f else 0.22f
    val wellAlpha = if (dark) 0.20f else 0.14f

    Surface(
        color = color.copy(alpha = fillAlpha),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(122.dp)
            .border(1.dp, color.copy(alpha = strokeAlpha), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(color.copy(alpha = wellAlpha), RoundedCornerShape(14.dp)),
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
                            // الشارة قرصٌ مصمت بلون البطاقة: رقمها يتبع
                            // إضاءته (حبر داكن فوق ذهب الليل لا أبيض).
                            color = contentColorOn(color),
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
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
