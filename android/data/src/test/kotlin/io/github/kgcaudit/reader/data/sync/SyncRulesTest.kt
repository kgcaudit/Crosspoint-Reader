package io.github.kgcaudit.reader.data.sync

import io.github.kgcaudit.reader.data.backup.BookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.document.Locator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기기 간 이어 읽기의 규칙(결정 8-2 · 8-3). 언제 묻고, 무엇을 조용히 합치고, 무엇은 절대 저절로 옮기지 않는가. */
class SyncRulesTest {

    private fun reflow(spine: Int, offset: Int) = Locator.encode(Locator.Reflow(spine, offset))

    @Test
    fun `a place inside the page already on screen is not offered even if its offset is larger`() {
        // 태블릿 한 쪽(글자 1000~2500) 안에 휴대폰의 자리(1400)가 있다. 띄우면 "거기로" 를 눌러도 같은 쪽에 머문다.
        assertFalse(SyncRules.reflowBeyond(Locator.Reflow(3, 1400), shownSpine = 3, shownEndExclusive = 2500))
        // 보이는 쪽 끝을 넘었으면 적어도 다음 쪽이다. 끝 글자 자체가 다음 쪽의 첫 글자다(끝은 포함하지 않는다).
        assertTrue(SyncRules.reflowBeyond(Locator.Reflow(3, 2500), shownSpine = 3, shownEndExclusive = 2500))
        // 다음 장은 새 쪽에서 시작한다 — 장 머리라도 뒤다. 앞 장은 아무리 뒤 글자라도 앞이다.
        assertTrue(SyncRules.reflowBeyond(Locator.Reflow(4, 0), shownSpine = 3, shownEndExclusive = 2500))
        assertFalse(SyncRules.reflowBeyond(Locator.Reflow(2, 99_999), shownSpine = 3, shownEndExclusive = 2500))
    }

    @Test
    fun `pdf and comic pages must be beyond what is shown`() {
        // 두쪽보기의 오른쪽 쪽(12)에 있는 자리는 이미 보인다.
        assertFalse(SyncRules.pageBeyond(remotePage = 12, shownLast = 12))
        assertTrue(SyncRules.pageBeyond(remotePage = 13, shownLast = 12))
        assertTrue(SyncRules.comicBeyond(5, null, 4, null))
        assertFalse(SyncRules.comicBeyond(4, null, 4, null))
        // 웹툰: 같은 그림 안에서 반 장 넘게 아래면 몇 화면 아래다. 그보다 가까우면 지금 화면 곁이다.
        assertTrue(SyncRules.comicBeyond(4, 0.8f, 4, 0.2f))
        assertFalse(SyncRules.comicBeyond(4, 0.5f, 4, 0.2f))
    }

    @Test
    fun `the furthest of several devices is offered and only when it is further than here`() {
        val here = ProgressRecord(reflow(2, 100), 20f, 5_000)
        val tab = "탭" to ProgressRecord(reflow(5, 0), 50f, 1_000)
        val pc = "PC" to ProgressRecord(reflow(7, 10), 70f, 900)
        val behind = "옛 폰" to ProgressRecord(reflow(1, 0), 10f, 9_000)
        assertEquals("PC", SyncRules.furthest(here, listOf(tab, behind, pc))?.first)
        // 다른 기기가 모두 뒤면 묻지 않는다 — 나중에 적혔어도(옛 폰이 가장 최근) 덜 읽은 자리다.
        assertNull(SyncRules.furthest(here, listOf(behind)))
        // 깨진 자리는 고르지 않는다.
        assertNull(SyncRules.furthest(here, listOf("깨짐" to ProgressRecord("r:x", 99f, 1))))
        // 이 기기에서 아직 펼치지 않은 책: 다른 기기의 자리는 무엇이든 더 읽은 것이다.
        assertEquals("탭", SyncRules.furthest(null, listOf(tab))?.first)
    }

    @Test
    fun `comic offers pick the furthest page`() {
        val here = ComicRecord("1권.cbz", 10, page = 3, pageCount = 100)
        val a = "a" to ComicRecord("1권.cbz", 10, page = 50, pageCount = 100)
        val b = "b" to ComicRecord("1권.cbz", 10, page = 80, pageCount = 100)
        assertEquals("b", SyncRules.furthestComic(here, listOf(a, b))?.first)
        // 책갈피만 있는 권(쪽 없음)은 자리가 아니다.
        assertNull(SyncRules.furthestComic(here, listOf("c" to ComicRecord("1권.cbz", 10, page = null))))
    }

    @Test
    fun `bookmarks are a union but one removed here is not brought back`() {
        val item = "폴더|책.epub|10"
        val remote = listOf(
            BookmarkRecord(reflow(1, 0), "가", 1),
            BookmarkRecord(reflow(2, 0), "나", 2),
            BookmarkRecord(reflow(3, 0), "다", 3),
            BookmarkRecord(reflow(3, 0), "다(중복)", 4),
        )
        // 1장은 이미 있다, 2장은 전에 받았다가 이 기기에서 뺐다, 3장은 처음 본다 — 3장만 들인다(같은 자리 둘은 하나로).
        val imported = SyncRules.bookmarksToImport(
            item,
            local = setOf(reflow(1, 0)),
            remote = remote,
            seen = setOf(SyncRules.bookmarkKey(item, reflow(2, 0))),
        )
        assertEquals(listOf(reflow(3, 0)), imported.map { it.locator })
    }

    @Test
    fun `the finished date keeps the earliest and is not forced back after it was cleared here`() {
        // 이 기기에 없으면 들인다.
        assertEquals(100L, SyncRules.finishedToApply(local = null, remote = 100L, seen = false))
        // 둘 다 있으면 이른 쪽 — 백업 합치기와 같은 규칙.
        assertEquals(100L, SyncRules.finishedToApply(local = 200L, remote = 100L, seen = false))
        assertNull(SyncRules.finishedToApply(local = 50L, remote = 100L, seen = false))
        // 전에 들였던 때인데 지금 없다 = 사람이 "다 읽음" 을 거뒀다. 다시 넣지 않는다.
        assertNull(SyncRules.finishedToApply(local = null, remote = 100L, seen = true))
        assertNull(SyncRules.finishedToApply(local = 100L, remote = null, seen = false))
    }
}
