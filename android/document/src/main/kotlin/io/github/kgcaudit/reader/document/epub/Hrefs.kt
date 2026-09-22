package io.github.kgcaudit.reader.document.epub

import java.io.ByteArrayOutputStream

/**
 * EPUB 안의 href 를 zip 엔트리 경로로 바꾼다.
 *
 * 조용히 틀리기 쉬운 자리다. 세 가지가 겹쳐 있다:
 *
 *  1. **상대 경로** — manifest 의 href 는 `content.opf` 가 있는 디렉터리 기준이고,
 *     챕터 안의 이미지·CSS href 는 그 챕터 파일 기준이다. `../` 가 흔하다.
 *  2. **퍼센트 인코딩** — href 는 URL 이라 공백이 `%20` 이고 한글이 `%ED%95%9C` 이다.
 *     zip 엔트리 이름은 **디코딩된 원문**이다. 디코딩을 빼먹으면 파일을 못 찾는데,
 *     증상이 "이 책만 이미지가 안 나온다"로 나타나 원인 찾기가 오래 걸린다.
 *  3. **프래그먼트** — 목차·각주 href 는 `ch1.xhtml#note3` 처럼 앵커를 달고 온다.
 *     파일을 열 때는 떼야 하고, 위치를 잡을 때는 그 앵커가 필요하다.
 */
internal object Hrefs {

    /** `#` 앞부분. 파일을 열 때 쓴다. */
    fun withoutFragment(href: String): String = href.substringBefore('#')

    /** `#` 뒷부분. 없으면 null. 목차·각주가 문서 안 특정 지점을 가리킬 때 쓴다. */
    fun fragment(href: String): String? =
        href.substringAfter('#', "").takeIf { it.isNotEmpty() }

    /**
     * [href] 를 [baseDir] 기준으로 풀어 정규화된 zip 엔트리 경로를 만든다.
     *
     * @param baseDir 참조하는 파일이 있는 **디렉터리**(파일 경로가 아니다). 루트면 빈 문자열.
     *   [dirOf] 로 얻는다.
     */
    fun resolve(baseDir: String, href: String): String {
        val target = decode(withoutFragment(href)).replace('\\', '/')

        // 절대 경로처럼 시작하면 zip 루트 기준으로 본다(zip 엔트리에는 선행 '/' 가 없다).
        val combined = if (target.startsWith("/")) {
            target.removePrefix("/")
        } else if (baseDir.isEmpty()) {
            target
        } else {
            "$baseDir/$target"
        }

        return normalize(combined)
    }

    /** 파일 경로에서 디렉터리 부분. 루트에 있으면 빈 문자열. */
    fun dirOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) "" else path.substring(0, slash)
    }

    /** `.` 과 `..` 을 걷어내고 중복 슬래시를 정리한다. */
    private fun normalize(path: String): String {
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * 퍼센트 인코딩을 UTF-8 로 디코딩한다.
     *
     * `java.net.URLDecoder` 를 쓰지 않는 이유: 그쪽은 `+` 를 공백으로 바꾼다. 그건
     * 질의 문자열 규칙이고 경로에는 틀리다 — 파일명에 든 `+` 가 사라져 역시 파일을
     * 못 찾게 된다.
     *
     * 잘못된 escape(`%ZZ`, 끝에서 잘린 `%A`)는 원문 그대로 남긴다. 버리면 경로가
     * 조용히 달라진다.
     */
    fun decode(text: String): String {
        if ('%' !in text) return text

        val out = StringBuilder(text.length)
        val bytes = ByteArrayOutputStream()
        var i = 0

        fun flushBytes() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }

        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            flushBytes()
            out.append(c)
            i++
        }
        flushBytes()
        return out.toString()
    }
}
