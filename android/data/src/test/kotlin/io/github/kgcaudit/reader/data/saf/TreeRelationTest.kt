package io.github.kgcaudit.reader.data.saf

import org.junit.Test
import kotlin.test.assertEquals

class TreeRelationTest {

    @Test
    fun `a folder inside a registered folder is seen as overlapping`() {
        // Books 를 등록한 뒤 Books/소설 을 또 등록하면 소설의 책이 두 번 보였다.
        assertEquals(TreeRelation.Inside, treeRelation("primary:Books", "primary:Books/소설"))
        assertEquals(TreeRelation.Contains, treeRelation("primary:Books/소설", "primary:Books"))
        assertEquals(TreeRelation.Inside, treeRelation("primary:", "primary:Books"), "저장소 전체 안의 폴더")
        assertEquals(TreeRelation.Same, treeRelation("primary:Books", "primary:Books/"))
    }

    @Test
    fun `a folder whose name only begins the same is not inside`() {
        // 망가뜨린 입력: 글자로만 앞부분을 비교하면 Books2 가 Books 안에 든 것으로 읽혀 멀쩡한 폴더를 막는다.
        assertEquals(TreeRelation.Apart, treeRelation("primary:Books", "primary:Books2"))
        assertEquals(TreeRelation.Apart, treeRelation("primary:Books", "1234-5678:Books"))
    }
}
