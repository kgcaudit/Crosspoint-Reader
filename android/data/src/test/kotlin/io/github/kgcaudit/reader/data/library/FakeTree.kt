package io.github.kgcaudit.reader.data.library

import java.io.IOException

/**
 * 손으로 짜는 폴더 트리. `"a/b/책.epub"` 처럼 경로만 적으면 폴더가 생긴다.
 *
 * [failing] 에 든 폴더는 목록을 못 읽는다(권한 회수, 제공자 무응답을 흉내 낸다).
 */
class FakeTree(
    paths: List<String>,
    private val failing: Set<String> = emptySet(),
    private val sizes: Map<String, Long> = emptyMap(),
) : DocumentTree {

    private val children = HashMap<String, MutableList<TreeEntry>>()
    override val rootKey: String = ""

    init {
        for (path in paths) {
            val parts = path.split('/')
            var parent = ""
            parts.forEachIndexed { i, name ->
                val key = if (parent.isEmpty()) name else "$parent/$name"
                val isDir = i < parts.size - 1
                val list = children.getOrPut(parent) { ArrayList() }
                if (list.none { it.key == key }) {
                    list += TreeEntry(key, uriOf(key), name, isDir, sizes[key] ?: 100L, 1L)
                }
                parent = key
            }
        }
    }

    /** 폴더 [from] 아래에 [to] 를 가리키는 폴더 항목을 넣는다(심볼릭 링크 흉내). */
    fun link(from: String, name: String, to: String) {
        children.getOrPut(from) { ArrayList() } += TreeEntry(to, uriOf(to), name, isDirectory = true)
    }

    override fun children(key: String): List<TreeEntry> {
        if (key in failing) throw IOException("cannot list $key")
        return children[key].orEmpty()
    }

    companion object {
        fun uriOf(key: String) = "content://fake/tree/root/document/$key"
    }
}
