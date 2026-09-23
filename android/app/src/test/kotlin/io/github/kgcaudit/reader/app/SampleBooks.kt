package io.github.kgcaudit.reader.app

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 화면을 확인할 견본 책. 한글·라틴·숫자·제목·목차가 섞여 있다. */
object SampleBooks {

    private val paragraphs = listOf(
        "여섯 살 적에 나는 「체험한 이야기」라는 제목의, 원시림에 관한 책에서 기막힌 그림 하나를 본 적이 있다. 맹수를 삼키고 있는 보아 구렁이 그림이었다.",
        "그 책에는 이렇게 씌어 있었다. \"보아 구렁이는 먹이를 씹지도 않고 통째로 삼킨다. 그리고는 꼼짝도 못하고 여섯 달 동안 잠을 자면서 그것을 소화시킨다.\"",
        "나는 그래서 밀림 속에서의 모험에 대해 한참 생각해 보고 난 끝에 색연필을 가지고 나름대로 내 생애 첫 번째 그림을 그려 보았다. 나의 그림 제1호였다.",
        "나는 그 걸작품을 어른들에게 보여 주고 내 그림이 무섭지 않느냐고 물었다. 어른들은 \"모자가 뭐가 무섭다는 거니?\" 하고 대답했다.",
        "In 1943, the little prince asked for a sheep — and the pilot, who had given up drawing at the age of six, drew a box instead.",
    )

    private fun chapter(title: String, count: Int) = buildString {
        append("<html><body><h1>").append(title).append("</h1>")
        repeat(count) { i -> append("<p>").append(paragraphs[i % paragraphs.size]).append("</p>") }
        append("</body></html>")
    }

    fun epub(): ByteArray {
        val chapters = listOf("제1장 보아 구렁이", "제2장 사막의 아침", "제3장 장미 한 송이")
        val entries = linkedMapOf(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>어린 왕자</dc:title><dc:creator>생텍쥐페리</dc:creator><dc:language>ko</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to buildString {
                append("<html><body><nav epub:type=\"toc\"><ol>")
                chapters.forEachIndexed { i, t -> append("<li><a href=\"c${i + 1}.xhtml\">$t</a></li>") }
                append("</ol></nav></body></html>")
            },
        )
        chapters.forEachIndexed { i, t -> entries["OEBPS/c${i + 1}.xhtml"] = chapter(t, 14) }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            entries.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()) }
        }
        return out.toByteArray()
    }

    /** 옛 한글 TXT 는 EUC-KR 이 흔하다. */
    fun txt(): ByteArray = (1..40).joinToString("\n") { "$it. " + paragraphs[it % paragraphs.size] }
        .toByteArray(charset("EUC-KR"))
}
