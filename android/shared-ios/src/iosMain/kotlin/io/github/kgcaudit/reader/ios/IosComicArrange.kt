package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.NaturalOrder
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.document.comic.ComicUnit

/** 작품 정리 화면(구상안 40)의 글 · 차례. 기기 없이 시험하려고 화면 밖에 둔다. */
object IosComicArrange {
    /** 작품의 세는 말: 웹툰은 화, 그 밖은 권. */
    fun unitWord(work: Work): String = if (work.webtoon) "화" else "권"

    /**
     * 한 폴더에 든 것(안드로이드 `placeSummary`): 읽는 권은 이어 적고("1–6권 · 외전"), 다른 곳의 권을 고른 사본은 "(같은 권)" 을
     * 붙인다 — 폴더를 지우기 전에 무엇이 그 폴더에만 있는지 알 수 있다.
     */
    fun placeSummary(work: Work, place: String): String {
        val chosen = work.entries.filter { it.unit.place == place }
        val copies = work.entries.filter { e -> e.unit.place != place && e.copies.any { it.place == place } }
        return listOf(ComicShelf.summary(chosen), copies.joinToString(" · ") { "${it.label}(같은 ${unitWord(work)})" })
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** 합칠 수 있는 작품들(이 작품 말고, 이름 차례). */
    fun others(work: Work, all: List<Work>): List<Work> = all.filter { it.key != work.key }.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }

    /** 같은 권의 파일들(자리 · 이름 차례). 고르는 판이 이 차례로 보인다. */
    fun copies(entry: WorkEntry): List<ComicUnit> =
        (listOf(entry.unit) + entry.copies).sortedWith { a, b -> NaturalOrder.compare(a.place + "/" + a.name, b.place + "/" + b.name) }
}
