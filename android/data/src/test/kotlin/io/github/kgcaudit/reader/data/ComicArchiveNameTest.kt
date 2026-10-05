package io.github.kgcaudit.reader.data

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicArchiveNameTest {

    @Test
    fun `an archive entry cannot write outside the unpack folder`() {
        // 일부러 만든 압축이 "../../databases/reader.db" 같은 이름으로 앱의 DB · 설정을 덮어쓸 수 있었다(zip slip).
        for (bad in listOf("../a.png", "ep/../../a.png", "/data/a.png", "\\a.png", "ep\\..\\..\\a.png", "")) {
            assertFalse(ComicArchive.safeEntryName(bad), bad)
        }
        // 보통 이름 · 점이 든 이름은 그대로 쪽이다 — 너무 넓게 거르면 멀쩡한 쪽이 사라진다.
        for (good in listOf("001.png", "1화/002.jpg", "a..b.png", ".hidden/003.png", "...png")) {
            assertTrue(ComicArchive.safeEntryName(good), good)
        }
    }
}
