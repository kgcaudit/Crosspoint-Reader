package io.github.kgcaudit.reader.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.pdf.PdfScreen
import io.github.kgcaudit.reader.reflow.ReaderScreen
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpReaderTheme
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.LocalVolumeKeys
import io.github.kgcaudit.reader.ui.design.VolumeKeyRouter
import io.github.kgcaudit.reader.ui.design.ScreenRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** 다른 앱이 "연결 프로그램" 으로 보낸 인텐트. 화면이 처리하면 null 로 되돌린다. */
    private val incoming = mutableStateOf<Intent?>(null)

    /** 음량 단추 → 리더(설정에서 켰을 때만). */
    private val volumeKeys = VolumeKeyRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 되살아난 경우(savedInstanceState 있음)에는 같은 인텐트를 다시 처리하지 않는다. 읽던
        // 책은 rememberSaveable 이 되돌린다 — 처리하면 읽던 자리 대신 처음부터 다시 연다.
        if (savedInstanceState == null) incoming.value = intent
        setContent {
            CompositionLocalProvider(LocalVolumeKeys provides volumeKeys) {
                CpTheme { OloApp(incoming, hideSystemBars = ::hideSystemBars, leave = ::leaveToCaller, rotate = ::applyRotation) }
            }
        }
    }

    /**
     * 리더가 볼륨키 넘김을 켜 두었으면 음량 단추를 가져간다. 화면(컴포즈)이 먹지 않은 키가 여기로 온다 — 지면에는
     * 초점을 가진 곳이 없어 컴포즈의 키 처리로는 받을 수 없다. 떼는 것도 먹어야 시스템 음량 판이 뜨지 않는다.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        volumeKeys.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        volumeKeys.dispatch(event) || super.onKeyUp(keyCode, event)

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

    /**
     * 화면 방향을 창에 건다. 자동은 FULL_SENSOR — 휴대폰의 회전 잠금과 상관없이 네 방향 모두 돈다
     * (기본값 UNSPECIFIED 는 잠금을 따라서, 잠금을 켠 사람에게는 앱이 돌지 않았다). 고정도 SENSOR_ 판을
     * 써서 뒤집어 든 경우(충전선이 위)는 따라 돈다 — 거꾸로 선 글자는 읽을 수 없다.
     */
    private fun applyRotation(rotation: ScreenRotation) {
        requestedOrientation = when (rotation) {
            ScreenRotation.Auto -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            ScreenRotation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ScreenRotation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
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
private fun OloApp(
    incoming: MutableState<Intent?>,
    hideSystemBars: (Boolean) -> Unit,
    leave: () -> Unit,
    rotate: (ScreenRotation) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var reader by remember { mutableStateOf<OpenedBook?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(container.prefs.load()) }
    val charSpeed = remember { container.prefs.loadSpeed("chars") }
    val pageSpeed = remember { container.prefs.loadSpeed("pages") }
    // 라이브러리에서도 같은 방향이다 — 책을 닫을 때마다 방향이 튀지 않게.
    LaunchedEffect(prefs.screen.rotation) { rotate(prefs.screen.rotation) }
    // 이번 실행에서 폴더를 훑었는가. 화면(액티비티)이 새로 만들어지면 다시 훑는다.
    var scanned by remember { mutableStateOf(false) }
    // 다른 앱이 넘긴 파일의 URI. 라이브러리 id 와 따로 두는 이유: 라이브러리에 없는 파일이라
    // library.find 로 되찾을 수 없다.
    var incomingUri by rememberSaveable { mutableStateOf<String?>(null) }
    // 받을 때 알아낸 형식. URI 만으로는 다시 알 수 없다 — 확장자 없는 파일은 보낸 앱이 준
    // MIME 으로만 알 수 있는데, 그건 인텐트에만 있다.
    var incomingFormat by rememberSaveable { mutableStateOf<String?>(null) }
    // 받을 때 읽은 이름·크기. 다시 열 때(되살아남) 제공자에게 또 묻지 않는다 — 묻는 것 자체가 느릴 수 있다.
    var incomingName by rememberSaveable { mutableStateOf<String?>(null) }
    var incomingSize by rememberSaveable { mutableStateOf(-1L) }
    // 받은 횟수. 같은 파일을 두 번 받으면 URI 가 같아 여는 효과가 다시 돌지 않는다 — 읽던 책은 닫혔는데
    // 새로 열지 않아 "책을 여는 중…" 에 멈춘다. 받을 때마다 올려 효과를 다시 돌린다.
    var incomingRequest by rememberSaveable { mutableStateOf(0) }
    // 다른 앱에서 열었으면 닫을 때 그 앱으로 돌아간다. 라이브러리가 나오면 "파일을 봤을 뿐인데
    // 왜 다른 앱이 떠 있나" 가 된다.
    var fromOutside by rememberSaveable { mutableStateOf(false) }

    /**
     * 듣기가 아직 읽고 있는 책을 이 화면에 되찾는다. 되찾았으면 true. 알림을 누르든 앱 아이콘을 누르든 새 화면은
     * 여기를 지난다 — 어느 쪽이든 듣고 있는 책이 나와야 한다.
     */
    fun adoptListened(): Boolean {
        val held = container.held?.takeIf { it.listened() } ?: return false
        reader = held.book
        openId = held.openId
        incomingUri = held.incomingUri
        incomingFormat = held.incomingFormat
        incomingName = held.incomingName
        incomingSize = held.incomingSize
        // 알림으로 돌아온 책을 닫으면 라이브러리로 간다. 보낸 앱은 이미 앞에 없다.
        fromOutside = false
        return true
    }
    // 화면이 새로 만들어졌다. 듣기가 읽던 책이 있으면 같은 리더를 되찾는다 — 저장된 id 로 다시 열면 듣기와 다른
    // 리더라 조종판이 붙지 않고, 저장된 것이 없으면(밀어 닫음) 라이브러리가 뜬다. 듣지 않던 책은 닫는다: 저장된
    // id 로 다시 열 것이고, 두면 파일을 쥔 채 남는다.
    // 값(되찾았는가)을 돌려주는 것은 lint 때문이다 — Unit 을 돌려주는 remember 는 오류로 막힌다. 한 화면에 한 번만 돌아야
    // 해서 SideEffect(그릴 때마다) 로는 안 된다.
    remember { adoptListened().also { if (!it) container.dropHeld() } }
    // 지금 열린 책을 화면 밖에 남긴다. 화면이 사라져도 듣기는 이 책을 계속 읽는다.
    LaunchedEffect(reader) {
        container.held = reader?.let { HeldBook(it, openId, incomingUri, incomingFormat, incomingName, incomingSize) }
    }

    LaunchedEffect(incoming.value) {
        val intent = incoming.value ?: return@LaunchedEffect
        // 제공자에게 묻는 일(이름·크기)은 입출력이다. 클라우드·메일 첨부는 여기서 파일을 받기도 한다 —
        // 메인 스레드에서 하면 앱이 멈춘다.
        val request = withContext(Dispatchers.IO) { Incoming.from(intent, context.contentResolver) }
        // 다 읽은 **뒤에** 비운다. 먼저 비우면 이 효과의 열쇠가 바뀌어 효과 자신이 취소된다 — 묻는 동안
        // 기다리던 파일이 열리지 않는다. 그사이 새 파일이 오면 이 효과는 취소되고 새 파일을 연다(맞다).
        incoming.value = null
        when (request) {
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
                incomingName = request.file.displayName
                incomingSize = request.file.sizeBytes ?: -1L
                incomingUri = request.file.uri.toString()
                incomingRequest++
            }
        }
    }

    LaunchedEffect(incomingUri, incomingRequest) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (reader != null) return@LaunchedEffect
        val format = BookFormat.entries.firstOrNull { it.name == incomingFormat }
        if (format == null) { incomingUri = null; return@LaunchedEffect }
        val parsed = android.net.Uri.parse(uri)
        val name = incomingName ?: withContext(Dispatchers.IO) { Incoming.describe(parsed, context.contentResolver).first }
        val file = IncomingFile(parsed, name, incomingSize.takeIf { it >= 0 }, format)
        runCatching { openKeepingResult { container.openIncoming(file) } }
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
        val book = container.data.library.get(BookId(id))
        if (book == null) { openId = null; return@LaunchedEffect }
        runCatching { openKeepingResult { container.open(book) } }
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

    when (val current = reader) {
        null -> LibraryScreen(
            onOpen = { book -> fromOutside = false; openId = book.id.value },
            scanOnStart = !scanned,
            onStartScan = { scanned = true },
        )
        is OpenedBook.Reflow -> {
            LaunchedEffect(current) { hideSystemBars(true) }
            CpReaderTheme(prefs.screen.theme) {
                ReaderScreen(
                    reader = current.reader,
                    prefs = prefs,
                    onPrefsChange = { prefs = it; container.prefs.save(it) },
                    onClose = ::close,
                    onChrome = { showing -> hideSystemBars(!showing) },
                    speed = charSpeed,
                    onSpeedChange = { container.prefs.saveSpeed("chars", it) },
                    listenKit = container.listenKit,
                )
            }
        }
        is OpenedBook.Pdf -> {
            LaunchedEffect(current) { hideSystemBars(true) }
            // 배경 · 밝기 · 터치 영역 … 은 EPUB 과 한 벌이다. PDF 에서 고른 배경이 EPUB 을 열 때 풀리면 안 된다.
            CpReaderTheme(prefs.screen.theme) {
                PdfScreen(
                    reader = current.reader,
                    onClose = ::close,
                    onChrome = { showing -> hideSystemBars(!showing) },
                    prefs = prefs.screen,
                    onPrefsChange = { prefs = prefs.copy(screen = it); container.prefs.save(prefs) },
                    speed = pageSpeed,
                    onSpeedChange = { container.prefs.saveSpeed("pages", it) },
                    listen = prefs.listen,
                    onListenChange = { prefs = prefs.copy(listen = it); container.prefs.save(prefs) },
                    listenKit = container.listenKit,
                )
            }
        }
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

/**
 * 책을 열되, 여는 도중 화면이 떠나(취소) 결과를 받을 사람이 없으면 **연 것을 닫는다.**
 *
 * `withContext` 는 끝난 뒤 돌아오는 순간 취소돼 있으면 결과를 버린다. 그러면 열어 둔 파일 디스크립터와
 * 캐시 사본이 아무도 닫지 않은 채 남는다(책을 여는 중에 다른 앱이 파일을 또 보낸 경우).
 */
private suspend fun openKeepingResult(open: suspend () -> OpenedBook): OpenedBook {
    val opened = withContext(kotlinx.coroutines.NonCancellable) { open() }
    if (!kotlin.coroutines.coroutineContext.isActive) {
        opened.close()
        throw kotlinx.coroutines.CancellationException("screen left while opening")
    }
    return opened
}
