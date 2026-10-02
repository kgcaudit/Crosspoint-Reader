package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.document.image.ImageHeader
import io.github.kgcaudit.reader.document.image.ImageSize
import java.io.Closeable
import java.io.InputStream

/**
 * 연 만화 한 권: 쪽 이름(자연 순서)과 쪽을 여는 길. 압축이면 열어 둔 파일을 쥐고 있어 [close] 해야 한다.
 *
 * 한 번에 하나씩만 부른다 — 압축 읽기는 한 파일 위치를 옮겨 가며 읽어, 두 쪽을 동시에 꺼내면 서로의 자리를 밟는다.
 * 부르는 쪽(뷰어)이 줄을 세운다.
 */
class ComicPages(
    val names: List<String>,
    private val opener: (String) -> InputStream?,
    private val resource: Closeable?,
) : Closeable {
    val count: Int get() = names.size

    /** [index] 쪽의 바이트. 없으면(항목이 사라짐) null. */
    fun read(index: Int): ByteArray? = names.getOrNull(index)?.let(opener)?.use { it.readBytes() }

    /**
     * [index] 쪽의 픽셀 크기 — 머리만 읽는다(웹툰 판별 · 기둥 배치). 그림이 아니거나 깨졌으면 null. 30MB 짜리 긴 그림도
     * 앞 몇 KB 만 읽으니 화 하나(그림 수십 장)를 여는 데 오래 걸리지 않는다.
     */
    fun size(index: Int): ImageSize? = try {
        names.getOrNull(index)?.let(opener)?.use { ImageHeader.read(it) }
    } catch (e: java.io.IOException) {
        null
    }

    override fun close() {
        resource?.close()
    }
}
