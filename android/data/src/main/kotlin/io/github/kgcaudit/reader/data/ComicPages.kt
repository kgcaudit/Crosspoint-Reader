package io.github.kgcaudit.reader.data

import java.io.Closeable

/**
 * 연 만화 한 권: 쪽 이름(자연 순서)과 쪽 바이트를 꺼내는 길. 압축이면 열어 둔 파일을 쥐고 있어 [close] 해야 한다.
 *
 * [read] 는 한 번에 하나씩만 부른다 — 압축 읽기는 한 파일 위치를 옮겨 가며 읽어, 두 쪽을 동시에 꺼내면 서로의 자리를
 * 밟는다. 부르는 쪽(뷰어)이 줄을 세운다.
 */
class ComicPages(
    val names: List<String>,
    private val reader: (String) -> ByteArray?,
    private val resource: Closeable?,
) : Closeable {
    val count: Int get() = names.size

    /** [index] 쪽의 바이트. 없으면(항목이 사라짐) null. */
    fun read(index: Int): ByteArray? = names.getOrNull(index)?.let(reader)

    override fun close() {
        resource?.close()
    }
}
