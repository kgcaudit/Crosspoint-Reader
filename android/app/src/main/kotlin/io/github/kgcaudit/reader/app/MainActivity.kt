package io.github.kgcaudit.reader.app

import android.content.Intent
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
import androidx.compose.runtime.MutableState
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
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderScreen
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 다른 앱이 "연결 프로그램" 으로 보낸 인텐트. 화면이 처리하면 null 로 되돌린다. */
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 되살아난 경우(savedInstanceState 있음)에는 같은 인텐트를 다시 처리하지 않는다. 읽던
        // 책은 rememberSaveable 이 되돌린다 — 처리하면 읽던 자리 대신 처음부터 다시 연다.
        if (savedInstanceState == null) incoming.value = intent
        setContent { CpTheme { OloApp(incoming, hideSystemBars = ::hideSystemBars, leave = ::leaveToCaller) } }
    }

    /** 다른 앱에서 연 책을 닫았다. 그 앱으로 돌아간다(이 앱은 뒤로 물러날 뿐 끝나지 않는다). */
    internal fun leaveToCaller() {
        leftToCaller++
        moveTaskToBack(true)
    }

    /** [leaveToCaller] 가 불린 횟수. Robolectric 은 작업이 뒤로 갔는지 알려 주지 않아 테스트가 이걸 본다. */
    @androidx.annotation.VisibleForTesting
    internal var leftToCaller = 0
        private set

    /**
     * 앱이 이미 떠 있을 때 온 파일(launchMode=singleTask). 새 창을 쌓지 않고 이 창에서 연다 —
     * 쌓으면 뒤로 가기를 누를 때마다 전에 연 책들이 차례로 나온다.
     */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = intent
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
private fun OloApp(incoming: MutableState<Intent?>, hideSystemBars: (Boolean) -> Unit, leave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<BookReader?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(container.prefs.load()) }
    // 다른 앱이 넘긴 파일의 URI. 라이브러리 id 와 따로 두는 이유: 라이브러리에 없는 파일이라
    // library.find 로 되찾을 수 없다.
    var incomingUri by rememberSaveable { mutableStateOf<String?>(null) }
    // 받을 때 알아낸 형식. URI 만으로는 다시 알 수 없다 — 확장자 없는 파일은 보낸 앱이 준
    // MIME 으로만 알 수 있는데, 그건 인텐트에만 있다.
    var incomingFormat by rememberSaveable { mutableStateOf<String?>(null) }
    // 다른 앱에서 열었으면 닫을 때 그 앱으로 돌아간다. 라이브러리가 나오면 "파일을 봤을 뿐인데
    // 왜 다른 앱이 떠 있나" 가 된다.
    var fromOutside by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(incoming.value) {
        val intent = incoming.value ?: return@LaunchedEffect
        incoming.value = null
        when (val request = Incoming.from(intent, context.contentResolver)) {
            null -> Unit
            is Incoming.Refused -> failure = request.message
            is Incoming.Book -> {
                // 읽던 책이 있으면 닫고 새 파일을 연다(진도는 넘길 때마다 저장돼 있다).
                reader?.close()
                reader = null
                openId = null
                failure = null
                fromOutside = true
                incomingFormat = request.file.format.name
                incomingUri = request.file.uri.toString()
            }
        }
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (reader != null) return@LaunchedEffect
        val format = BookFormat.entries.firstOrNull { it.name == incomingFormat }
        if (format == null) { incomingUri = null; return@LaunchedEffect }
        val parsed = android.net.Uri.parse(uri)
        val (name, size) = Incoming.describe(parsed, context.contentResolver)
        val file = IncomingFile(parsed, name, size, format)
        runCatching { container.openIncoming(file) }
            .onSuccess { reader = it }
            .onFailure {
                // 화면이 다시 만들어지며 취소된 것은 실패가 아니다. 실패로 다루면 incomingUri 를 지워
                // 되살아난 화면이 책을 다시 열지 못한다.
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.w("OloApp", "cannot open incoming ${file.displayName}", it)
                failure = describeOpenFailure(it, file.format)
                incomingUri = null
            }
    }

    // 되살아났을 때 열던 책을 다시 연다.
    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        if (reader != null) return@LaunchedEffect
        val book = container.data.library.find(id)
        if (book == null) { openId = null; return@LaunchedEffect }
        runCatching { container.open(book) }
            .onSuccess { reader = it }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                android.util.Log.w("OloApp", "cannot open ${book.displayName}", it)
                failure = describeOpenFailure(it, book.format)
                openId = null
            }
    }

    fun close() {
        reader?.close()
        reader = null
        openId = null
        incomingUri = null
        hideSystemBars(false)
        if (fromOutside) {
            fromOutside = false
            leave()
        }
    }

    val current = reader
    if (current == null) {
        LibraryScreen(onOpen = { book -> fromOutside = false; openId = book.id.value })
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
    if ((openId != null || incomingUri != null) && reader == null && failure == null) {
        Box(Modifier.fillMaxSize().background(CpTheme.colors.background)) { CpPopup(title = "책을 여는 중…") }
    }
}
