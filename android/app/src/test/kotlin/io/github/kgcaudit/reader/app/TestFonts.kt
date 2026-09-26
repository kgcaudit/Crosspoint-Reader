package io.github.kgcaudit.reader.app

import java.io.File

/** :text-platform 의 테스트 폰트(리소스 `olo-test-fonts/`)를 꺼낸다. */
object TestFonts {
    fun copy(name: String, to: File) {
        val input = checkNotNull(TestFonts::class.java.getResourceAsStream("/olo-test-fonts/$name")) { "no resource $name" }
        input.use { i -> to.outputStream().use { i.copyTo(it) } }
    }
}
