@file:Suppress("NO_ACTUAL_FOR_EXPECT")

package io.github.kgcaudit.reader.document.io

/**
 * 공통 코드가 쓰는 바이트 · 글자 흐름. JVM(안드로이드)에서는 `java.io` 의 같은 이름 클래스 그 자체다(actual typealias) — 그래서
 * 안드로이드 모듈은 `java.io.InputStream` 을 그대로 주고받고 한 줄도 바뀌지 않는다. iOS 는 iosMain 에서 같은 모양으로 채운다.
 *
 * 왜 kotlinx-io · Okio 를 들이지 않는가: 공개 API(`ReflowDocument.openChapter` · `ByteSource`)가 이미 java.io 모양이고
 * 안드로이드 모듈 수십 곳이 그 모양으로 쓴다. 다른 흐름 타입을 들이면 그 모두를 바꿔야 하고, 바꾸는 동안 두 앱이 갈라진다.
 * 여기 적은 멤버만 공통 코드에서 쓸 수 있다 — 더 필요하면 여기와 iosMain 에 함께 더한다.
 */
expect abstract class InputStream() : AutoCloseable {
    abstract fun read(): Int
    open fun read(b: ByteArray): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int
    open fun skip(n: Long): Long
    open fun available(): Int
    open fun markSupported(): Boolean
    open fun mark(readlimit: Int)
    open fun reset()
    override fun close()
}

expect abstract class Reader : AutoCloseable {
    protected constructor()
    abstract fun read(cbuf: CharArray, off: Int, len: Int): Int
    open fun read(): Int
    open fun read(cbuf: CharArray): Int
    open fun skip(n: Long): Long
    abstract override fun close()
}

expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

expect open class EOFException : IOException {
    constructor()
    constructor(message: String?)
}

/** 닫을 수 있는 것. JVM 에서는 `java.io.Closeable` 그 자체 — 안드로이드 쪽이 이 타입으로 받는 곳이 있다. */
expect interface Closeable : AutoCloseable {
    override fun close()
}
