package io.github.kgcaudit.reader.app

import android.app.Application
import android.content.Context
import android.net.Uri
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.book.BookFontTable
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.pdf.PdfBook
import io.github.kgcaudit.reader.pdf.PdfReader
import io.github.kgcaudit.reader.pdf.PdfSource
import io.github.kgcaudit.reader.pdf.PlatformPdfSource
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.reflow.ListenPrefs
import io.github.kgcaudit.reader.reflow.listen.ListenKit
import io.github.kgcaudit.reader.ui.design.AutoTurn
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.text.UserFonts
import io.github.kgcaudit.reader.ui.design.Footer
import io.github.kgcaudit.reader.ui.design.FooterItem
import io.github.kgcaudit.reader.ui.design.KeepScreenOn
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.PaperTheme
import io.github.kgcaudit.reader.ui.design.ReadingSpeed
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.ScreenRotation
import io.github.kgcaudit.reader.ui.design.TouchZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 연 책. 형식마다 리더가 다르다 — 리플로우(EPUB·TXT)는 조판하고, PDF 는 쪽을 그린다. */
sealed interface OpenedBook : AutoCloseable {
    class Reflow(val reader: BookReader) : OpenedBook {
        override fun close() = reader.close()
    }

    class Pdf(val reader: PdfReader) : OpenedBook {
        override fun close() = reader.close()
    }
}

class OloApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as OloApp).container

/**
 * 앱 전체에서 하나뿐인 것들. DI 라이브러리 없이 손으로 묶는다(확정 사항: 수동 DI).
 */
class AppContainer(private val app: Application) {
    val data = ReaderData(app)

    /**
     * 조판 캐시. 지워져도 다시 만들 수 있지만 앱 파일 영역에 둔다 — 캐시 영역에 두면
     * 저장 공간이 부족할 때 시스템이 지워서, 긴 책을 열 때마다 처음부터 조판하게 된다.
     */
    val pages = PageStore(File(app.filesDir, "pages"))

    val prefs = PrefsStore(app)

    /**
     * 본문 글꼴(휴대폰 글꼴 + 사용자 글꼴). 사용자가 넣은 폰트는 앱 파일 영역에 둔다 — 캐시
     * 영역이면 저장 공간이 부족할 때 시스템이 지운다.
     */
    val fonts = FontCatalog(UserFonts(File(app.filesDir, "fonts")))

    /**
     * PDF 엔진. 테스트가 가짜로 바꾼다 — Robolectric 에는 PDF 엔진(pdfium)이 없어 PdfRenderer 가
     * 돌지 않는다. Pdfium 으로 바꿀 때도 이 한 줄이다.
     */
    @androidx.annotation.VisibleForTesting
    internal var pdfEngine: (android.os.ParcelFileDescriptor) -> PdfSource = ::PlatformPdfSource

    /** 듣기 엔진(4단계). 테스트가 가짜로 바꾼다 — Robolectric 에는 음성 엔진이 없다. */
    @androidx.annotation.VisibleForTesting
    internal var listenKit: ListenKit = ListenKit.android(app)

    /** 책을 연다. */
    suspend fun open(book: LibraryBook): OpenedBook = withContext(Dispatchers.IO) {
        val opened = read(book.id, book.displayName, book.format, Uri.parse(book.id.value))
        closingOnFailure(opened) {
            // 목록이 파일 이름 대신 책 제목·저자를 보여 주게 한다. TXT 는 제목이 곧 파일 이름이고, PDF 는
            // 파일에 적혀 있을 때만(없으면 파일 이름 그대로 둔다).
            when {
                opened is OpenedBook.Reflow && book.format == BookFormat.EPUB ->
                    opened.reader.document.meta.let { data.library.updateMetadata(book.id, it.title, it.author) }
                opened is OpenedBook.Pdf && opened.reader.book.hasOwnTitle ->
                    opened.reader.book.meta.let { data.library.updateMetadata(book.id, it.title, it.author) }
            }
            data.library.markOpened(book.id, System.currentTimeMillis())
            opened
        }
    }

    /**
     * 다른 앱이 넘긴 파일을 연다.
     *
     * 같은 파일(이름·크기)이 라이브러리에 있으면 **그 책으로** 연다 — 진도·책갈피가 한 벌로
     * 이어지고 최근 목록에도 오른다. 라이브러리 쪽이 열리지 않으면(폴더 권한이 풀림) 받은
     * URI 로 연다. 받은 파일은 라이브러리·최근 목록에 넣지 않는다([IncomingFile]).
     */
    suspend fun openIncoming(file: IncomingFile): OpenedBook = withContext(Dispatchers.IO) {
        data.library.findByFile(file.displayName, file.sizeBytes)?.let { book ->
            try {
                return@withContext open(book)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 취소는 "라이브러리 쪽이 안 열림" 이 아니다. 삼키면 취소된 뒤에도 받은 URI 로 한 번 더 연다.
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OloApp", "library copy of ${file.displayName} did not open", e)
            }
        }
        read(BookId(file.uri.toString()), file.displayName, file.format, file.uri)
    }

    /**
     * 연 것으로 뒷일을 하되, 그 뒷일이 실패하거나 취소되면 연 것을 닫는다. 닫지 않으면 파일 디스크립터와
     * 캐시 사본이 남는다(화면에 넘기지 못했으니 닫을 사람이 없다).
     */
    private suspend fun <T> closingOnFailure(opened: Any, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        (opened as? AutoCloseable)?.runCatching { close() }
        throw e
    }

    /**
     * 책 글꼴표를 읽어 리더를 만든다. 글꼴은 여기서 꺼내지 않는다 — 출판사 글꼴을 끈 사람에게
     * 수십 MB 를 풀 이유가 없다. 처음으로 책 글꼴로 조판할 때 꺼낸다.
     */
    private suspend fun reader(document: ReflowDocument): BookReader {
        val table = try {
            BookFontTable.load(document)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 글꼴표를 못 만들어도 책은 연다(출판사 글꼴만 없다).
            BookFontTable.EMPTY
        }
        return BookReader(
            fonts = fonts,
            document = document,
            bookFonts = table,
            bookFontsDir = bookFontsDir(document.meta.id),
            store = pages,
            bookmarkRepository = data.bookmarks,
            progressRepository = data.progress,
            annotationRepository = data.annotations,
        )
    }

    /**
     * 책마다 글꼴을 꺼내 둘 곳. 캐시 영역이다 — 지워져도 다시 꺼내면 되고, 공간이 모자랄 때
     * 시스템이 가장 먼저 비울 수 있어야 한다.
     */
    private fun bookFontsDir(id: BookId): File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(id.value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(app.cacheDir, "book-fonts/$hash")
    }

    private suspend fun read(id: BookId, name: String, format: BookFormat, uri: Uri): OpenedBook = when (format) {
        BookFormat.EPUB -> reflow(EpubDocument.open(id, name, data.sources.seekableSource(uri)))
        BookFormat.TXT -> reflow(TxtDocument.open(id, name, data.sources.byteSource(uri)))
        BookFormat.PDF -> {
            val book = PdfBook.open(id, name, data.sources.seekableDescriptor(uri), pdfEngine)
            closingOnFailure(book) {
                val reader = PdfReader(book, data.bookmarks, data.progress)
                reader.open()
                OpenedBook.Pdf(reader)
            }
        }
    }

    private suspend fun reflow(document: ReflowDocument): OpenedBook =
        closingOnFailure(document) { OpenedBook.Reflow(reader(document)) }
}

/**
 * 책을 못 열었을 때 사람에게 보여 줄 문장.
 *
 * 예외 메시지를 그대로 보여 주지 않는다("end of central directory not found" 는 읽는
 * 사람에게 아무 뜻이 없다). 무슨 일이고 무엇을 하면 되는지만 말하고, 원문은 로그에 남긴다.
 */
fun describeOpenFailure(error: Throwable, format: BookFormat?): String = when {
    error is UnsupportedOperationException -> error.message ?: "아직 열 수 없는 형식입니다."
    // PdfRenderer 는 암호가 걸린 파일에 SecurityException 을 던진다. 권한이 풀린 것과 같은 예외라
    // 문구로 가른다 — "파일을 찾을 수 없습니다" 라고 하면 멀쩡히 있는 파일을 찾아 헤맨다.
    format == BookFormat.PDF && error is SecurityException && error.message.orEmpty().contains("password", ignoreCase = true) ->
        "암호가 걸린 PDF 입니다. 암호를 푼 파일로 다시 열어 주세요."
    error is java.io.FileNotFoundException || error is SecurityException ->
        "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 라이브러리를 새로고침해 보세요."
    format == BookFormat.EPUB && error is java.io.IOException ->
        "EPUB 파일이 손상됐거나 EPUB 형식이 아닙니다. 다른 곳에서 다시 받아 보세요."
    format == BookFormat.PDF && error is java.io.IOException ->
        "PDF 파일이 손상됐거나 PDF 형식이 아닙니다. 다른 곳에서 다시 받아 보세요."
    error is java.io.IOException -> "파일을 읽는 중 문제가 생겼습니다. 저장소가 연결돼 있는지 확인해 보세요."
    else -> "책을 여는 중 문제가 생겼습니다."
}

/**
 * 보기 설정 저장.
 *
 * DataStore 대신 SharedPreferences 를 쓴다. 값이 작고, 리더를 여는 순간 동기로
 * 읽어야 첫 조판을 옛 설정으로 한 번 더 하지 않는다. DataStore 는 비동기라 첫 값이
 * 오기 전에 기본값으로 조판이 시작된다.
 */
class PrefsStore(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun load(): ReaderPrefs = ReaderPrefs(
        fontSizeSp = sp.getInt(KEY_SIZE, ReaderPrefs.DEFAULT_SIZE_SP)
            .coerceIn(ReaderPrefs.MIN_SIZE_SP, ReaderPrefs.MAX_SIZE_SP),
        font = migrateFont(sp.getString(KEY_FONT, null)),
        lineSpacing = ReaderPrefs.LineSpacing.entries.firstOrNull { it.name == sp.getString(KEY_SPACING, null) }
            ?: ReaderPrefs.LineSpacing.Normal,
        publisherFonts = sp.getBoolean(KEY_PUBLISHER_FONTS, true),
        margin = enumOf(KEY_MARGIN, ReaderPrefs.Margin.Normal),
        align = enumOf(KEY_ALIGN, ReaderPrefs.ParagraphAlign.Original),
        indent = enumOf(KEY_INDENT, ReaderPrefs.Indent.Original),
        paragraphSpacing = enumOf(KEY_PARAGRAPH_SPACING, ReaderPrefs.ParagraphSpacing.Tight),
        screen = ScreenPrefs(
            theme = enumOf(KEY_THEME, PaperTheme.System),
            // 음수는 "시스템 밝기"(저장하지 않은 것과 같다).
            brightness = sp.getFloat(KEY_BRIGHTNESS, -1f).takeIf { it >= 0f }?.coerceAtMost(1f),
            keepScreenOn = enumOf(KEY_KEEP_ON, KeepScreenOn.System),
            volumeKeys = sp.getBoolean(KEY_VOLUME_KEYS, false),
            touch = enumOf(KEY_TOUCH, TouchZones.Default),
            footer = Footer(
                left = enumOf(KEY_FOOTER_LEFT, FooterItem.BookTitle),
                center = enumOf(KEY_FOOTER_CENTER, FooterItem.Page),
                right = enumOf(KEY_FOOTER_RIGHT, FooterItem.Percent),
            ),
            rotation = enumOf(KEY_ROTATION, ScreenRotation.Auto),
            twoPagesLandscape = sp.getBoolean(KEY_TWO_PAGES_LANDSCAPE, true),
            twoPagesPortrait = sp.getBoolean(KEY_TWO_PAGES_PORTRAIT, false),
            pdfCoverAlone = sp.getBoolean(KEY_PDF_COVER_ALONE, true),
            pageTurn = enumOf(KEY_PAGE_TURN, PageTurn.None),
            brightnessGesture = sp.getBoolean(KEY_BRIGHTNESS_GESTURE, true),
            autoTurn = enumOf(KEY_AUTO_TURN, AutoTurn.Off),
        ),
        listen = ListenPrefs(
            // 망가진 값(범위 밖)은 범위 안으로 — 0 배속으로 저장된 값 때문에 듣기가 안 되면 안 된다.
            rate = sp.getFloat(KEY_LISTEN_RATE, 1f).takeIf { it.isFinite() }?.coerceIn(ListenPrefs.MIN_RATE, ListenPrefs.MAX_RATE) ?: 1f,
            engine = sp.getString(KEY_LISTEN_ENGINE, null),
            voice = sp.getString(KEY_LISTEN_VOICE, null),
            voiceLabel = sp.getString(KEY_LISTEN_VOICE_LABEL, null),
        ),
    )

    /** 저장된 이름의 값. 모르는 이름(나중 판에서 빠진 것)이면 기본값 — 옛 설정 때문에 책이 안 열리면 안 된다. */
    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == sp.getString(key, null) } ?: default

    fun save(prefs: ReaderPrefs) {
        sp.edit()
            .putInt(KEY_SIZE, prefs.fontSizeSp)
            .putString(KEY_FONT, prefs.font)
            .putString(KEY_SPACING, prefs.lineSpacing.name)
            .putBoolean(KEY_PUBLISHER_FONTS, prefs.publisherFonts)
            .putString(KEY_MARGIN, prefs.margin.name)
            .putString(KEY_ALIGN, prefs.align.name)
            .putString(KEY_INDENT, prefs.indent.name)
            .putString(KEY_PARAGRAPH_SPACING, prefs.paragraphSpacing.name)
            .putString(KEY_THEME, prefs.screen.theme.name)
            .putFloat(KEY_BRIGHTNESS, prefs.screen.brightness ?: -1f)
            .putString(KEY_KEEP_ON, prefs.screen.keepScreenOn.name)
            .putBoolean(KEY_VOLUME_KEYS, prefs.screen.volumeKeys)
            .putString(KEY_TOUCH, prefs.screen.touch.name)
            .putString(KEY_FOOTER_LEFT, prefs.screen.footer.left.name)
            .putString(KEY_FOOTER_CENTER, prefs.screen.footer.center.name)
            .putString(KEY_FOOTER_RIGHT, prefs.screen.footer.right.name)
            .putString(KEY_ROTATION, prefs.screen.rotation.name)
            .putBoolean(KEY_TWO_PAGES_LANDSCAPE, prefs.screen.twoPagesLandscape)
            .putBoolean(KEY_TWO_PAGES_PORTRAIT, prefs.screen.twoPagesPortrait)
            .putBoolean(KEY_PDF_COVER_ALONE, prefs.screen.pdfCoverAlone)
            .putString(KEY_PAGE_TURN, prefs.screen.pageTurn.name)
            .putBoolean(KEY_BRIGHTNESS_GESTURE, prefs.screen.brightnessGesture)
            .putString(KEY_AUTO_TURN, prefs.screen.autoTurn.name)
            .putFloat(KEY_LISTEN_RATE, prefs.listen.rate)
            .putString(KEY_LISTEN_ENGINE, prefs.listen.engine)
            .putString(KEY_LISTEN_VOICE, prefs.listen.voice)
            .putString(KEY_LISTEN_VOICE_LABEL, prefs.listen.voiceLabel)
            .apply()
    }

    /**
     * 읽는 속도(남은 시간, E5). 글자(EPUB · TXT)와 쪽(PDF)을 따로 둔다 — 단위가 달라 섞으면 둘 다 틀린다.
     * 책을 닫아도 남아야 다음 책에서 처음부터 다시 재지 않는다.
     */
    fun loadSpeed(kind: String): ReadingSpeed {
        val value = sp.getFloat("speed.$kind", -1f)
        return ReadingSpeed(value.takeIf { it > 0f }?.toDouble(), sp.getInt("speed.$kind.samples", 0))
    }

    fun saveSpeed(kind: String, speed: ReadingSpeed) {
        sp.edit().putFloat("speed.$kind", speed.unitsPerMinute?.toFloat() ?: -1f).putInt("speed.$kind.samples", speed.samples).apply()
    }

    companion object {
        /**
         * 없어진 글꼴 설정을 지운다: 0.3.0 까지의 번들 폰트("batang@…", "gothic@…")와 0.7.0 까지의
         * 시스템 명조("system-serif"). 모두 휴대폰 글꼴(null = 기본)로 연다. 남겨 두면 모르는 키라
         * 결과는 같지만, 나중에 같은 이름의 키가 생기면 옛 설정이 엉뚱한 글꼴을 가리킨다.
         */
        fun migrateFont(saved: String?): String? = when (saved?.substringBefore('@')) {
            "batang", "gothic", "system-serif" -> null
            else -> saved
        }

        private const val KEY_SIZE = "fontSizeSp"
        private const val KEY_FONT = "font"
        private const val KEY_SPACING = "lineSpacing"
        private const val KEY_PUBLISHER_FONTS = "publisherFonts"
        private const val KEY_ROTATION = "rotation"
        private const val KEY_MARGIN = "margin"
        private const val KEY_ALIGN = "align"
        private const val KEY_INDENT = "indent"
        private const val KEY_PARAGRAPH_SPACING = "paragraphSpacing"
        private const val KEY_THEME = "theme"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_KEEP_ON = "keepScreenOn"
        private const val KEY_VOLUME_KEYS = "volumeKeys"
        private const val KEY_TOUCH = "touchZones"
        private const val KEY_FOOTER_LEFT = "footerLeft"
        private const val KEY_FOOTER_CENTER = "footerCenter"
        private const val KEY_FOOTER_RIGHT = "footerRight"
        private const val KEY_TWO_PAGES_LANDSCAPE = "twoPagesLandscape"
        private const val KEY_TWO_PAGES_PORTRAIT = "twoPagesPortrait"
        private const val KEY_PDF_COVER_ALONE = "pdfCoverAlone"
        private const val KEY_PAGE_TURN = "pageTurn"
        private const val KEY_BRIGHTNESS_GESTURE = "brightnessGesture"
        private const val KEY_AUTO_TURN = "autoTurn"
        private const val KEY_LISTEN_RATE = "listenRate"
        private const val KEY_LISTEN_ENGINE = "listenEngine"
        private const val KEY_LISTEN_VOICE = "listenVoice"
        private const val KEY_LISTEN_VOICE_LABEL = "listenVoiceLabel"
    }
}
