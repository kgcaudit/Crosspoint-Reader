package io.github.kgcaudit.reader.document

import java.io.InputStream

/**
 * 바이트를 여러 번 다시 읽을 수 있는 원천.
 *
 * 저장소를 추상화하는 이유: 앱에서는 SAF `Uri`가, 테스트에서는 바이트 배열이 원천이
 * 된다. 이 인터페이스 덕분에 파서와 문서 구현이 순수 Kotlin으로 남고 기기 없이
 * 테스트된다.
 *
 * 구현은 [openStream]을 몇 번이든 호출할 수 있어야 한다(인코딩 감지 후 본문 재읽기,
 * 재조판 등에서 다시 연다).
 */
fun interface ByteSource {
    fun openStream(): InputStream

    companion object {
        /** 테스트·소용량 데이터용 인메모리 원천. */
        fun of(bytes: ByteArray): ByteSource = ByteSource { bytes.inputStream() }
    }
}
