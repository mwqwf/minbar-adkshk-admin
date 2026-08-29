package com.ali.ishaqiyin_admin.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.PendingUpload
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.util.AudioMerger
import androidx.lifecycle.repeatOnLifecycle
import com.ali.ishaqiyin_admin.util.AudioTranscodeMerger
import com.ali.ishaqiyin_admin.util.Mp3FormatException
import com.ali.ishaqiyin_admin.util.PickedFile
import com.ali.ishaqiyin_admin.util.copyUriToCache
import com.ali.ishaqiyin_admin.util.lessonTitleFromFileName
import com.ali.ishaqiyin_admin.util.pickedFilesFromIo
import com.ali.ishaqiyin_admin.util.smartTitleFromFileName
import com.ali.ishaqiyin_admin.util.sortedByNaturalName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * نافذة اعتبار الإدراج مكرَّراً: تدوير الشاشة يعيد النموذج ممتلئاً بينما
 * الدرس دخل الطابور فعلاً، فضغطة «رفع» ثانية تُنشئ درساً مكرَّراً.
 */
private const val DUPLICATE_WINDOW_MS = 60_000L

/**
 * سقف ما يُختار في المرّة الواحدة. مجلّد كامل من الدروس هو الحالة المقصودة
 * (عشرون ملفّاً وأكثر)، والسقف حارس لجهاز المستخدم لا غير: كلّ ملفّ يُنسخ
 * إلى تخزين التطبيق قبل رفعه، فمئة ملفّ دفعةً تعني نسخاً بحجم عدّة غيغابايت.
 */
private const val BATCH_MAX_FILES = 50

/** نيّة المشرف في عدّة الملفات — سؤال واحد لا معالج خطوات. */
private const val MODE_MERGE = "merge"
private const val MODE_SEPARATE = "separate"

/**
 * العناوين التي عدّلها المشرف بيده في ورقة المراجعة — مفتاحها الـUri فتصمد
 * لحذف ملفّ أو إعادة ترتيب. ⚠️ تُحفظ عبر إعادة الإنشاء: تدوير الشاشة بعد
 * تسمية عشرين درساً بيدٍ واحدة كان سيمحوها كلّها.
 */
private val batchTitlesSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.map { (uri, title) -> "$uri\n$title" } },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.forEach { entry ->
                val parts = entry.split("\n", limit = 2)
                if (parts.size == 2) put(parts[0], parts[1])
            }
        }
    },
)

/** منتقي تاريخ ثم وقت بحوارات النظام (نظير showDatePicker/showTimePicker). */
fun pickDateTime(context: Context, initialMs: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initialMs }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true,
            ).show()
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
        datePicker.maxDate = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
    }.show()
}

/**
 * حفظ الملفات المختارة عبر تدوير الشاشة: (uri، حجم، اسم) في سطر واحد —
 * الاسم آخراً كي لا يكسره أيّ فاصل داخله.
 */
private val pickedFilesSaver = listSaver<SnapshotStateList<PickedFile>, String>(
    save = { list -> list.map { "${it.uri}\n${it.size}\n${it.name}" } },
    restore = { saved ->
        mutableStateListOf<PickedFile>().apply {
            saved.forEach { entry ->
                val parts = entry.split("\n", limit = 3)
                if (parts.size == 3) {
                    add(
                        PickedFile(
                            uri = Uri.parse(parts[0]),
                            name = parts[2],
                            size = parts[1].toLongOrNull() ?: 0L,
                        ),
                    )
                }
            }
        }
    },
)

/**
 * حفظ صور صفحات الكتاب عبر إعادة إنشاء الشاشة — كانت الحقل الوحيد في
 * النموذج المحفوظ بـ`remember`، فأيّ تدوير أو تبديل وضع داكن أو عودة من
 * شاشة القصّ كان يمحو الصور كاملةً بلا تنبيه، فيدخل الدرس الطابور بلا
 * صوره (ومن أرفق صوراً بلا نصّ يفقد النص المشروح كلّه).
 */
private val transcriptImagesSaver = listSaver<SnapshotStateList<Uri>, String>(
    save = { list -> list.map { it.toString() } },
    restore = { saved ->
        mutableStateListOf<Uri>().apply {
            saved.filter { it.isNotEmpty() }.forEach { add(Uri.parse(it)) }
        }
    },
)

/**
 * اقتراح العنوان التالي في سلسلة مرقّمة: عنوان منتهٍ برقم (عربيّ أو
 * هنديّ ٠-٩) يُقترح بزيادته واحداً بنفس صيغة أرقامه. وإن لم ينتهِ برقم
 * فلا اقتراح — النصّ الفارغ يعني «اكتب العنوان بنفسك».
 */
private fun nextTitleSuggestion(previous: String): String {
    val source = previous.trimEnd()
    if (source.isEmpty()) return ""
    val normalized = source.map { ch ->
        if (ch in '٠'..'٩') '0' + (ch - '٠') else ch
    }.joinToString("")
    val match = Regex("(\\d+)$").find(normalized) ?: return ""
    val raw = match.groupValues[1]
    // رقم طويل جدّاً (تاريخ أو معرّف) ليس ترقيم سلسلة — لا يُزاد.
    if (raw.length > 6) return ""
    val next = raw.toLongOrNull()?.plus(1) ?: return ""
    val head = source.substring(0, match.range.first)
    val useArabicDigits = source.any { it in '٠'..'٩' }
    // «الدرس 05» يتبعه «الدرس 06» لا «الدرس 6»: يُحفظ التصفير البادئ.
    val padded = next.toString().padStart(
        if (raw.startsWith("0")) raw.length else 1,
        '0',
    )
    val digits = if (useArabicDigits) {
        padded.map { ch -> '٠' + (ch - '0') }.joinToString("")
    } else {
        padded
    }
    return head + digits
}

@Composable
fun AddLessonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    // rememberSaveable: تدوير الشاشة كان يمسح النموذج كاملاً بعد تعبئته.
    var title by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var subcategoryId by rememberSaveable { mutableStateOf<String?>(null) }

    /** الملفات المختارة بالترتيب — الترتيب هنا هو ترتيب الرفع حرفيّاً. */
    val files = rememberSaveable(saver = pickedFilesSaver) { mutableStateListOf<PickedFile>() }

    // 📁 رفع مجلّد كامل: عدّة ملفّات = عدّة دروس، أو درس واحد مدموج. النيّة
    // تُسأل مرّة واحدة عند تجاوز الملفّ الواحد، وتبقى قابلة للتغيير بنقرة.
    var multiMode by rememberSaveable { mutableStateOf("") }
    // ⚠️ محفوظان عبر إعادة الإنشاء: تدوير الشاشة والسؤالُ معروض كان يبتلعه
    // بلا جواب، فيمضي النموذج على الدمج بلا أن يختار المشرف شيئاً.
    var askMultiMode by rememberSaveable { mutableStateOf(false) }
    var showBatchSheet by rememberSaveable { mutableStateOf(false) }
    /**
     * كم ملفّاً جُهِّز من الدفعة — نَسخُ عشرين ملفّاً يستغرق، فلا يُترك بلا
     * مؤشّر. ⚠️ من **الحالة المشتركة** لا من `remember`: تدوير الشاشة كان
     * يمحو العدّاد فيختفي الشريط والدفعة ماضية في الخلفية.
     *
     * ⚡ القيمة تُقرأ في موضع عرضها وحده: قراءتها هنا كانت ستُعيد تركيب
     * الشاشة كلّها مع **كلّ ملفّ** (خمسون مرّة) بينما النسخ يشتغل.
     */
    val batchState = UploadQueue.batchProgress.collectAsState()
    /** مشتقّ: يتغيّر مرّتين لا غير — بدء الدفعة ونهايتها. */
    val batchRunning by remember { derivedStateOf { batchState.value != null } }
    val batchTitles = rememberSaveable(saver = batchTitlesSaver) {
        mutableStateMapOf<String, String>()
    }
    /** الوضع الفعليّ: «دروس منفصلة» لا معنى له بملفّ واحد. */
    val separate = multiMode == MODE_SEPARATE && files.size > 1
    /**
     * سقف الإضافة: سقف الدمج قيدُ **الدمج وحده**. والنيّة غير المحسومة تأخذ
     * سقف الدفعة — اختيار مجلّد كامل هو الحالة المقصودة والسؤالُ بعده هو ما
     * يحسمها، فالقصّ عند عشرة قبل السؤال كان يبتلع أربعين ملفّاً. وهو نفسه
     * السقف الذي يقصّ عنده المنتقي، فلا يَعِد نصّ الزرّ بما لا يقع.
     */
    val addLimit = if (multiMode == MODE_MERGE) AudioMerger.maxFiles else BATCH_MAX_FILES

    /** عنوان الدرس رقم [index]: ما كتبه المشرف، وإلّا فمن اسم الملفّ. */
    fun batchTitleOf(index: Int): String {
        val file = files[index]
        return batchTitles[file.uri.toString()]?.takeIf { it.isNotBlank() }
            ?: lessonTitleFromFileName(file.name, index)
    }

    /**
     * ❓ السؤال يُطرح متى احتُمِلت نيّتان: أكثر من ملفّ بلا نيّة محسومة، أو
     * نيّة «دمج» محفوظة تجاوز عددُها سقفَ الدمج.
     *
     * ⛔ الشقّ الثاني ليس زينة: نيّةٌ باقية من رفعٍ سابق كانت تمضي بكلّ اختيار
     * لاحق إلى الدمج بلا سؤال — فيبتلع درسٌ واحد مجلّداً كاملاً.
     */
    fun askIntentIfNeeded() {
        if (files.size <= 1) return
        val mergeOverflow = multiMode == MODE_MERGE && files.size > AudioMerger.maxFiles
        if (multiMode.isEmpty() || mergeOverflow) askMultiMode = true
    }

    // «إدراج» لا «رفع»: النموذج لا ينتظر الشبكة إطلاقاً — يُدرَج الدرس في
    // الطابور فيفرغ النموذج فوراً ويستطيع المشرف تعبئة درس آخر بينما
    // يُرفع الأوّل في الخلفية (ويستأنف وحده إن انقطع الاتصال).
    var queuing by remember { mutableStateOf(false) }
    /**
     * الانشغال الحقيقيّ: الحارس المحلّيّ **زائد** علم الدفعة المشترك — إعادة
     * إنشاء النشاط تعيد `queuing` صفراً بينما حلقة الإدراج ماضية في الخلفية.
     */
    val busy = queuing || batchRunning
    var merging by remember { mutableStateOf(false) }
    // 🛡️ تنجو من تدوير الشاشة: بقيّة حقول النموذج محفوظة، فكان الخطأ أو
    // رسالة النجاح تختفي وحدها عند التدوير.
    var message by rememberSaveable { mutableStateOf("") }
    var isError by rememberSaveable { mutableStateOf(false) }
    var featured by rememberSaveable { mutableStateOf(false) }
    // التمييز صار مؤقّتاً: null مع featured=true يعني «دائم».
    var featuredUntil by rememberSaveable { mutableStateOf<Long?>(null) }
    var featuredLabel by rememberSaveable { mutableStateOf("") }
    var showFeatureSheet by remember { mutableStateOf(false) }
    // ⛔ كانت remember: التدوير أثناء تسجيل جارٍ يُزيل ورقة التسجيل فيعمل
    // onDispose { recorder.release() } على تسجيل حيّ ⇒ يضيع بلا رسالة.
    var showRecorder by rememberSaveable { mutableStateOf(false) }

    // «النص المشروح» الاختياري المرافق للدرس — من أحبّ أضافه ومن لم يرد فلا.
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptText by rememberSaveable { mutableStateOf("") }
    var transcriptBookTitle by rememberSaveable { mutableStateOf("") }
    var transcriptSourceRef by rememberSaveable { mutableStateOf("") }
    val transcriptImages = rememberSaveable(saver = transcriptImagesSaver) {
        mutableStateListOf<Uri>()
    }

    // 📝 المسودة التلقائية: إغلاق التطبيق أثناء التعبئة كان يضيّع الحقول
    // كلّها (rememberSaveable ينجو من التدوير لا من إنهاء العمليّة).
    // النصوص والاختيارات وحدها تُحفظ — لا ملفّات الصوت ولا الصور.
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    // 🛡️ سباق الاستعادة/الحفظ: أثر الحفظ قد ينتهي (delay 600ms) قبل انتهاء
    // قراءة المسودة فيكتب null فوقها ويمحوها. لا كتابة قبل انتهاء المحاولة.
    var draftChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val raw = withContext(Dispatchers.IO) { AppPrefs.addLessonDraft }
                ?: return@LaunchedEffect
            // لا استعادة فوق نموذج فيه شيء (عودة من تدوير — حالته محفوظة أصلاً).
            val formEmpty = title.isBlank() && categoryId == null &&
                transcriptText.isBlank() && transcriptBookTitle.isBlank() &&
                transcriptSourceRef.isBlank()
            if (!formEmpty) return@LaunchedEffect
            runCatching {
                val json = JSONObject(raw)
                title = json.optString("title")
                categoryId = json.optString("categoryId").takeIf { it.isNotEmpty() }
                subcategoryId = json.optString("subcategoryId").takeIf { it.isNotEmpty() }
                transcriptText = json.optString("transcriptText")
                transcriptBookTitle = json.optString("transcriptBookTitle")
                transcriptSourceRef = json.optString("transcriptSourceRef")
                if (transcriptText.isNotBlank() || transcriptBookTitle.isNotBlank() ||
                    transcriptSourceRef.isNotBlank()
                ) {
                    transcriptOpen = true
                }
                draftRestored = true
            }
        } finally {
            draftChecked = true
        }
    }
    // حفظ خفيف بمهلة: كلّ تغيير يعيد تشغيل الأثر فلا يُكتب إلّا بعد سكون
    // قصير — والنموذج الفارغ يمسح المسودة بدل حفظ فراغ.
    LaunchedEffect(
        draftChecked, title, categoryId, subcategoryId,
        transcriptText, transcriptBookTitle, transcriptSourceRef,
    ) {
        // لا كتابة (ولا محو) قبل انتهاء محاولة الاستعادة أعلاه.
        if (!draftChecked) return@LaunchedEffect
        delay(600)
        val empty = title.isBlank() && categoryId == null && subcategoryId == null &&
            transcriptText.isBlank() && transcriptBookTitle.isBlank() &&
            transcriptSourceRef.isBlank()
        val payload = if (empty) {
            null
        } else {
            JSONObject()
                .put("title", title)
                .put("categoryId", categoryId.orEmpty())
                .put("subcategoryId", subcategoryId.orEmpty())
                .put("transcriptText", transcriptText)
                .put("transcriptBookTitle", transcriptBookTitle)
                .put("transcriptSourceRef", transcriptSourceRef)
                .toString()
        }
        withContext(Dispatchers.IO) { AppPrefs.addLessonDraft = payload }
    }

    // ⛔ كان runCatching بلا onFailure: فشل الجلب على شبكة ضعيفة يترك
    // القائمتين المنسدلتين فارغتين بلا تفسير ولا مخرج، ورسالة النقص عند
    // «رفع» تقول «اختر القسم الرئيسي» وكأنّ الخطأ من المشرف لا من الشبكة.
    var sectionsLoaded by remember { mutableStateOf(false) }
    var sectionsFailed by remember { mutableStateOf(false) }
    var sectionsRetry by remember { mutableStateOf(0) }
    val netOnline by NetworkMonitor.online.collectAsState()
    // يُعاد الجلب بزرّ «إعادة تحميل الأقسام» وعند عودة الشبكة تلقائياً.
    LaunchedEffect(sectionsRetry, netOnline) {
        if (sectionsLoaded) return@LaunchedEffect
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }.onSuccess {
            sectionsLoaded = true
            sectionsFailed = false
            // إعادة اختيار آخر قسم استُعمل — أغلب الدروس تُضاف إلى القسم نفسه
            // تباعاً. الشرط يمنع تجاوز اختيار مستعاد بعد تدوير الشاشة.
            if (categoryId == null) {
                val savedCategory = AppPrefs.lastAddCategoryId
                    ?.takeIf { saved -> categories.any { it.id == saved } }
                if (savedCategory != null) {
                    categoryId = savedCategory
                    subcategoryId = AppPrefs.lastAddSubcategoryId?.takeIf { saved ->
                        subcategories.any { it.id == saved && it.categoryId == savedCategory }
                    }
                }
            }
        }.onFailure {
            sectionsFailed = true
        }
    }

    // ملفات واردة من المشاركة الخارجية: تُلحق بقائمة الدمج **فور وصولها**
    // حتى والنموذج مفتوح — شارِك صوتية أخرى من أي تطبيق وستنضم للدمج هنا.
    // ⚠️ الجمع مقيّد بدورة حياة الشاشة (STARTED): كان مجمّعاً دائم الحياة،
    // فنموذجٌ مفتوح في خلفية النظام «يسرق» المشاركة بصمت من النموذج الظاهر.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(
            androidx.lifecycle.Lifecycle.State.STARTED,
        ) {
            ShareIntake.pending.collect { queue ->
                val shared = queue.firstOrNull() ?: return@collect
                val duplicate = files.any { it.uri.toString() == shared.uri.toString() }
                // ⛔ السقف يُحسب **هنا** لا من addLimit الملتقَط: هذا الإغلاق
                // يعيش من أوّل تركيب (LaunchedEffect(Unit)) فكان يحمل سقف
                // الدفعة 50 إلى الأبد — حتى بعد اختيار «دمج» بسقفه 10، فتُقبل
                // ملفّات مشارَكة فوق سقف الدمج ويفشل «رفع» بعدها بلا إنذار.
                // (قراءة multiMode هنا حيّة لأنّها حالة Snapshot مفوَّضة.)
                val cap = if (multiMode == MODE_MERGE) AudioMerger.maxFiles else BATCH_MAX_FILES
                when {
                    duplicate -> Unit
                    // لا بوابة صيغة بعد اليوم: الدمج يقبل أي صيغ (إعادة ترميز
                    // AAC/M4A عند الحاجة) — فقط حدّ العدد يبقى.
                    files.size >= cap -> {
                        message = if (multiMode == MODE_MERGE) {
                            "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد " +
                                "— لم يُضف «${shared.name}»."
                        } else {
                            "الحدّ الأقصى $BATCH_MAX_FILES ملفّاً في الدفعة " +
                                "— لم يُضف «${shared.name}»."
                        }
                        isError = true
                    }

                    else -> {
                        files.add(shared)
                        if (title.isBlank()) title = smartTitleFromFileName(shared.name)
                        // الملفّ الثاني يطرح السؤال: درس واحد مدموج أم دروس؟
                        askIntentIfNeeded()
                    }
                }
                ShareIntake.consumeFirst()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // ⚠️ قراءة اسم/حجم كلّ ملفّ استعلامُ ContentResolver عابر للعمليات:
            // كانت متزامنة على خيط الواجهة لكلّ ملفّ (حتى 50 في حلقة واحدة)،
            // فمجلّد من بطاقة SD بطيئة يجمّد الشاشة وقد يُظهر حوار ANR —
            // pickedFilesFromIo تنجز القراءة كلّها على Dispatchers.IO.
            val picked = context.pickedFilesFromIo(uris)
            val existing = files.map { it.uri.toString() }.toSet()
            // ⛔ فرز طبيعيّ بأرقام الأسماء للمضاف حديثاً وحده: منتقي النظام يعيد
            // «الدرس 10» قبل «الدرس 9»، وترتيب الرفع هو ترتيب ظهور الدروس في
            // التطبيق. وقصْره على الجديد يحفظ أيّ ترتيب رتّبه المشرف يدويّاً قبله.
            val fresh = picked
                .filter { it.uri.toString() !in existing }
                .sortedByNaturalName { it.name }
            val combined = files + fresh

            // قيد «MP3 فقط» أُلغي: الدمج يقبل أي صيغ (لصق مباشر إن كانت كلها
            // MP3، وإلا فكّ وإعادة ترميز AAC/M4A) — لا عبء تحويل على المشرف.
            // ⛔ ونيّة «دمج» محفوظة لا تصمد لاختيار مجلّد: تجاوزُ سقفِ الدمج
            // يُسقطها فيُطرح السؤال من جديد. بلا ذلك كانت خمسون ملفّاً تُدمج في
            // درس واحد صامتاً، وقصُّها عند عشرة كان سيبتلع أربعين بلا اختيار.
            if (multiMode == MODE_MERGE && combined.size > AudioMerger.maxFiles) multiMode = ""
            // السقف سقفُ النيّة القائمة بعد ذلك (سقف الدمج للدمج، وسقف الدفعة لما
            // سواه) — وهو نفسه ما يَعِد به نصّ الزرّ، فلا يُقصّ ما لم يُعلَن قصّه.
            val cap = if (multiMode == MODE_MERGE) AudioMerger.maxFiles else BATCH_MAX_FILES
            if (combined.size > cap) {
                message = "الحدّ الأقصى $cap ملفّاً في الدفعة — أُبقي أوّلها."
                isError = true
            } else {
                message = ""
                isError = false
            }
            files.clear()
            files.addAll(combined.take(cap))
            if (title.isBlank() && files.isNotEmpty()) {
                // عنوان مقترح ذكيّ — يزيل الترقيم وبصمات المواقع وأنماط
                // المسجّلات؛ الاسم الآليّ البحت يُترك فارغاً ليكتبه المشرف.
                title = smartTitleFromFileName(files.first().name)
            }
            // ❓ سؤال واحد صريح لا استنتاج: أكثر من ملفّ يحتمل نيّتين لا ثالث لهما.
            askIntentIfNeeded()
        }
    }

    val subsForCategory = subcategories.filter { it.categoryId == categoryId }

    /// أوّل نقص بترتيب منطقي مع رسالة تسمّيه بعينه — الزر لا يُعطَّل،
    /// والنقص يُشرح صراحةً بدل زر أصمّ لا يفسّر امتناعه.
    fun firstMissing(): String? = when {
        files.isEmpty() -> "اختر ملفاً صوتياً (أو سجّل مباشرة) أولاً."
        // في «دروس منفصلة» لكلّ درس عنوانه من اسم ملفّه — حقل العنوان الواحد
        // لا يُستعمل أصلاً، فاشتراطه كان سيمنع رفع مجلّد بلا سبب.
        !separate && title.isBlank() -> "اكتب عنوان الدرس."
        categoryId == null -> "اختر القسم الرئيسي."
        subcategoryId == null && subsForCategory.isEmpty() ->
            "لا توجد أقسام فرعية لهذا القسم — أنشئ واحداً أو اختر قسماً آخر."
        subcategoryId == null -> "اختر القسم الفرعي."
        else -> null
    }

    /**
     * يُدرج الدرس في طابور الرفع ثم يُفرغ النموذج فوراً.
     * لا ينتظر شبكة ولا اكتمال رفع: الملفّ يُنسخ إلى تخزين التطبيق،
     * ويُختم زمن الإضافة الآن فيصل الدرس إلى التطبيق العام بترتيب إضافته.
     */
    fun queueLesson() {
        if (busy) return
        val missing = firstMissing()
        if (missing != null) {
            message = missing
            isError = true
            return
        }
        // 🛡️ حارس التكرار: تدوير الشاشة أثناء التجهيز يعيد النموذج ممتلئاً
        // بينما الدرس دخل الطابور فعلاً، فضغطة «رفع» ثانية كانت تُنشئ درساً
        // مكرَّراً — وclientKey يختلف بين العنصرين فلا يمنعه الخادم.
        val pendingName = if (files.size == 1) files.first().name else null
        val duplicate = UploadQueue.items.value.any { item ->
            val sameFile = if (pendingName != null) {
                item.fileName == pendingName
            } else {
                // الدمج يُدرج باسم موحّد (merged.mp3/merged.m4a) لا باسم أيّ
                // ملفّ مختار، فالمقارنة على بادئته.
                item.fileName.startsWith("merged.")
            }
            sameFile && item.title == title.trim() &&
                System.currentTimeMillis() - item.queuedAtMs < DUPLICATE_WINDOW_MS
        }
        if (duplicate) {
            message = "«${title.trim()}» في طابور الرفع منذ أقلّ من دقيقة — لم يُضف مرّة ثانية."
            isError = true
            return
        }
        queuing = true
        merging = false
        message = ""
        isError = false
        // 🛡️ العنوان يدخل الطابور مقصوصاً: حارس التكرار أعلاه يقارن بـtitle.trim()،
        // فمسافة لاحقة واحدة كانت تُسقط الحارس فيُنشأ درس مكرّر.
        val snapshotTitle = title.trim()
        val cat = categories.firstOrNull { it.id == categoryId }
        val sub = subsForCategory.firstOrNull { it.id == subcategoryId }
        val label = listOfNotNull(cat?.name, sub?.name).joinToString(" ← ")
        // اسما القسمين يُرسَلان مع الدرس: الخادم كان يكتبهما فارغَين، فتسقط
        // هويّة القسم من نسخة الدرس في «سلة المحذوفات».
        val catLabel = cat?.name.orEmpty()
        val subLabel = sub?.name.orEmpty()
        // آخر قسم مستعمَل يُحفظ ليُعاد اختياره تلقائياً في الفتح القادم.
        AppPrefs.lastAddCategoryId = categoryId
        AppPrefs.lastAddSubcategoryId = subcategoryId
        scope.launch {
            try {
                // ⛔ كلّ العمل الدائم (دمج ← إدراج ← إيقاظ العامل) داخل
                // NonCancellable على IO: مغادرة الشاشة أو تدويرها أثناء التجهيز
                // كانت تُلغي هذا الكوروتين فور اكتمال الدمج وقبل الإدراج، فيبقى
                // الملفّ المدموج في الكاش ولا يدخل الدرس الطابور أصلاً ولا رسالة
                // تُنبّه. وتحديث الواجهة يبقى خارجها فلا يُكتب لشاشة زالت.
                val (position, total) = withContext(NonCancellable + Dispatchers.IO) {
                    val queued = if (files.size == 1) {
                        UploadQueue.enqueue(
                            context = context,
                            sourceUri = files.first().uri,
                            fileName = files.first().name,
                            title = snapshotTitle,
                            categoryId = categoryId!!,
                            subcategoryId = subcategoryId!!,
                            sectionLabel = label,
                            featured = featured,
                            featuredUntilMs = featuredUntil,
                            addedBy = AuthService.currentUser?.email.orEmpty(),
                            categoryName = catLabel,
                            subcategoryName = subLabel,
                            transcriptText = transcriptText,
                            transcriptBookTitle = transcriptBookTitle,
                            transcriptSourceRef = transcriptSourceRef,
                            transcriptImages = transcriptImages.toList(),
                        )
                    } else {
                        // عدّة ملفات = درس واحد متّصل: يُدمج محليّاً أوّلاً (لا
                        // يحتاج شبكة) ثم يدخل الطابور ملفّاً واحداً. كل الملفات
                        // MP3 → لصق إطارات بلا إعادة ترميز؛ غير ذلك أو ترميزات
                        // MP3 متنافرة → فكّ الجميع وإعادة ترميز AAC/M4A — الدمج
                        // يصحّ مهما اختلفت الصيغ والناتج صيغة واحدة.
                        merging = true
                        val stamp = System.currentTimeMillis()
                        val locals = files.map { context.copyUriToCache(it.uri, it.name) }
                        var mergedName = "merged.mp3"
                        val merged = try {
                            if (files.all { AudioMerger.isMp3(it.name) }) {
                                try {
                                    AudioMerger.mergeMp3(
                                        inputs = locals,
                                        outputPath = File(
                                            context.cacheDir,
                                            "merged_$stamp.mp3",
                                        ).absolutePath,
                                    )
                                } catch (_: Mp3FormatException) {
                                    mergedName = "merged.m4a"
                                    AudioTranscodeMerger.mergeToM4a(
                                        locals,
                                        File(context.cacheDir, "merged_$stamp.m4a").absolutePath,
                                    )
                                }
                            } else {
                                mergedName = "merged.m4a"
                                AudioTranscodeMerger.mergeToM4a(
                                    locals,
                                    File(context.cacheDir, "merged_$stamp.m4a").absolutePath,
                                )
                            }
                        } finally {
                            // النسخ المؤقّتة تُمسح في النجاح والفشل معاً: كان
                            // الحذف بعد الدمج وحده، فأيّ استثناء يترك نسخة كلّ
                            // ملفّ في cacheDir بلا كانس يمرّ عليها.
                            locals.forEach { runCatching { it.delete() } }
                        }
                        merging = false
                        UploadQueue.enqueueLocalFile(
                            file = merged,
                            fileName = mergedName,
                            title = snapshotTitle,
                            categoryId = categoryId!!,
                            subcategoryId = subcategoryId!!,
                            sectionLabel = label,
                            featured = featured,
                            featuredUntilMs = featuredUntil,
                            addedBy = AuthService.currentUser?.email.orEmpty(),
                            context = context,
                            categoryName = catLabel,
                            subcategoryName = subLabel,
                            transcriptText = transcriptText,
                            transcriptBookTitle = transcriptBookTitle,
                            transcriptSourceRef = transcriptSourceRef,
                            transcriptImages = transcriptImages.toList(),
                        )
                    }
                    // يُقرأ الموقع **قبل** إيقاظ العامل: ملفّ صغير قد يُرفع
                    // ويخرج من الطابور قبل أن نصل إلى بناء الرسالة، فيصير
                    // «الترتيب 0» بلا معنى.
                    val position = UploadQueue.positionOf(queued.id)
                    val total = UploadQueue.liveCount()
                    LessonUploadWorker.kick(context)
                    position to total
                }

                // إفراغ النموذج فوراً — المشرف يواصل إضافة درس آخر.
                // العنوان لا يُفرَغ إن كان جزءاً من سلسلة مرقّمة: يُقترح
                // التالي بزيادة رقمه، وهو الشكل الغالب لدروس المنبر.
                val suggestion = nextTitleSuggestion(snapshotTitle)
                title = suggestion
                files.clear()
                // ⛔ النيّة تُنسى مع الملفّات: بقاؤها على «الدمج» كانت تجعل
                // **كلّ** اختيار لاحق يمضي بلا سؤال — فأوّل رفع مدموج كان
                // يبتلع بعده مجلّداً كاملاً في درس واحد صامتاً.
                multiMode = ""
                batchTitles.clear()
                featured = false
                featuredUntil = null
                featuredLabel = ""
                transcriptText = ""
                transcriptBookTitle = ""
                transcriptSourceRef = ""
                transcriptImages.clear()
                transcriptOpen = false
                queuing = false
                // نجاح الإدراج يمسح المسودة (العنوان المقترح تلقائيّاً ليس عملاً
                // يخشى ضياعه) — وشريط الاستعادة يختفي إن كان ظاهراً.
                draftRestored = false
                withContext(Dispatchers.IO) { AppPrefs.addLessonDraft = null }
                // تأكيد **لكلّ إضافة** لا للأولى فقط: الرسالة الداخلية قد
                // تتطابق نصّاً مع سابقتها فلا يلحظ المشرف تغيّراً، فيُضاف
                // موقع الدرس في الدور ويُرفَق شريط سفليّ يظهر من جديد
                // مع كلّ إدراج.
                val order = if (total > 1 && position > 0) {
                    " (الترتيب $position من $total)"
                } else {
                    ""
                }
                message = "أُضيف «$snapshotTitle» إلى طابور الرفع$order — " +
                    "يكمل في الخلفية ويصلك إشعار عند اكتماله. " +
                    "تستطيع إضافة درس آخر الآن." +
                    if (suggestion.isNotEmpty()) {
                        " العنوان التالي مقترح: «$suggestion» — عدّله إن شئت."
                    } else {
                        ""
                    }
                isError = false
                snack("أُضيف «$snapshotTitle» إلى طابور الرفع$order")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // مغادرة الشاشة بعد تمام الإدراج: العمل الدائم تمّ داخل
                // NonCancellable، فلا رسالة خطأ — يُحترم الإلغاء ويُعاد رميه.
                throw cancel
            } catch (e: Exception) {
                queuing = false
                merging = false
                message = when (e) {
                    is AudioTranscodeMerger.UnsupportedAudioException ->
                        e.message ?: "تعذّر فكّ أحد الملفات الصوتية."
                    is Mp3FormatException ->
                        "تعذّر دمج الملفات — أحدها ليس ملفاً صوتياً سليماً."
                    else -> "تعذّر تجهيز الدرس: ${e.message ?: e}"
                }
                isError = true
            }
        }
    }

    /**
     * 📁 رفع مجلّد كامل: كلّ ملفّ درسٌ مستقلّ.
     *
     * ⛔ حلقة إدراج **واحدة بالترتيب** ثمّ إيقاظ **واحد** في نهايتها — لا
     * عامل لكلّ درس ولا رفع متوازٍ: العامل الواحد يستنزف الطابور بالدور
     * (`UploadQueue.peek` يعيد أصغر `seq` غير مركون)، فترتيب الإضافة هو
     * ترتيب الرفع وترتيب الظهور في التطبيق. لو رُفعت متوازيةً لوصل الملفّ
     * الأخفّ أوّلاً فانقلبت السلسلة كلّها.
     *
     * والاستئناف مضمون بلا شيء إضافيّ: كلّ عنصر يحمل نسخته المحليّة وجلسة
     * رفعه، فانقطاع الشبكة أو إطفاء الهاتف يُكمل من البايت نفسه.
     */
    fun queueSeparateLessons() {
        if (busy) return
        val missing = firstMissing()
        if (missing != null) {
            message = missing
            isError = true
            return
        }
        // لقطة ثابتة قبل أيّ عمل غير متزامن: القائمة قد تتغيّر تحت اليد.
        val plan = files.mapIndexed { index, file -> file to batchTitleOf(index) }
        if (plan.isEmpty()) return
        val cat = categories.firstOrNull { it.id == categoryId }
        val sub = subsForCategory.firstOrNull { it.id == subcategoryId }
        val label = listOfNotNull(cat?.name, sub?.name).joinToString(" ← ")
        val catLabel = cat?.name.orEmpty()
        val subLabel = sub?.name.orEmpty()
        val catId = categoryId!!
        val subId = subcategoryId!!
        val featuredSnapshot = featured
        val featuredUntilSnapshot = featuredUntil
        val transcriptSnapshot = transcriptText
        val transcriptBookSnapshot = transcriptBookTitle
        val transcriptRefSnapshot = transcriptSourceRef
        val transcriptImagesSnapshot = transcriptImages.toList()
        val addedBy = AuthService.currentUser?.email.orEmpty()
        queuing = true
        merging = false
        message = ""
        isError = false
        AppPrefs.lastAddCategoryId = categoryId
        AppPrefs.lastAddSubcategoryId = subcategoryId
        scope.launch {
            // 🛡️ الحجز الذرّيّ **خارج التركيب** هو الحارس الحقيقيّ: إعادة
            // إنشاء النشاط أثناء نسخ الملفّات تُعيد الزرّ صالحاً والحلقةُ
            // الأولى ماضية في `NonCancellable`، فضغطة ثانية كانت تُشغّل حلقة
            // ثانية بلقطتَي مقارنة متداخلتين ⇒ دروس مكرَّرة و`seq` متشابك.
            //
            // ⛔ وموضعه **أوّل الجسد** لا قبل `launch`: إلغاء النطاق قبل بدء
            // الجسد كان سيترك حجزاً بلا `finally` يفكّه — شاشة مقفلة إلى
            // إعادة تشغيل التطبيق. ولا نقطة تعليق بينه وبين `try` فالدخول مضمون.
            if (!UploadQueue.beginBatch(plan.size)) {
                queuing = false
                message = "دفعة دروس قيد التجهيز الآن — انتظر انتهاءها."
                isError = true
                return@launch
            }
            try {
                // ⛔ الإدراج كلّه داخل NonCancellable على IO: مغادرة الشاشة أو
                // تدويرها في منتصف إدراج عشرين ملفّاً كانت ستُلغي الكوروتين
                // فيضيع نصفها بلا رسالة — والملفّات نُسخت وحُذفت مصادرها.
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    val inserted = mutableListOf<PendingUpload>()
                    val failed = mutableListOf<String>()
                    var skipped = 0
                    var transcriptTaken = false
                    // ⚠️ لقطة **قبل** الحلقة: الفحص على الطابور الحيّ كان
                    // يعدّ ملفّاً أدرجته الحلقة نفسها «مكرّراً»، فملفّان
                    // باسم واحد من مجلّدين مختلفين يسقط ثانيهما صامتاً.
                    // الحارس لضغطة ثانية بعد تدوير الشاشة، لا لدفعة واحدة.
                    val before = UploadQueue.items.value
                    plan.forEachIndexed { index, (file, lessonTitle) ->
                        val duplicate = before.any { item ->
                            item.fileName == file.name && item.title == lessonTitle &&
                                System.currentTimeMillis() - item.queuedAtMs < DUPLICATE_WINDOW_MS
                        }
                        if (duplicate) {
                            skipped++
                        } else {
                            runCatching {
                                UploadQueue.enqueue(
                                    context = context,
                                    sourceUri = file.uri,
                                    fileName = file.name,
                                    title = lessonTitle,
                                    categoryId = catId,
                                    subcategoryId = subId,
                                    sectionLabel = label,
                                    featured = featuredSnapshot,
                                    featuredUntilMs = featuredUntilSnapshot,
                                    addedBy = addedBy,
                                    categoryName = catLabel,
                                    subcategoryName = subLabel,
                                    // «النص المشروح» نصّ مقطع بعينه من كتاب:
                                    // نسخُه على عشرين درساً كذب، فيُرفق بأوّل
                                    // درس دخل فعلاً وحده (وورقة المراجعة تقوله).
                                    transcriptText = if (transcriptTaken) {
                                        ""
                                    } else {
                                        transcriptSnapshot
                                    },
                                    transcriptBookTitle = if (transcriptTaken) {
                                        ""
                                    } else {
                                        transcriptBookSnapshot
                                    },
                                    transcriptSourceRef = if (transcriptTaken) {
                                        ""
                                    } else {
                                        transcriptRefSnapshot
                                    },
                                    transcriptImages = if (transcriptTaken) {
                                        emptyList()
                                    } else {
                                        transcriptImagesSnapshot
                                    },
                                    // الدفعة تُعلَن مرّة واحدة في نهايتها.
                                    announce = false,
                                    // ⚡ وتُكتب المدوّنة **مرّة واحدة** بعد
                                    // الحلقة: كتابةٌ لكلّ ملفّ كانت O(ن²) على
                                    // مدوّنة تضمّ نصوص «النص المشروح».
                                    persistNow = false,
                                )
                            }.onSuccess {
                                inserted += it
                                transcriptTaken = true
                            }.onFailure {
                                // ملفّ تعذّرت قراءته لا يُسقط الدفعة كلّها:
                                // يُسمَّى للمشرف في النهاية وتمضي البقيّة.
                                failed += file.name
                            }
                        }
                        UploadQueue.updateBatchProgress(index + 1)
                    }
                    if (inserted.isNotEmpty()) {
                        // كتابة واحدة **قبل** الإعلان: الموقع «س من ص» يُقرأ
                        // من الطابور المحفوظ فلا يصحّ إلّا بعدها.
                        UploadQueue.persistBatch(inserted)
                        UploadQueue.markBatchEnqueued(inserted)
                        // إيقاظ **واحد** بعد آخر إدراج: `kick` لا يقاطع رفعاً
                        // جارياً، والدور محفوظ في `seq` لا في سلسلة WorkManager.
                        LessonUploadWorker.kick(context)
                    }
                    Triple(inserted.size, skipped, failed.toList())
                }
                val (added, skipped, failed) = result

                // إفراغ النموذج: النيّة نفسها تُنسى كي يُسأل عنها من جديد.
                files.clear()
                batchTitles.clear()
                multiMode = ""
                title = ""
                featured = false
                featuredUntil = null
                featuredLabel = ""
                transcriptText = ""
                transcriptBookTitle = ""
                transcriptSourceRef = ""
                transcriptImages.clear()
                transcriptOpen = false
                queuing = false
                draftRestored = false
                withContext(Dispatchers.IO) { AppPrefs.addLessonDraft = null }
                message = buildString {
                    when {
                        added > 0 -> {
                            append("أُضيف ${lessonsCountLabel(added)} إلى طابور الرفع ")
                            append("بالترتيب المعروض — تُرفع واحداً تلو الآخر، ")
                            append("والأوّل يظهر أوّلاً في التطبيق. ")
                            append("تستطيع إغلاق الشاشة، ويصلك إشعار عند اكتمال كلّ درس.")
                        }
                        // الحالة الغالبة لـ«لم يُضف شيء»: ضغطة ثانية بعد تدوير
                        // الشاشة والدفعة دخلت فعلاً — نصّ «لم يُضف شيء» وحده
                        // كان سيُفهم فشلاً فيعيد المحاولة بلا داعٍ.
                        skipped > 0 -> append(
                            "كلّها في طابور الرفع فعلاً منذ أقلّ من دقيقة — " +
                                "لم يُضف شيء مكرّر.",
                        )
                        else -> append("لم يُضف شيء.")
                    }
                    if (added > 0 && skipped > 0) {
                        append(" (تُخُطّي $skipped مكرّراً في الطابور)")
                    }
                    if (failed.isNotEmpty()) {
                        append(" ⚠️ تعذّرت قراءة: ${failed.joinToString("، ")}")
                    }
                }
                // «كلّها مكرّرة» رسالة اطمئنان مقصودة (انظر أعلاه) — تلوينها
                // أحمر كان يناقض نصّها فيُفهم فشلاً ويُعاد الرفع بلا داعٍ.
                isError = failed.isNotEmpty() || (added == 0 && skipped == 0)
                if (added > 0) snack("أُضيف ${lessonsCountLabel(added)} إلى طابور الرفع")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // الإدراج تمّ داخل NonCancellable — لا رسالة خطأ لمغادرة الشاشة.
                throw cancel
            } catch (e: Exception) {
                queuing = false
                message = "تعذّر تجهيز الدروس: ${e.message ?: e}"
                isError = true
            } finally {
                // 🔓 فكّ الحجز في كلّ المخارج — ومنها إلغاء الكوروتين بمغادرة
                // الشاشة: `finally` يعمل عند الإلغاء أيضاً، وبعد أن يكون
                // `NonCancellable` قد أتمّ عمله. بلاه يبقى الطابور محجوزاً
                // إلى إعادة تشغيل التطبيق فلا تُقبل دفعة أخرى أبداً.
                UploadQueue.endBatch()
            }
        }
    }

    if (askMultiMode) {
        MultiFilesIntentDialog(
            count = files.size,
            mergeLimit = AudioMerger.maxFiles,
            onMerge = {
                multiMode = MODE_MERGE
                askMultiMode = false
            },
            onSeparate = {
                multiMode = MODE_SEPARATE
                askMultiMode = false
            },
            onDismiss = {
                askMultiMode = false
                // إغلاق بلا اختيار لا يترك النموذج معلّقاً: الدمج هو السلوك
                // القائم، وفوق سقفه لا يبقى إلّا الفصل — و«تغيير» بجانبهما.
                if (multiMode.isEmpty()) {
                    multiMode = if (files.size > AudioMerger.maxFiles) {
                        MODE_SEPARATE
                    } else {
                        MODE_MERGE
                    }
                }
            },
        )
    }

    if (showBatchSheet) {
        BatchUploadSheet(
            titles = files.indices.map { batchTitleOf(it) },
            fileNames = files.map { it.name },
            sectionLabel = listOfNotNull(
                categories.firstOrNull { it.id == categoryId }?.name,
                subsForCategory.firstOrNull { it.id == subcategoryId }?.name,
            ).joinToString(" ← "),
            notes = buildList {
                if (featured) {
                    add("⭐ التمييز سيُطبَّق على الدروس كلّها.")
                }
                if (transcriptText.isNotBlank() || transcriptImages.isNotEmpty()) {
                    add("📖 «النص المشروح» يخصّ مقطعاً بعينه — يُرفق بالدرس الأوّل وحده.")
                }
            },
            onRename = { index, newTitle ->
                files.getOrNull(index)?.let { batchTitles[it.uri.toString()] = newTitle }
            },
            onConfirm = {
                showBatchSheet = false
                queueSeparateLessons()
            },
            onDismiss = { showBatchSheet = false },
        )
    }

    if (showFeatureSheet) {
        FeatureDurationSheet(
            lessonTitle = title.ifBlank { "الدرس الجديد" },
            currentUntilMs = featuredUntil,
            onDismiss = {
                showFeatureSheet = false
                if (!featured) featuredLabel = ""
            },
            onPick = { duration ->
                showFeatureSheet = false
                featured = true
                featuredUntil = duration.untilMs()
                featuredLabel = duration.label
            },
        )
    }

    if (showRecorder) {
        RecordSheet(
            onDismiss = { showRecorder = false },
            onRecorded = { file, name ->
                showRecorder = false
                // التسجيل (m4a) صار يقبل الدمج كأي صيغة أخرى: يُلحق بالقائمة
                // بدل أن يمحوها — سجّل مقاطع متتابعة أو اخلطها بملفات مختارة.
                if (files.size >= addLimit) {
                    message = "الحد الأقصى $addLimit ملفات — لم يُضف التسجيل."
                    isError = true
                } else {
                    files.add(
                        PickedFile(
                            uri = android.net.Uri.fromFile(file),
                            name = name,
                            size = file.length(),
                        ),
                    )
                    message = ""
                    isError = false
                    askIntentIfNeeded()
                }
            },
        )
    }

    AdminScaffold(title = "إضافة درس صوتي", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            // إرشاد ثابت وموجز: يزيل سؤال «هل أنتظر انتهاء الرفع؟».
            item { QueueHintCard(Modifier.padding(bottom = 8.dp)) }
            // مؤشّر حيّ لما يُرفع الآن وما ينتظر الدور.
            item { UploadQueueBanner(Modifier.padding(bottom = 8.dp)) }
            // 📝 شريط صغير عند استعادة مسودة: يطمئن المشرف أنّ ما كتبه لم
            // يضع، و«تفريغ» يمحوها مع حقولها إن أراد البدء من جديد.
            if (draftRestored) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(
                                adminGold.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "استُعيدت مسودة غير مكتملة",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                title = ""
                                categoryId = null
                                subcategoryId = null
                                transcriptText = ""
                                transcriptBookTitle = ""
                                transcriptSourceRef = ""
                                transcriptOpen = false
                                draftRestored = false
                                scope.launch(Dispatchers.IO) { AppPrefs.addLessonDraft = null }
                            },
                            enabled = !busy,
                        ) {
                            Text("تفريغ", fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                AdminTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "عنوان الدرس الصوتي",
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch("audio/*") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (files.isEmpty()) Icons.Filled.AudioFile else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (files.isEmpty()) {
                                "اختر ملفاً (أو مجلّداً كاملاً)"
                            } else {
                                "إضافة ملفات (${files.size}/$addLimit)"
                            },
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { showRecorder = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(6.dp))
                        Text("تسجيل مباشر", overflow = TextOverflow.Ellipsis, maxLines = 1)
                    }
                }
                // نيّة الدفعة معروضة دائماً بلا سؤال متكرّر: سطر واحد يقول ما
                // سيقع، و«تغيير» يعيد السؤال — الترتيب أدناه هو ترتيب الرفع.
                if (files.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (separate) {
                                "كلّ ملفّ درس مستقلّ: ${filesCountLabel(files.size)} " +
                                    "بالترتيب أدناه (وهو ترتيب ظهورها في " +
                                    "التطبيق) — والعناوين من أسماء الملفّات."
                            } else {
                                "ستُدمج ${filesCountLabel(files.size)} بالترتيب أدناه في درس واحد " +
                                    "متصل — استعمل الأسهم لإعادة الترتيب."
                            },
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { askMultiMode = true }, enabled = !busy) {
                            Text("تغيير", fontSize = 13.sp)
                        }
                    }
                }
            }

            itemsIndexed(files) { index, file ->
                FileRow(
                    index = index,
                    name = file.name,
                    canMoveUp = index > 0,
                    canMoveDown = index < files.size - 1,
                    enabled = !busy,
                    showReorder = files.size > 1,
                    onUp = {
                        val item = files.removeAt(index)
                        files.add(index - 1, item)
                    },
                    onDown = {
                        val item = files.removeAt(index)
                        files.add(index + 1, item)
                    },
                    onRemove = { files.removeAt(index) },
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                // فشل الجلب يُقال صراحةً مع زرّ إعادة — لا قائمتان فارغتان
                // صامتتان يتّهم المشرف نفسه أمامهما.
                if (sectionsFailed && categories.isEmpty()) {
                    Text(
                        "تعذّر تحميل الأقسام — تحقّق من الإنترنت.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { sectionsRetry++ },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("إعادة تحميل الأقسام")
                    }
                    Spacer(Modifier.height(10.dp))
                }
                AdminDropdown(
                    label = "القسم الرئيسي",
                    items = categories,
                    selected = categories.firstOrNull { it.id == categoryId },
                    itemLabel = { it.name },
                    enabled = !busy,
                    onSelected = {
                        categoryId = it.id
                        subcategoryId = null
                    },
                )
                if (categoryId != null) {
                    Spacer(Modifier.height(14.dp))
                    AdminDropdown(
                        label = "القسم الفرعي",
                        items = subsForCategory,
                        selected = subsForCategory.firstOrNull { it.id == subcategoryId },
                        itemLabel = { it.name },
                        enabled = !busy,
                        onSelected = { subcategoryId = it.id },
                    )
                    if (subsForCategory.isEmpty()) {
                        Text(
                            "لا توجد أقسام فرعية لهذا القسم — أنشئ واحداً أولاً.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = featured,
                        onCheckedChange = {
                            if (busy) return@Checkbox
                            if (it) {
                                // التمييز يلزمه مدّة — لا يُترك دائماً بالصدفة.
                                showFeatureSheet = true
                            } else {
                                featured = false
                                featuredUntil = null
                                featuredLabel = ""
                            }
                        },
                        enabled = !busy,
                    )
                    Icon(Icons.Filled.Star, contentDescription = null, tint = adminGold)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("تمييز الدرس (مختارات المنبر)")
                        if (featured) {
                            Text(
                                featuredLabel,
                                fontSize = 11.sp,
                                color = adminGold,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 📖 «النص المشروح» الاختياري — يُنشر مع الدرس فور اكتمال رفعه.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(12.dp)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { transcriptOpen = !transcriptOpen },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "النص المشروح (اختياري)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    "نص المقطع من الكتاب أو صور صفحاته — يظهر في " +
                                        "شاشة تشغيل الدرس.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (transcriptOpen) {
                                    Icons.Filled.ArrowUpward
                                } else {
                                    Icons.Filled.ArrowDownward
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (transcriptOpen) {
                            Spacer(Modifier.height(10.dp))
                            AdminTextField(
                                value = transcriptBookTitle,
                                onValueChange = { if (it.length <= 200) transcriptBookTitle = it },
                                label = "اسم الكتاب/المتن (اختياري)",
                                enabled = !busy,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTextField(
                                value = transcriptSourceRef,
                                onValueChange = { if (it.length <= 300) transcriptSourceRef = it },
                                label = "المقطع (من … إلى …) — اختياري",
                                enabled = !busy,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTextField(
                                value = transcriptText,
                                onValueChange = { if (it.length <= 20000) transcriptText = it },
                                label = "النص المشروح",
                                singleLine = false,
                                minLines = 4,
                                maxLines = 10,
                                enabled = !busy,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTranscriptImagesEditor(
                                images = transcriptImages,
                                enabled = !busy,
                                onError = { errorText ->
                                    message = errorText
                                    isError = true
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (merging) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "جارٍ دمج الملفات في مقطع واحد…",
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // نسخ عشرين ملفّاً إلى تخزين التطبيق يستغرق لحظات — شريط
                // محدَّد لأنّ العدد معلوم، ونصّ يقول إنّ المغادرة لا تُبطله.
                // ⚠️ من الحالة المشتركة: تدوير الشاشة كان يُخفيه والدفعة ماضية.
                val batchProgress = batchState.value
                if (batchProgress != null) {
                    LinearProgressIndicator(
                        progress = {
                            if (batchProgress.total <= 0) {
                                0f
                            } else {
                                batchProgress.prepared / batchProgress.total.toFloat()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "جارٍ إدراج الدروس بالترتيب — يكمل حتى لو غادرت الشاشة.",
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (message.isNotEmpty()) {
                    Text(
                        message,
                        color = if (isError) MaterialTheme.colorScheme.error else adminGreen,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                Button(
                    onClick = {
                        if (separate) {
                            // ورقة المراجعة أوّلاً: عشرون درساً لا تُرفع بنقرة
                            // عمياء — يرى ترتيبها وعناوينها قبل أن تمضي.
                            val missing = firstMissing()
                            if (missing != null) {
                                message = missing
                                isError = true
                            } else {
                                showBatchSheet = true
                            }
                        } else {
                            queueLesson()
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            batchProgress != null ->
                                "جارٍ التجهيز… ${batchProgress.prepared} من ${batchProgress.total}"
                            busy -> "جارٍ التجهيز…"
                            separate -> "مراجعة ورفع ${lessonsCountLabel(files.size)}"
                            else -> "رفع الدرس الصوتي"
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 💡 بطاقة إرشاد ثابتة أعلى الشاشة — تجيب صراحةً عمّا يُربك المشرف:
 * لا انتظار بين درس وآخر، والرفع لا يتوقّف بإغلاق الشاشة، والإشعار يخبره.
 */
@Composable
private fun QueueHintCard(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "أضف بلا انتظار",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "• أضف درساً جديداً حتى أثناء رفع الدرس السابق — الطابور " +
                    "يرفعها واحداً تلو الآخر بالترتيب.\n" +
                    "• الرفع يستمرّ في الخلفية ولو أغلقت هذه الشاشة أو التطبيق.\n" +
                    "• يصلك إشعار فور اكتمال رفع كلّ درس.",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileRow(
    index: Int,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    showReorder: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "إزالة",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showReorder) {
            IconButton(onClick = onUp, enabled = enabled && canMoveUp) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "أعلى",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDown, enabled = enabled && canMoveDown) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = "أسفل",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
