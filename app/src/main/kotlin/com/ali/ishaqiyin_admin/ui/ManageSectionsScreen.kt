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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import kotlinx.coroutines.launch

@Composable
fun ManageSectionsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var catName by remember { mutableStateOf("") }
    var subName by remember { mutableStateOf("") }
    var subCategoryId by remember { mutableStateOf<String?>(null) }
    var busyCat by remember { mutableStateOf(false) }
    var busySub by remember { mutableStateOf(false) }

    suspend fun refreshCategories() {
        runCatching { categories = AdminRepository.fetchCategories() }
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
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "إنشاء قسم رئيسي",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = kTeal,
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
                            busyCat = true
                            scope.launch {
                                try {
                                    AdminRepository.addCategory(name)
                                    catName = ""
                                    refreshCategories()
                                    snack("تم إنشاء القسم الرئيسي.")
                                } catch (e: Exception) {
                                    snack("تعذّر إنشاء القسم: ${e.message ?: e}")
                                }
                                busyCat = false
                            }
                        },
                        enabled = !busyCat,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (busyCat) Spin() else Text("إنشاء القسم الرئيسي")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "إنشاء قسم فرعي",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = kTeal,
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
                            busySub = true
                            scope.launch {
                                try {
                                    AdminRepository.addSubcategory(name, parent)
                                    subName = ""
                                    subCategoryId = null
                                    snack("تم إنشاء القسم الفرعي.")
                                } catch (e: Exception) {
                                    snack("تعذّر إنشاء القسم الفرعي: ${e.message ?: e}")
                                }
                                busySub = false
                            }
                        },
                        enabled = !busySub,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        if (busySub) Spin() else Text("إنشاء القسم الفرعي")
                    }
                }
            }
        }
    }
}
