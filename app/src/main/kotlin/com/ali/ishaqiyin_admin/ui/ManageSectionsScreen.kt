package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** رسالة موحّدة حين تُحفظ الكتابة محليّاً ولم يؤكّدها الخادم بعد. */
private const val PENDING_NETWORK_HINT =
    "لا اتصال — حُفظ الطلب وسيُرسَل تلقائياً عند عودة الشبكة (بلا تكرار)."

/** تطبيع الاسم للمقارنة: مسافات مضغوطة وحالة أحرف موحّدة. */
private fun normalizedName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * نظير `AdminRepository.sectionKey` حرفاً بحرف — للفحص القَبْليّ فقط.
 *
 * ⛔ لماذا وُجد: معرّف وثيقة القسم يُشتقّ من **الاسم عند الإنشاء** ولا يتغيّر
 * عند إعادة التسمية، والكتابة تقع بـ`set()` الكاسح. فإنشاء قسم باسم قسمٍ سبق
 * أن أُنشئ ثمّ غُيِّر اسمه كان يشتقّ المعرّف القديم نفسه ويدهس وثيقته كاملةً
 * — يختفي القسم المُعاد تسميته وتظهر فروعه ودروسه فجأة تحت الاسم الجديد.
 * الدالّة الأصليّة خاصّة في المستودع، فنشتقّ المفتاح هنا لنرفض قبل الكتابة.
 */
private fun derivedSectionKey(prefix: String, vararg parts: String): String {
    val raw = parts.joinToString("|") {
        it.trim().lowercase().replace(Regex("\\s+"), " ")
    }
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
    return prefix + digest.joinToString("") { byte ->
        "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff)
    }
}

@Composable
fun ManageSectionsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var catName by remember { mutableStateOf("") }
    var subName by remember { mutableStateOf("") }
    var subCategoryId by remember { mutableStateOf<String?>(null) }
    var busyCat by remember { mutableStateOf(false) }
    var busySub by remember { mutableStateOf(false) }
    // ⛔ كان runCatching بلا onFailure: فشل الجلب يُبتلَع فتبقى القائمتان
    // فارغتين بلا رسالة، ويمرّ حارس التكرار المبنيّ عليهما ⇒ أقسام مكرّرة.
    var listsLoaded by remember { mutableStateOf(false) }
    // يفترق عن `!listsLoaded`: هذا لا يصير true إلّا بعد **فشل** فعليّ، كي لا
    // يظهر زرّ «أعد المحاولة» أثناء التحميل الأوّل الجاري.
    var loadFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }

    suspend fun refreshCategories() {
        runCatching {
            categories = AdminRepository.fetchCategories()
            // تُجلب الفرعية أيضاً لمنع تكرار اسم داخل القسم الرئيسي نفسه.
            subcategories = AdminRepository.fetchSubcategories()
        }
            .onSuccess {
                listsLoaded = true
                loadFailed = false
            }
            .onFailure {
                listsLoaded = false
                loadFailed = true
                snack("تعذّر تحميل الأقسام: ${it.arabicReason()}. الإنشاء معطّل حتى ينجح التحميل.")
            }
    }

    // ⛔ كانت الاستدعاءة الوحيدة LaunchedEffect(Unit): فشل الجلب الأوّل يقفل
    // زرّي الإنشاء إلى الأبد بلا أيّ مسار لإعادة المحاولة إلّا مغادرة الشاشة.
    // الآن يُعاد الجلب عند ضغط «أعد المحاولة» وعند عودة الشبكة تلقائياً.
    val online by NetworkMonitor.online.collectAsState()
    LaunchedEffect(retryTick, online) {
        if (!listsLoaded) refreshCategories()
    }

    AdminScaffold(title = "إدارة الأقسام", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // فشل الجلب يعطّل الإنشاء — فلا بدّ من مخرج ظاهر بدل شاشة مقفلة.
            if (loadFailed) {
                RetryBox(
                    message = "تعذّر تحميل الأقسام — الإنشاء معطّل حتى ينجح التحميل.",
                    onRetry = { retryTick++ },
                )
                Spacer(Modifier.height(8.dp))
            }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "إنشاء قسم رئيسي",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    AdminTextField(
                        value = catName,
                        onValueChange = { catName = it },
                        label = "اسم القسم الرئيسي",
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val name = catName.trim()
                            if (name.isEmpty()) return@Button
                            // حارس التكرار الأوّل: قسم بالاسم نفسه موجود
                            // أصلاً في القائمة المحمَّلة — لا يُنشأ ثانٍ.
                            val twin = categories.firstOrNull {
                                normalizedName(it.name) == normalizedName(name)
                            }
                            if (twin != null) {
                                snack("«${twin.name}» موجود مسبقاً — لم يُنشأ قسم مكرّر.")
                                return@Button
                            }
                            // حارس الدهس: المعرّف يُشتقّ من الاسم، وقسمٌ سبق
                            // إنشاؤه بهذا الاسم ثمّ أُعيدت تسميته يحمل المعرّف
                            // نفسه — الكتابة فوقه بـset() تمحوه بكلّ محتواه.
                            val ghost = categories.firstOrNull {
                                it.id == derivedSectionKey("cat_", name)
                            }
                            if (ghost != null) {
                                snack(
                                    "هذا الاسم استُعمل سابقاً لقسمٍ اسمه الآن " +
                                        "«${ghost.name}» — إنشاؤه به يمحو ذاك القسم. " +
                                        "غيّر الاسم ولو بحرف واحد.",
                                )
                                return@Button
                            }
                            busyCat = true
                            scope.launch {
                                try {
                                    // تعيد false إن لم يؤكّد الخادم قبل المهلة:
                                    // الكتابة محفوظة محليّاً بمعرّف ثابت فلا
                                    // تُنتج قسمين لو أعاد المشرف الإدخال.
                                    val confirmed = AdminRepository.addCategory(name)
                                    catName = ""
                                    refreshCategories()
                                    snack(
                                        if (confirmed) {
                                            "تم إنشاء القسم الرئيسي."
                                        } else {
                                            PENDING_NETWORK_HINT
                                        },
                                    )
                                } catch (e: Exception) {
                                    snack("تعذّر إنشاء القسم: ${e.arabicReason()}")
                                }
                                busyCat = false
                            }
                        },
                        // الإنشاء معطّل ما لم تُحمَّل القوائم: حارس التكرار أعمى بدونها.
                        enabled = !busyCat && listsLoaded,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        // الدوّار بلون محتوى الزرّ: أبيض في الفاتح كما كان،
                        // وحبر داكن فوق ذهب الوضع الداكن كي يُرى.
                        if (busyCat) {
                            Spin(color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("إنشاء القسم الرئيسي")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "إنشاء قسم فرعي",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    AdminTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = "اسم القسم الفرعي",
                    )
                    Spacer(Modifier.height(12.dp))
                    AdminDropdown(
                        label = "اختر القسم الرئيسي",
                        items = categories,
                        selected = categories.firstOrNull { it.id == subCategoryId },
                        itemLabel = { it.name },
                        onSelected = { subCategoryId = it.id },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val name = subName.trim()
                            val parent = subCategoryId
                            if (name.isEmpty() || parent == null) {
                                snack("أدخل الاسم واختر القسم الرئيسي.")
                                return@Button
                            }
                            val twin = subcategories.firstOrNull {
                                it.categoryId == parent &&
                                    normalizedName(it.name) == normalizedName(name)
                            }
                            if (twin != null) {
                                snack("«${twin.name}» موجود في هذا القسم — لم يُنشأ فرع مكرّر.")
                                return@Button
                            }
                            // نفس حارس الدهس أعلاه، بمفتاح الفرعيّ المركّب
                            // (الأب + الاسم): فرعٌ أُعيدت تسميته يبقى بمعرّف
                            // اسمه الأوّل، وإنشاء فرعٍ به يكتب فوقه فيمحوه.
                            val ghost = subcategories.firstOrNull {
                                it.id == derivedSectionKey("sub_", parent, name)
                            }
                            if (ghost != null) {
                                snack(
                                    "هذا الاسم استُعمل سابقاً لفرعٍ اسمه الآن " +
                                        "«${ghost.name}» — إنشاؤه به يمحو ذاك الفرع. " +
                                        "غيّر الاسم ولو بحرف واحد.",
                                )
                                return@Button
                            }
                            busySub = true
                            scope.launch {
                                try {
                                    val confirmed =
                                        AdminRepository.addSubcategory(name, parent)
                                    subName = ""
                                    subCategoryId = null
                                    refreshCategories()
                                    snack(
                                        if (confirmed) {
                                            "تم إنشاء القسم الفرعي."
                                        } else {
                                            PENDING_NETWORK_HINT
                                        },
                                    )
                                } catch (e: Exception) {
                                    snack("تعذّر إنشاء القسم الفرعي: ${e.arabicReason()}")
                                }
                                busySub = false
                            }
                        },
                        enabled = !busySub && listsLoaded,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (busySub) {
                            Spin(color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("إنشاء القسم الفرعي")
                        }
                    }
                }
            }
        }
    }
}
