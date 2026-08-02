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
import androidx.compose.runtime.getValue
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
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

/** رسالة موحّدة حين تُحفظ الكتابة محليّاً ولم يؤكّدها الخادم بعد. */
private const val PENDING_NETWORK_HINT =
    "لا اتصال — حُفظ الطلب وسيُرسَل تلقائياً عند عودة الشبكة (بلا تكرار)."

/** تطبيع الاسم للمقارنة: مسافات مضغوطة وحالة أحرف موحّدة. */
private fun normalizedName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")

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

    suspend fun refreshCategories() {
        runCatching {
            categories = AdminRepository.fetchCategories()
            // تُجلب الفرعية أيضاً لمنع تكرار اسم داخل القسم الرئيسي نفسه.
            subcategories = AdminRepository.fetchSubcategories()
        }
    }

    LaunchedEffect(Unit) { refreshCategories() }

    AdminScaffold(title = "إدارة الأقسام", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
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
                        enabled = !busyCat,
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
                        enabled = !busySub,
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
