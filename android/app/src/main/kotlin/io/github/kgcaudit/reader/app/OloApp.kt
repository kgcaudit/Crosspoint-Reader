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
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.text.UserFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
     * 본문 글꼴. 명조가 있는지 한 번 재 두고 앱이 떠 있는 동안 같은 답을 쓴다. 사용자가 넣은
     * 폰트는 앱 파일 영역에 둔다 — 캐시 영역이면 저장 공간이 부족할 때 시스템이 지운다.
     */
    val fonts = FontCatalog(UserFonts(File(app.filesDir, "fonts")))

    /** 책을 연다. PDF 는 아직 리더가 없다(결정 P1 미결). */
    suspend fun open(book: LibraryBook): BookReader = withContext(Dispatchers.IO) {
        val document = read(book.id, book.displayName, book.format, Uri.parse(book.id.value))
        // 목록이 파일 이름 대신 책 제목을 보여 주게 한다. TXT 는 제목이 곧 파일 이름이다.
        if (book.format == BookFormat.EPUB) {
            data.library.updateMetadata(book.id, document.meta.title, document.meta.author)
        }
        data.library.markOpened(book.id, System.currentTimeMillis())
        BookReader(fonts, document, pages, data.bookmarks, data.progress)
    }

    /**
     * 다른 앱이 넘긴 파일을 연다.
     *
     * 같은 파일(이름·크기)이 라이브러리에 있으면 **그 책으로** 연다 — 진도·책갈피가 한 벌로
     * 이어지고 최근 목록에도 오른다. 라이브러리 쪽이 열리지 않으면(폴더 권한이 풀림) 받은
     * URI 로 연다. 받은 파일은 라이브러리·최근 목록에 넣지 않는다([IncomingFile]).
     */
    suspend fun openIncoming(file: IncomingFile): BookReader = withContext(Dispatchers.IO) {
        data.library.findByFile(file.displayName, file.sizeBytes)?.let { book ->
            runCatching { return@withContext open(book) }
                .onFailure { android.util.Log.w("OloApp", "library copy of ${file.displayName} did not open", it) }
        }
        val id = BookId(file.uri.toString())
        BookReader(fonts, read(id, file.displayName, file.format, file.uri), pages, data.bookmarks, data.progress)
    }

    private fun read(id: BookId, name: String, format: BookFormat, uri: Uri): ReflowDocument = when (format) {
        BookFormat.EPUB -> EpubDocument.open(id, name, data.sources.seekableSource(uri))
        BookFormat.TXT -> TxtDocument.open(id, name, data.sources.byteSource(uri))
        BookFormat.PDF -> throw UnsupportedOperationException("PDF 보기는 다음 판에서 지원합니다")
    }
}

/**
 * 책을 못 열었을 때 사람에게 보여 줄 문장.
 *
 * 예외 메시지를 그대로 보여 주지 않는다("end of central directory not found" 는 읽는
 * 사람에게 아무 뜻이 없다). 무슨 일이고 무엇을 하면 되는지만 말하고, 원문은 로그에 남긴다.
 */
fun describeOpenFailure(error: Throwable, format: BookFormat?): String = when {
    error is UnsupportedOperationException -> error.message ?: "아직 열 수 없는 형식입니다."
    error is java.io.FileNotFoundException || error is SecurityException ->
        "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 라이브러리를 새로고침해 보세요."
    format == BookFormat.EPUB && error is java.io.IOException ->
        "EPUB 파일이 손상됐거나 EPUB 형식이 아닙니다. 다른 곳에서 다시 받아 보세요."
    error is java.io.IOException -> "파일을 읽는 중 문제가 생겼습니다. 저장소가 연결돼 있는지 확인해 보세요."
    else -> "책을 여는 중 문제가 생겼습니다."
}

/**
 * 보기 설정 저장.
 *
 * DataStore 대신 SharedPreferences 를 쓴다. 값이 세 개뿐이고, 리더를 여는 순간 동기로
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
    )

    fun save(prefs: ReaderPrefs) {
        sp.edit()
            .putInt(KEY_SIZE, prefs.fontSizeSp)
            .putString(KEY_FONT, prefs.font)
            .putString(KEY_SPACING, prefs.lineSpacing.name)
            .apply()
    }

    companion object {
        /**
         * 0.3.0 까지의 번들 폰트 설정을 시스템 글꼴 키로 옮긴다. "바탕" 을 고른 사람은 명조를,
         * "고딕" 은 고딕을 원한 것이다. 옮기지 않으면 모르는 키라 기본값이 되는데, 명조 없는
         * 기기에서는 우연히 맞고 명조 있는 기기에서 고딕을 고른 사람만 명조로 바뀐다.
         */
        fun migrateFont(saved: String?): String? = when (saved?.substringBefore('@')) {
            "batang" -> FontCatalog.SERIF
            "gothic" -> FontCatalog.SANS
            else -> saved
        }

        private const val KEY_SIZE = "fontSizeSp"
        private const val KEY_FONT = "font"
        private const val KEY_SPACING = "lineSpacing"
    }
}
