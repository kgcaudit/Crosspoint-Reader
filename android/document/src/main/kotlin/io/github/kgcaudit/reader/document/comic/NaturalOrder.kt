package io.github.kgcaudit.reader.document.comic

/**
 * 숫자를 값으로 비교하는 이름 순서: "2권" 이 "10권" 앞에 온다.
 *
 * 왜: 만화 파일 이름은 숫자를 0 으로 채우지 않는 일이 흔하다("1, 10, 2"). 글자 순서로 늘어놓으면 10권이 2권 앞에
 * 오고, 압축 안의 쪽도 "1.jpg, 10.jpg, 2.jpg" 로 뒤섞인다. 대소문자는 가리지 않는다. 한글은 코드 순서가 곧
 * 가나다 순이라 따로 다루지 않는다.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val ea = digitsEnd(a, i)
                val eb = digitsEnd(b, j)
                // 앞의 0 을 떼고 길이 → 글자 순으로 견준다. Long 으로 바꾸지 않는 까닭: 스무 자리 숫자도 넘치지 않게.
                val na = a.substring(i, ea).trimStart('0')
                val nb = b.substring(j, eb).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                // 값이 같으면 0 을 더 붙인 쪽을 뒤로("01" 은 "1" 뒤) — 같은 값의 두 이름도 늘 같은 순서가 되게.
                val zeros = (ea - i) - (eb - j)
                if (zeros != 0) return zeros
                i = ea
                j = eb
                continue
            }
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
        val rest = (a.length - i) - (b.length - j)
        // 대소문자만 다른 두 이름은 원래 글자로 가른다 — 정렬이 늘 같은 결과를 내도록.
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k] in '0'..'9') k++
        return k
    }
}
