package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ↩️ «تراجع بعد الفعل» بدل «تأكيد قبله».
 *
 * لماذا: كلّ حذف في اللوحة كان يمرّ بحوار تأكيد. وحين يُسأل المشرف عن كلّ
 * شيء يكفّ عن قراءة السؤال ويضغط «نعم» بالعادة — فيصير الحوار حمايةً
 * شكليّة لا تمنع خطأً، ويؤخّر العمل السليم في كلّ مرّة. فالأفعال الرخيصة
 * القابلة للرجوع تُنفَّذ **فوراً** ويُعطى المشرف عشر ثوانٍ ليتراجع.
 *
 * ولا يُنشَأ شيء من جديد عند التراجع: الفعل الفعليّ (الكتابة في القاعدة)
 * **يؤجَّل** حتى تنقضي مهلة التراجع، فالتراجع يعيد العنصر إلى موضعه وحالته
 * بالضبط لأنّه لم يُمَسّ أصلاً.
 */

/** مهلة التراجع: عشر ثوانٍ — نفس مدّة [SnackbarDuration.Long]. */
private const val UNDO_WINDOW_MS = 10_000L

/**
 * نطاق مستقلّ عن التركيب: لو خرج المشرف من الشاشة قبل انقضاء المهلة وجب
 * أن يقع الفعل المؤجَّل مع ذلك — وإلّا بقي المحذوف في القاعدة بصمت.
 * والمهلة الصريحة تضمن انتهاء الانتظار حتى لو زال الشريط عن الشاشة.
 */
private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** مُظهِر شريط «تراجع» لشاشة واحدة. */
class UndoBarController(val host: SnackbarHostState) {
    /**
     * [onUndo] يُستدعى إن ضغط المشرف «تراجع» (إعادة الحالة كما كانت)،
     * و[onCommit] يُستدعى بعد انقضاء المهلة (تنفيذ الفعل فعلاً).
     */
    fun show(message: String, onUndo: () -> Unit, onCommit: () -> Unit = {}) {
        undoScope.launch {
            val result = withTimeoutOrNull(UNDO_WINDOW_MS + 500) {
                host.showSnackbar(
                    message = message,
                    actionLabel = "تراجع",
                    duration = SnackbarDuration.Long,
                )
            }
            if (result == SnackbarResult.ActionPerformed) onUndo() else onCommit()
        }
    }
}

/**
 * يُتاح للأجزاء الداخليّة التي لا تملك شاشةً خاصّة بها (كمحرّر الصور المُدرَج
 * داخل نموذج)؛ `null` يعني «لا شريط هنا» فيسقط المستدعي إلى رسالة عاديّة.
 */
val LocalUndoBar = compositionLocalOf<UndoBarController?> { null }

/** يُنشئ متحكّم الشريط لهذه الشاشة (ضع معه [UndoBarOverlay]). */
@Composable
fun rememberUndoBar(): UndoBarController {
    val host = remember { SnackbarHostState() }
    return remember(host) { UndoBarController(host) }
}

/**
 * شريط التراجع أسفل الشاشة. يوضع **فوق** محتوى الشاشة داخل [Box] كي يظهر
 * فوق القوائم والأزرار العائمة، ويُتيح الشريط لما بداخله عبر [LocalUndoBar].
 */
@Composable
fun UndoBarOverlay(controller: UndoBarController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUndoBar provides controller) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(controller.host, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
