package io.github.kgcaudit.reader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderScreen
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CpTheme { OloApp(::hideSystemBars) } }
    }

    /** 책을 읽는 동안에는 시스템 바를 숨긴다. 메뉴를 열면 다시 보인다. */
    private fun hideSystemBars(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * 화면 두 개(라이브러리 · 리더)뿐이라 내비게이션 라이브러리 없이 상태 하나로 오간다.
 *
 * 열던 책의 id 는 [rememberSaveable] 로 남긴다. 시스템이 백그라운드에서 앱을 죽였다가
 * 되살리면 라이브러리가 아니라 읽던 책으로 돌아와야 한다.
 */
@Composable
private fun OloApp(hideSystemBars: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<BookReader?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(container.prefs.load()) }

    // 되살아났을 때 열던 책을 다시 연다.
    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        if (reader != null) return@LaunchedEffect
        val book = container.data.library.find(id)
        if (book == null) { openId = null; return@LaunchedEffect }
        runCatching { container.open(book) }
            .onSuccess { reader = it }
            .onFailure {
                android.util.Log.w("OloApp", "cannot open ${book.displayName}", it)
                failure = describeOpenFailure(it, book.format)
                openId = null
            }
    }

    fun close() {
        reader?.close()
        reader = null
        openId = null
        hideSystemBars(false)
    }

    val current = reader
    if (current == null) {
        LibraryScreen(onOpen = { book -> openId = book.id.value })
    } else {
        DisposableEffect(current) {
            hideSystemBars(true)
            onDispose { }
        }
        ReaderScreen(
            reader = current,
            prefs = prefs,
            onPrefsChange = { prefs = it; container.prefs.save(it) },
            onClose = ::close,
            onChrome = { showing -> hideSystemBars(!showing) },
        )
    }

    failure?.let { message ->
        CpPopup(title = "이 책을 열지 못했습니다", message = message, onDismiss = { failure = null }) {
            Spacer(Modifier.height(16.dp))
            CpButton("확인", { failure = null })
        }
    }
    if (openId != null && reader == null && failure == null) {
        Box(Modifier.fillMaxSize().background(CpTheme.colors.background)) { CpPopup(title = "책을 여는 중…") }
    }
}
