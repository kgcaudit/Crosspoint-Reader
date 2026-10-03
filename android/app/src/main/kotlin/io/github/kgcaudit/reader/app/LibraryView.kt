package io.github.kgcaudit.reader.app

import android.content.Context
import io.github.kgcaudit.reader.data.library.LibraryBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Collator
import java.util.Locale

/** 서재를 어떻게 늘어놓을까. 탭 전체(읽는 중 · 읽은 · 읽을)에 함께 적용한다(0.38.0, 2026-10-03 사용자 결정 3). */
enum class LibraryLayout(val label: String) { Grid("격자로 보기"), List("목록으로 보기"), Shelf("책장으로 보기") }

/** 서재의 차례. 탭 전체에 함께 적용한다. */
enum class LibrarySort(val label: String) {
    /**
     * 최근 읽은 것이 앞(기본, 0.38.0). 읽는 중 · 읽은 갈래가 이 차례일 때 이어 볼 책이 맨 앞에 선다. 한 번도 열지 않은
     * 갈래에는 읽은 때가 없으니 이름순으로 선다.
     */
    Recent("최근 읽은 순"),
    Name("이름순"),
    Added("추가한 순"),
    Size("크기순");

    fun sort(books: List<LibraryBook>, recent: ((LibraryBook) -> Long?)? = null): List<LibraryBook> =
        arrange(books, { it.label }, { it.addedAtEpochMs }, { it.sizeBytes }, recent)

    /**
     * 책 · 만화 작품 어느 쪽이든 같은 규칙으로 늘어놓는다. [recent] 가 없으면(읽을 갈래) "최근 읽은 순" 은 이름순이다.
     * 값을 모르는 것(null)은 맨 뒤 — 크기를 알려 주지 않는 제공자의 책이 맨 앞에 서지 않게.
     */
    fun <T> arrange(items: List<T>, label: (T) -> String, added: (T) -> Long?, size: (T) -> Long?, recent: ((T) -> Long?)? = null): List<T> {
        val byName = compareBy(KOREAN, label)
        return when (this) {
            // 한글은 가나다, 영문은 대소문자를 가리지 않는다. 문자 코드 순이면 "Zoo" 가 "apple" 앞에 온다.
            Name -> items.sortedWith(byName)
            Recent -> if (recent == null) items.sortedWith(byName) else items.sortedWith(compareByDescending<T> { recent(it) ?: Long.MIN_VALUE }.then(byName))
            // 최근에 넣은 책이 앞 — 폴더에 막 넣은 책을 찾으려고 고르는 차례다.
            Added -> items.sortedWith(compareByDescending<T> { added(it) ?: Long.MIN_VALUE }.then(byName))
            // 큰 책이 앞.
            Size -> items.sortedWith(compareByDescending<T> { size(it) ?: Long.MIN_VALUE }.then(byName))
        }
    }

    private companion object {
        val KOREAN: Collator = Collator.getInstance(Locale.KOREAN)
    }
}

/**
 * 라이브러리 보기 설정(격자/목록 · 차례). 앱을 다시 켜도 고른 보기 그대로다. 이름으로 저장한다 — 순서를 바꾸거나
 * 사이에 끼워도 이미 고른 값이 밀리지 않고, 모르는 이름(다음 판이 쓴 값)은 기본값이 된다.
 */
class LibraryViewStore(context: Context) {
    private val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(LibraryLayout.entries.firstOrNull { it.name == sp.getString(KEY_LAYOUT, null) } ?: LibraryLayout.Grid)
    private val _sort = MutableStateFlow(LibrarySort.entries.firstOrNull { it.name == sp.getString(KEY_SORT, null) } ?: LibrarySort.Recent)

    val layout: StateFlow<LibraryLayout> = _layout.asStateFlow()
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    fun setLayout(value: LibraryLayout) {
        _layout.value = value
        sp.edit().putString(KEY_LAYOUT, value.name).apply()
    }

    fun setSort(value: LibrarySort) {
        _sort.value = value
        sp.edit().putString(KEY_SORT, value.name).apply()
    }

    private companion object {
        const val KEY_LAYOUT = "layout"
        const val KEY_SORT = "sort"
    }
}

/** "9.2MB" · "84KB". 목록 보기의 한 줄에 들어갈 만큼 짧게. */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1fMB".format(Locale.ROOT, bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

/** 책이 홈의 어느 갈래에 있는가. 찾기 결과가 이 차례로 선다(이어 읽을 책이 먼저). */
/** 서재의 세 칸. 이름은 책 · 만화 모두 같다(0.43.0 — "읽는 중 · 다 읽은 작품" 이 섞여 있었다). */
enum class Shelf(val label: String) { Reading("읽는 책"), Read("읽은 책"), ToRead("읽을 책") }

/** 찾은 책 한 권과 그 갈래. */
data class BookHit(val book: LibraryBook, val shelf: Shelf)

/**
 * 라이브러리에서 [query] 가 제목 · 저자 · 파일 이름에 든 책. 대소문자와 띄어쓰기를 가리지 않는다("어린왕자" 로 "어린
 * 왕자" 를 찾는다) — 사람은 책 이름의 띄어쓰기를 기억하지 못한다. 정규식이 아니라 글자로 찾는다: "(" · "[" 를 쳐도
 * 오류가 나지 않는다.
 *
 * 차례는 읽는 중 → 읽은 책 → 읽을 책, 같은 갈래 안에서는 이름순(가나다).
 */
fun findBooks(books: List<LibraryBook>, reading: Set<io.github.kgcaudit.reader.document.BookId>, read: Set<io.github.kgcaudit.reader.document.BookId>, query: String): List<BookHit> {
    val q = squash(query)
    if (q.isEmpty()) return emptyList()
    return books
        .filter { b -> listOfNotNull(b.label, b.author, b.displayName).any { squash(it).contains(q) } }
        .map { b -> BookHit(b, if (b.id in reading) Shelf.Reading else if (b.id in read) Shelf.Read else Shelf.ToRead) }
        .sortedWith(compareBy<BookHit> { it.shelf.ordinal }.thenBy(Collator.getInstance(Locale.KOREAN)) { it.book.label })
}

/** 비교용: 소문자, 띄어쓰기 없음. */
private fun squash(text: String): String = text.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

/**
 * [text] 안에서 [query] 가 든 구간(칠할 곳). 띄어쓰기를 가리지 않고 찾으므로, 원문의 띄어쓰기를 건너뛰며 맞춘다 — "어린왕자"
 * 로 찾은 "어린 왕자" 는 "어린 왕자" 전체를 칠한다. 없으면 null.
 */
fun matchRange(text: String, query: String): IntRange? {
    val q = squash(query)
    if (q.isEmpty()) return null
    for (start in text.indices) {
        if (text[start].isWhitespace()) continue
        var i = start
        var k = 0
        while (i < text.length && k < q.length) {
            val ch = text[i]
            if (ch.isWhitespace()) { i++; continue }
            if (ch.lowercaseChar() != q[k]) break
            i++; k++
        }
        if (k == q.length) return start until i
    }
    return null
}
