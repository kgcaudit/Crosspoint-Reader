package io.github.kgcaudit.reader.document

import java.io.InputStream

/**
 * [buffer] 가 찰 때까지(또는 스트림이 끝날 때까지) 읽고 읽은 바이트 수를 돌려준다.
 *
 * `read(buffer)` 한 번은 **요청보다 적게** 줄 수 있다(압축 해제 스트림·파이프). 한 번만 부르면 파일
 * 머리를 반쯤만 읽고 "모르는 형식" 으로 판단하게 된다.
 */
internal fun InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        // 0 도 끝으로 본다. 계약 위반이지만 그런 스트림이 있고, 그러면 여기서 영원히 돈다.
        if (n <= 0) break
        total += n
    }
    return total
}

/** 최대 [limit] 바이트. 스트림이 짧으면 있는 만큼만(복사본). */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    val read = readUpTo(buffer)
    return if (read == limit) buffer else buffer.copyOf(read)
}
