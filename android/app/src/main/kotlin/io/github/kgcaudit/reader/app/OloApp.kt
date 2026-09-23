package io.github.kgcaudit.reader.app

import android.app.Application
import android.content.Context
import android.net.Uri
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.text.ReaderFont
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

    /** 책을 연다. PDF 는 아직 리더가 없다(결정 P1 미결). */
    suspend fun open(book: LibraryBook): BookReader = withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.id.value)
        val document: ReflowDocument = when (book.format) {
            BookFormat.EPUB -> EpubDocument.open(book.id, book.displayName, data.sources.seekableSource(uri))
            BookFormat.TXT -> TxtDocument.open(book.id, book.displayName, data.sources.byteSource(uri))
            BookFormat.PDF -> throw UnsupportedOperationException("PDF 보기는 다음 판에서 지원합니다")
        }
        // 목록이 파일 이름 대신 책 제목을 보여 주게 한다. TXT 는 제목이 곧 파일 이름이다.
        if (book.format == BookFormat.EPUB) {
            data.library.updateMetadata(book.id, document.meta.title, document.meta.author)
        }
        data.library.markOpened(book.id, System.currentTimeMillis())
        BookReader(app, document, pages, data.bookmarks, data.progress)
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
        font = ReaderFont.of(sp.getString(KEY_FONT, null).orEmpty()),
        lineSpacing = ReaderPrefs.LineSpacing.entries.firstOrNull { it.name == sp.getString(KEY_SPACING, null) }
            ?: ReaderPrefs.LineSpacing.Normal,
    )

    fun save(prefs: ReaderPrefs) {
        sp.edit()
            .putInt(KEY_SIZE, prefs.fontSizeSp)
            .putString(KEY_FONT, prefs.font.key)
            .putString(KEY_SPACING, prefs.lineSpacing.name)
            .apply()
    }

    private companion object {
        const val KEY_SIZE = "fontSizeSp"
        const val KEY_FONT = "font"
        const val KEY_SPACING = "lineSpacing"
    }
}
