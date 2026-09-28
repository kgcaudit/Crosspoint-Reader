package io.github.kgcaudit.reader.app

import android.content.Context
import io.github.kgcaudit.reader.data.library.LibraryBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Collator
import java.util.Locale

/** 읽을 책을 어떻게 늘어놓을까. */
enum class LibraryLayout(val label: String) { Grid("격자로 보기"), List("목록으로 보기") }

/** 읽을 책의 차례. */
enum class LibrarySort(val label: String) {
    Name("이름순"),
    Added("추가한 순"),
    Size("크기순");

    fun sort(books: List<LibraryBook>): List<LibraryBook> = when (this) {
        // 한글은 가나다, 영문은 대소문자를 가리지 않는다. 문자 코드 순이면 "Zoo" 가 "apple" 앞에 온다.
        Name -> books.sortedWith(compareBy(KOREAN) { it.label })
        // 최근에 넣은 책이 앞 — 폴더에 막 넣은 책을 찾으려고 고르는 차례다.
        Added -> books.sortedWith(compareByDescending<LibraryBook> { it.addedAtEpochMs ?: 0L }.thenBy(KOREAN) { it.label })
        // 큰 책이 앞. 크기를 모르는(제공자가 알려 주지 않은) 책은 맨 뒤.
        Size -> books.sortedWith(compareByDescending<LibraryBook> { it.sizeBytes ?: -1L }.thenBy(KOREAN) { it.label })
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
    private val _sort = MutableStateFlow(LibrarySort.entries.firstOrNull { it.name == sp.getString(KEY_SORT, null) } ?: LibrarySort.Name)

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
