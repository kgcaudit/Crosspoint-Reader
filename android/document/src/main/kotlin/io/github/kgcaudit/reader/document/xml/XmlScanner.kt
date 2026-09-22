package io.github.kgcaudit.reader.document.xml

import java.io.Reader

/**
 * EPUB용 스트리밍 XML 스캐너.
 *
 * ### 왜 직접 쓰는가
 *
 * 안드로이드에는 `org.xmlpull.v1.XmlPullParser` 구현이 내장돼 있지만, JVM에는
 * **인터페이스만** 있다. 테스트에 kxml2를 끌어오면 안드로이드 내장 구현과 동작이
 * 미묘하게 달라서, JVM 골든 테스트가 실제로 기기에서 도는 코드를 검증하지 못한다.
 * 그건 이 프로젝트가 코어를 순수 Kotlin으로 유지하는 이유 자체를 무너뜨린다.
 * 직접 쓰면 두 곳에서 같은 코드가 돌고, 의존성이 0이며, 챕터 텍스트의 글자 오프셋을
 * (책갈피·진도의 좌표계다) 정확히 통제할 수 있다.
 *
 * ### 무엇을 하지 않는가
 *
 * 범용 XML 파서가 아니다. EPUB의 OPF·NCX·NAV·XHTML을 읽는 데 필요한 것만 한다:
 * 요소·속성·텍스트·CDATA·엔티티. 이름공간 선언 추적, DTD·내부 서브셋 해석, 유효성
 * 검증은 하지 않는다(주석·PI·DOCTYPE은 건너뛴다).
 *
 * ### 왜 관대한가
 *
 * 시중의 EPUB에는 깨진 마크업이 흔하다. 닫히지 않은 태그, 짝이 안 맞는 종료 태그,
 * 선언되지 않은 엔티티, 인용부호 없는 속성값. 엄격하게 굴면 "이 책이 안 열린다"가
 * 되므로, 복구 가능한 문제는 최대한 이어서 읽는다. 다만 조용히 글자를 버리지는
 * 않는다 — 해석 못 한 엔티티는 원문 그대로 흘려보낸다.
 */
class XmlScanner(reader: Reader) {

    private val scanner = CharScanner(reader)

    /**
     * 문서를 이벤트 흐름으로 읽는다. 한 번만 소비할 수 있다.
     *
     * 빈 요소(`<br/>`)는 start/end 를 연달아 낸다. 소비자가 자기닫힘을 따로 다룰 필요가 없다.
     */
    fun events(): Sequence<XmlEvent> = sequence {
        val text = StringBuilder()

        while (true) {
            val c = scanner.read()
            if (c < 0) break

            if (c != '<'.code) {
                if (c == '&'.code) appendReference(text) else text.append(c.toChar())
                continue
            }

            // 태그 시작. 모아 둔 텍스트를 먼저 낸다.
            if (text.isNotEmpty()) {
                yield(XmlEvent.Text(text.toString()))
                text.setLength(0)
            }

            when (scanner.peek()) {
                '/'.code -> {
                    scanner.read()
                    readName()?.let { name ->
                        skipUntil('>')
                        yield(XmlEvent.EndElement(name))
                    }
                }

                '!'.code -> {
                    scanner.read()
                    when {
                        scanner.consumeIfMatches("[CDATA[") -> text.append(readCdata())
                        scanner.consumeIfMatches("--") -> skipComment()
                        else -> skipDeclaration() // DOCTYPE 등
                    }
                }

                '?'.code -> {
                    scanner.read()
                    skipProcessingInstruction()
                }

                else -> {
                    val name = readName()
                    if (name == null) {
                        // '<' 다음에 이름이 아닌 것이 왔다. 태그가 아니라 본문의 '<' 로 본다
                        // (부등호를 이스케이프하지 않은 책이 있다).
                        text.append('<')
                    } else {
                        val attributes = readAttributes()
                        val selfClosing = scanner.consumeIfMatches("/")
                        skipUntil('>')
                        yield(XmlEvent.StartElement(name, attributes))
                        if (selfClosing) yield(XmlEvent.EndElement(name))
                    }
                }
            }
        }

        if (text.isNotEmpty()) yield(XmlEvent.Text(text.toString()))
    }

    // ── 토큰 읽기 ────────────────────────────────────────────────────

    /** 이름을 읽는다. 첫 글자가 이름에 쓸 수 없는 문자면 null(아무것도 소비하지 않음). */
    private fun readName(): XmlName? {
        if (!isNameStart(scanner.peek())) return null
        val sb = StringBuilder()
        while (isNameChar(scanner.peek())) sb.append(scanner.read().toChar())
        return XmlName.parse(sb.toString())
    }

    private fun readAttributes(): List<XmlAttribute> {
        var attributes: MutableList<XmlAttribute>? = null
        while (true) {
            skipWhitespace()
            val next = scanner.peek()
            if (next < 0 || next == '>'.code || next == '/'.code) break

            val name = readName()
            if (name == null) {
                // 속성 이름 자리에 쓰레기가 있다. 한 글자 버리고 계속 — 여기서 멈추면
                // 뒤에 오는 정상 속성(href 등)까지 잃는다.
                scanner.read()
                continue
            }

            skipWhitespace()
            val value = if (scanner.consumeIfMatches("=")) readAttributeValue() else ""
            (attributes ?: mutableListOf<XmlAttribute>().also { attributes = it })
                .add(XmlAttribute(name, value))
        }
        return attributes ?: emptyList()
    }

    private fun readAttributeValue(): String {
        skipWhitespace()
        val sb = StringBuilder()
        val quote = scanner.peek()
        if (quote == '"'.code || quote == '\''.code) {
            scanner.read()
            while (true) {
                val c = scanner.read()
                if (c < 0 || c == quote) break
                if (c == '&'.code) appendReference(sb) else sb.append(c.toChar())
            }
        } else {
            // 인용부호 없는 값. 공백이나 태그 끝까지 읽는다.
            while (true) {
                val c = scanner.peek()
                if (c < 0 || c == '>'.code || c == '/'.code || isWhitespace(c)) break
                scanner.read()
                if (c == '&'.code) appendReference(sb) else sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    /**
     * `&` 를 이미 읽은 상태에서 참조를 읽어 [out] 에 붙인다.
     *
     * 해석에 실패하면 읽은 바이트를 그대로 되돌려 붙인다 — 글자를 조용히 잃지 않는다.
     */
    private fun appendReference(out: StringBuilder) {
        val body = StringBuilder()
        while (true) {
            val c = scanner.peek()
            // ';' 가 나오기 전에 공백이나 '<' 가 오면 엔티티가 아니다(맨 '&' 로 본다).
            if (c < 0 || isWhitespace(c) || c == '<'.code || c == '&'.code) break
            scanner.read()
            if (c == ';'.code) {
                val resolved = XmlEntities.resolve(body.toString())
                if (resolved != null) out.append(resolved) else out.append('&').append(body).append(';')
                return
            }
            body.append(c.toChar())
            if (body.length > MAX_ENTITY_BODY) break
        }
        out.append('&').append(body)
    }

    private fun readCdata(): String {
        val sb = StringBuilder()
        while (true) {
            val c = scanner.read()
            if (c < 0) break
            if (c == ']'.code && scanner.consumeIfMatches("]>")) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    // ── 건너뛰기 ─────────────────────────────────────────────────────

    private fun skipComment() {
        while (true) {
            val c = scanner.read()
            if (c < 0) return
            if (c == '-'.code && scanner.consumeIfMatches("->")) return
        }
    }

    private fun skipProcessingInstruction() {
        while (true) {
            val c = scanner.read()
            if (c < 0) return
            if (c == '?'.code && scanner.consumeIfMatches(">")) return
        }
    }

    /**
     * DOCTYPE 등의 선언을 건너뛴다.
     *
     * 내부 서브셋 `[...]` 안에 `>` 가 들어 있을 수 있으므로 대괄호 깊이를 센다.
     * 이걸 빼먹으면 DTD를 가진 문서에서 본문 앞부분이 통째로 사라진다.
     */
    private fun skipDeclaration() {
        var bracketDepth = 0
        while (true) {
            val c = scanner.read()
            if (c < 0) return
            when (c) {
                '['.code -> bracketDepth++
                ']'.code -> if (bracketDepth > 0) bracketDepth--
                '>'.code -> if (bracketDepth == 0) return
            }
        }
    }

    private fun skipUntil(target: Char) {
        while (true) {
            val c = scanner.read()
            if (c < 0 || c == target.code) return
        }
    }

    private fun skipWhitespace() {
        while (isWhitespace(scanner.peek())) scanner.read()
    }

    private companion object {
        /** 엔티티 본문 길이 상한. 닫히지 않은 `&` 하나로 문서 끝까지 읽어버리지 않도록. */
        const val MAX_ENTITY_BODY = 32

        fun isWhitespace(c: Int): Boolean =
            c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code

        fun isNameStart(c: Int): Boolean =
            c == '_'.code || c == ':'.code || c.toChar().isLetter() || c > 0x7F

        fun isNameChar(c: Int): Boolean =
            isNameStart(c) || c == '-'.code || c == '.'.code || c.toChar().isDigit()
    }
}

/**
 * [Reader] 위의 한 글자 되돌리기가 가능한 버퍼 스캐너.
 *
 * 문서를 문자열로 통째로 올리지 않는 이유: EPUB 중에는 챕터 분할 없이 본문 전체가
 * 한 파일인 것이 있다. 10MB 파일이면 UTF-16 문자열로 20MB가 되고, 리더 상주 메모리
 * 예산(120MB)을 한 번에 갉아먹는다.
 */
private class CharScanner(private val reader: Reader) {
    private val buffer = CharArray(BUFFER_SIZE)
    private var length = 0
    private var position = 0

    fun read(): Int {
        if (!fill()) return -1
        return buffer[position++].code
    }

    fun peek(): Int {
        if (!fill()) return -1
        return buffer[position].code
    }

    /**
     * 다음 글자들이 [expected] 와 같으면 소비하고 true.
     *
     * 되돌리기를 피하려고, 경계를 넘는 비교가 필요할 때는 버퍼를 먼저 압축해
     * [expected] 전체가 한 버퍼 안에 들어오게 만든다.
     */
    fun consumeIfMatches(expected: String): Boolean {
        if (!ensureAvailable(expected.length)) return false
        for (i in expected.indices) {
            if (buffer[position + i] != expected[i]) return false
        }
        position += expected.length
        return true
    }

    private fun fill(): Boolean {
        if (position < length) return true
        length = reader.read(buffer, 0, buffer.size)
        position = 0
        if (length <= 0) {
            length = 0
            return false
        }
        return true
    }

    private fun ensureAvailable(count: Int): Boolean {
        if (length - position >= count) return true
        // 남은 부분을 앞으로 당기고 뒤를 채운다.
        val remaining = length - position
        if (remaining > 0) System.arraycopy(buffer, position, buffer, 0, remaining)
        length = remaining
        position = 0
        while (length < count) {
            val read = reader.read(buffer, length, buffer.size - length)
            if (read <= 0) break
            length += read
        }
        return length >= count
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
