package io.github.kgcaudit.reader.document.io

/**
 * zlib · raw deflate 풀기. JVM 은 `java.util.zip.Inflater` 그 자체, iOS 는 시스템 zlib 로 채운다 — 압축 풀기를 손으로 쓰면
 * 속도 · 깨진 입력 처리에서 두 앱이 달라진다. [nowrap] 이 true 면 zlib 머리 없는 raw deflate(zip 항목).
 */
expect open class Inflater {
    constructor()
    constructor(nowrap: Boolean)
    fun setInput(input: ByteArray, off: Int, len: Int)
    fun setInput(input: ByteArray)
    fun inflate(output: ByteArray, off: Int, len: Int): Int
    fun inflate(output: ByteArray): Int
    fun finished(): Boolean
    fun needsInput(): Boolean
    fun needsDictionary(): Boolean
    fun getRemaining(): Int
    fun end()
}

/** 압축 자료가 깨졌다([Inflater.inflate]). */
expect open class DataFormatException : Exception {
    constructor()
    constructor(message: String?)
}

/**
 * 이름으로 고르는 문자 인코딩(EUC-KR · CP949 · Shift_JIS …). UTF-8 · UTF-16 · Latin-1 · ASCII 는 [Text] 가 순수 Kotlin 으로 푼다 —
 * 그쪽은 두 플랫폼이 똑같아야 하는 곳(PDF · zip 이름)이라 플랫폼에 맡기지 않는다.
 */
expect object NamedCharsets {
    /** [name] 을 이 플랫폼이 아는가. 별칭(`x-windows-949` · `MS949`)도 받는다. */
    fun isSupported(name: String): Boolean

    /** 풀 수 없는 바이트는 대체 문자(U+FFFD)로. 모르는 인코딩이면 null. */
    fun decode(bytes: ByteArray, off: Int, len: Int, name: String): String?

    /** 풀 수 없는 바이트가 하나라도 있으면 null(추측이 맞았는지 볼 때). 모르는 인코딩이어도 null. */
    fun decodeStrict(bytes: ByteArray, name: String): String?

    /** [input] 을 [name] 으로 읽는 글자 흐름. 풀 수 없는 바이트는 대체 문자로. 모르는 인코딩이면 null. */
    fun reader(input: InputStream, name: String): Reader?
}

/** 해시 · AES. PDF 암호 풀기와 EPUB 글꼴 난독화 풀기에만 쓴다. iOS 는 CommonCrypto 로 채운다. */
expect object Crypto {
    /** [algorithm] 은 "MD5" · "SHA-1" · "SHA-256" · "SHA-384" · "SHA-512". */
    fun digest(algorithm: String, data: ByteArray): ByteArray

    /** AES-CBC, 덧대기 없음. [data] 길이는 16 의 배수여야 한다. */
    fun aesCbcNoPadding(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
}

/** 유니코드 정규화. 만화 파일 이름(맥의 NFD · 전각 숫자)을 맞출 때 쓴다. */
expect object Unicode {
    fun nfc(text: String): String
    fun nfkc(text: String): String
}

/**
 * 흐름 제어용 예외(창 끝 · 다시 읽기). 자주 던지므로 JVM 에서는 스택을 기록하지 않는다 — 기록하면 큰 PDF 를 여는 동안
 * 수천 번 스택을 떠서 느려진다.
 */
expect abstract class ControlFlowException() : RuntimeException

/** 지금 시각(1970년부터 밀리초). 읽은 때 · 책갈피 시각에 쓴다. */
expect fun currentTimeMillis(): Long
