package io.github.kgcaudit.reader.document.pdf

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.utf16Literal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfStructureReaderTest {

    private fun read(bytes: ByteArray) = PdfStructureReader.read(SeekableSource.of(bytes)).outline

    /** 목차를 끝까지 읽은 뒤 파일 전체를 훑었는지. */
    private fun readThroughXref(bytes: ByteArray): Boolean {
        val file = PdfFile.open(SeekableSource.of(bytes))!!
        val outlines = file.resolve(file.root!!["Outlines"]) as PdfDict
        var item = file.resolve(outlines["First"]) as? PdfDict
        while (item != null) {
            (item["Dest"] as? PdfArray)?.items?.firstOrNull()?.let(file::resolve)
            item = file.resolve(item["Next"]) as? PdfDict
        }
        return file.scannedWholeFile
    }

    private fun List<PdfOutlineItem>.summary() = map { "${"  ".repeat(it.depth)}${it.pageIndex}:${it.label}" }

    /** 1 = Catalog, 2 = Outlines, 10… = 쪽 나무, 50… = 목차 항목. */
    private fun book(block: TestPdf.(pages: List<Int>) -> Unit, catalogExtra: String = ""): TestPdf = TestPdf().apply {
        val pages = pages(10, 8)
        obj(1, "<< /Type /Catalog /Pages 10 0 R /Outlines 2 0 R $catalogExtra >>")
        block(pages)
    }

    @Test
    fun `chapters point at their pages in reading order, with sub entries indented`() {
        // 쪽 나무가 두 단이어도 쪽 번호는 읽는 순서다. 나무의 객체 번호 순서로 세면 뒤 절반이 틀린다.
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R /Last 52 0 R /Count 3 >>")
            obj(50, "<< /Title ${utf16("1장 어린 새")} /Parent 2 0 R /Next 51 0 R /Dest [${p[1]} 0 R /XYZ 0 800 0] >>")
            obj(51, "<< /Title ${utf16("2장 검은 숨")} /Parent 2 0 R /Prev 50 0 R /Next 52 0 R /First 53 0 R /Last 53 0 R /Dest [${p[4]} 0 R /Fit] >>")
            obj(53, "<< /Title ${utf16("2-1 새벽")} /Parent 51 0 R /Dest [${p[6]} 0 R /Fit] >>")
            obj(52, "<< /Title (Epilogue) /Parent 2 0 R /Prev 51 0 R /Dest [${p[7]} 0 R /Fit] >>")
        }).classic()

        assertEquals(listOf("1:1장 어린 새", "4:2장 검은 숨", "  6:2-1 새벽", "7:Epilogue"), read(pdf).summary())
    }

    @Test
    fun `korean titles with escaped bytes decode exactly`() {
        // 실제 책의 증상: '절'(U+C808)·'눈'(U+B208)의 0x08 바이트는 `\b` 로 적힌다. 이걸 'b' 로 읽으면
        // "거절" 이 "거졢" 이 된다. 괄호·역슬래시·8진수 이스케이프도 같이 본다.
        val titles = listOf("깔끔하게 거절하고 싶었지만", "5장 밤의 눈동자", "(괄호) 와 \\ 역슬래시", "줄\n바꿈")
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            titles.forEachIndexed { i, t ->
                val next = if (i < titles.lastIndex) "/Next ${51 + i} 0 R" else ""
                raw(
                    50 + i,
                    "<< /Title ".toByteArray() + utf16Literal(t) + " $next /Dest [${p[i]} 0 R /Fit] >>".toByteArray(),
                )
            }
        }).classic()

        // 제목 속 줄바꿈은 빈칸 하나가 된다 — 목록 한 줄에 두 줄이 겹쳐 그려지지 않게.
        assertEquals(listOf("깔끔하게 거절하고 싶었지만", "5장 밤의 눈동자", "(괄호) 와 \\ 역슬래시", "줄 바꿈"), read(pdf).map { it.label })
    }

    @Test
    fun `named destinations resolve through the dests dictionary and the name tree`() {
        // 소년이 온다 PDF 가 이 모양이다: 목차 항목은 이름만 적고, 이름 → 쪽 은 따로 둔다.
        val pdf = book(
            { p ->
                obj(2, "<< /Type /Outlines /First 50 0 R >>")
                obj(50, "<< /Title (Name) /Next 51 0 R /Dest /a2 >>")
                obj(51, "<< /Title (Tree) /Next 52 0 R /Dest (chap.3) >>")
                obj(52, "<< /Title (Action) /Next 53 0 R /A << /S /GoTo /D (chap.5) >> >>")
                obj(53, "<< /Title (Web) /A << /S /URI /URI (https://example.com) >> >>")
                obj(60, "<< /a2 [${p[2]} 0 R /XYZ 76.5 699.75 0] >>")
                // 이름 나무: 뿌리 → 잎 두 개(/Kids), 값 하나는 `<< /D [...] >>` 사전.
                obj(61, "<< /Kids [62 0 R 63 0 R] >>")
                obj(62, "<< /Limits [(chap.1) (chap.3)] /Names [(chap.1) [${p[0]} 0 R /Fit] (chap.3) 64 0 R] >>")
                obj(63, "<< /Limits [(chap.5) (chap.5)] /Names [(chap.5) << /D [${p[5]} 0 R /Fit] >>] >>")
                obj(64, "<< /D [${p[3]} 0 R /Fit] >>")
            },
            catalogExtra = "/Dests 60 0 R /Names << /Dests 61 0 R >>",
        ).classic()

        // 웹 링크 항목은 갈 쪽이 없어 빠진다.
        assertEquals(listOf("2:Name", "3:Tree", "5:Action"), read(pdf).summary())
    }

    @Test
    fun `a part heading without its own page opens its first chapter`() {
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title ${utf16("제1부")} /First 51 0 R /Next 52 0 R >>")
            obj(51, "<< /Title (One) /Dest [${p[3]} 0 R /Fit] >>")
            // 없는 쪽을 가리키는 항목과 목적지가 아예 없는 항목은 누를 수 없으니 뺀다.
            obj(52, "<< /Title (Ghost) /Next 54 0 R /Dest [999 0 R /Fit] >>")
            obj(54, "<< /Title (Nowhere) /Next 55 0 R >>")
            // 최상위를 하나 더 둔다 — 하나만 남으면 "감싸는 항목" 으로 보고 한 단계 올린다(다른 시험).
            obj(55, "<< /Title (Epilogue) /Dest [${p[7]} 0 R /Fit] >>")
        }).classic()

        assertEquals(listOf("3:제1부", "  3:One", "7:Epilogue"), read(pdf).summary())
        // 끊긴 참조 하나 때문에 파일 전체를 훑지 않는다(큰 파일은 몇 초가 걸린다).
        assertFalse(readThroughXref(pdf))
    }

    @Test
    fun `modern files with object streams and a compressed cross reference read the same`() {
        // PDF 1.5 이후 대부분의 파일: 목차 항목이 객체 스트림 안에 있고 상호 참조는 예측자를 건 스트림이다.
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title ${utf16("차례")} /Next 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
            obj(51, "<< /Title ${utf16("1장")} /Dest [${p[2]} 0 R /Fit] >>")
        }).modern(inStream = setOf(1, 2, 10, 11, 12, 50, 51))

        assertEquals(listOf("0:차례", "2:1장"), read(pdf).summary())
        // 압축된 상호 참조를 제대로 풀어서 읽었다. 풀지 못해도 훑어서 되살리므로 결과만 봐서는 모른다.
        assertFalse(readThroughXref(pdf))
    }

    @Test
    fun `an edited file shows the newest titles`() {
        // 덧붙여 고친 파일(ExifTool 등): 새 판의 상호 참조가 옛 판을 /Prev 로 잇는다. 옛 제목이 나오면 안 된다.
        val base = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (Old title) /Dest [${p[1]} 0 R /Fit] >>")
        }).classic()
        // "startxref" 안의 xref 가 아니라 줄 머리의 xref.
        val prev = String(base, Charsets.ISO_8859_1).lastIndexOf("\nxref\n") + 1
        val update = StringBuilder()
        val objAt = base.size
        update.append("50 0 obj\n<< /Title (New title) /Dest [${10 + 3 + 1} 0 R /Fit] >>\nendobj\n")
        val xrefAt = objAt + update.length
        update.append("xref\n50 1\n%010d 00000 n \ntrailer\n<< /Size 51 /Root 1 0 R /Prev $prev >>\nstartxref\n$xrefAt\n%%EOF\n".format(objAt))
        val edited = base + update.toString().toByteArray(Charsets.ISO_8859_1)

        assertEquals(listOf("1:New title"), read(edited).summary())
        // 새 판의 상호 참조와 /Prev 로 읽었다(훑어서 우연히 맞힌 것이 아니다).
        assertFalse(readThroughXref(edited))
    }

    @Test
    fun `a file with a broken cross reference is recovered by scanning`() {
        // 상호 참조의 위치가 틀렸거나(내려받다 잘림, 잘못 고친 파일) 아예 없어도 목차는 나온다.
        val shape: TestPdf.(List<Int>) -> Unit = { p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (Recovered) /Dest [${p[5]} 0 R /Fit] >>")
        }
        assertEquals(listOf("5:Recovered"), read(book(shape).classic(shift = 7)).summary())
        assertEquals(listOf("5:Recovered"), read(book(shape).classic(writeXref = false)).summary())
    }

    @Test
    fun `the library shows the title and author written in the file`() {
        // 파일 이름("[한강] 소년이 온다.pdf")보다 문서 정보의 제목·저자가 낫다. '한'(U+D55C)의 0x5C 는
        // 역슬래시라 `\\` 로 적힌다 — 이스케이프를 잘못 풀면 저자가 "핂강" 같은 글자가 된다.
        fun info(title: ByteArray, author: ByteArray?) = TestPdf().apply {
            pages(10, 1)
            obj(1, "<< /Type /Catalog /Pages 10 0 R >>")
            raw(
                30,
                "<< /Title ".toByteArray() + title +
                    (author?.let { " /Author ".toByteArray() + it } ?: ByteArray(0)) + " /Creator (calibre) >>".toByteArray(),
            )
        }.classic(trailerExtra = "/Info 30 0 R")

        val book = PdfStructureReader.read(SeekableSource.of(info(utf16Literal("소년이 온다"), utf16Literal("한강"))))
        assertEquals("소년이 온다", book.title)
        assertEquals("한강", book.author)

        // 만든 프로그램이 채운 제목은 다듬거나 버린다(→ 앱은 파일 이름을 쓴다).
        assertEquals("원고", PdfStructureReader.read(SeekableSource.of(info("(Microsoft Word - 원고.docx)".utf8(), null))).title)
        assertEquals(null, PdfStructureReader.read(SeekableSource.of(info("(Untitled)".utf8(), null))).title)
        assertEquals(null, PdfStructureReader.read(SeekableSource.of(info("()".utf8(), null))).title)
    }

    private fun String.utf8() = toByteArray(Charsets.UTF_8)

    @Test
    fun `a sole wrapping entry does not push the whole contents one step in`() {
        // 두 잡지(씨네21 · 좋은생각)의 모양: "목차" 하나 아래에 모든 기사가 있다. 그대로면 목록 전체가 한 칸
        // 들여 써진다. 감싸던 항목은 첫 줄로 남기고(목차 쪽으로 가는 길) 나머지를 한 단계 올린다.
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title ${utf16("목차")} /First 51 0 R /Dest [${p[1]} 0 R /Fit] >>")
            obj(51, "<< /Title ${utf16("NEWS")} /Next 53 0 R /First 52 0 R /Dest [${p[2]} 0 R /Fit] >>")
            obj(52, "<< /Title ${utf16("국내뉴스")} /Dest [${p[2]} 0 R /Fit] >>")
            obj(53, "<< /Title ${utf16("REVIEW｜<오디세이>")} /Dest [${p[5]} 0 R /Fit] >>")
        }).classic()
        assertEquals(listOf("1:목차", "2:NEWS", "  2:국내뉴스", "5:REVIEW｜<오디세이>"), read(pdf).summary())

        // 최상위가 둘 이상이면 손대지 않는다.
        val two = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (A) /Next 52 0 R /First 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
            obj(51, "<< /Title (A-1) /Dest [${p[1]} 0 R /Fit] >>")
            obj(52, "<< /Title (B) /Dest [${p[2]} 0 R /Fit] >>")
        }).classic()
        assertEquals(listOf("0:A", "  1:A-1", "2:B"), read(two).summary())
    }

    @Test
    fun `korean written as code point tags reads as korean and account names are not authors`() {
        // 실제 잡지의 문서 정보: 제목 "2608 <C88B><C740><C0DD><AC01>", 저자 "USER". 조판 프로그램이 한글을
        // 번호로 적었고, 저자 자리엔 컴퓨터 계정 이름이 들어갔다. 진짜 꺾쇠(<동궁>)와 한글 밖의 번호는 그대로.
        val pdf = TestPdf().apply {
            pages(10, 1)
            obj(1, "<< /Type /Catalog /Pages 10 0 R >>")
            raw(30, "<< /Title (2608 <C88B><C740><C0DD><AC01> <ABCD> <동궁>) /Author (USER) >>".utf8())
        }.classic(trailerExtra = "/Info 30 0 R")
        val book = PdfStructureReader.read(SeekableSource.of(pdf))
        assertEquals("2608 좋은생각 <ABCD> <동궁>", book.title)
        assertEquals(null, book.author)
    }

    @Test
    fun `page labels give the numbers printed in the book`() {
        // 머리말 i–iv, 본문 1–, 부록 "A-1"… 목차의 쪽 번호가 종이책 목차와 같아야 찾아갈 수 있다.
        val labelled = book(
            { p ->
                obj(2, "<< /Type /Outlines /First 50 0 R >>")
                obj(50, "<< /Title (One) /Dest [${p[4]} 0 R /Fit] >>")
                // 번호 나무: 뿌리 → 잎 둘(/Kids).
                obj(70, "<< /Kids [71 0 R 72 0 R] >>")
                obj(71, "<< /Nums [0 << /S /r >> 4 << /S /D >>] >>")
                obj(72, "<< /Nums [6 << /S /D /P (A-) /St 1 >> 7 << /P ${utf16("표지")} >>] >>")
            },
            catalogExtra = "/PageLabels 70 0 R",
        ).classic()
        val labels = PdfStructureReader.read(SeekableSource.of(labelled)).pageLabels!!
        assertEquals(listOf("i", "ii", "iii", "iv", "1", "2", "A-1", "표지"), (0 until 8).map(labels::label))

        // 1부터 세는 이름표(두 잡지가 이렇다)는 파일 순서와 같아 따로 보이지 않는다(null).
        val plain = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (One) /Dest [${p[0]} 0 R /Fit] >>")
        }, catalogExtra = "/PageLabels << /Nums [0 << /S /D >>] >>").classic()
        assertEquals(null, PdfStructureReader.read(SeekableSource.of(plain)).pageLabels)
    }

    @Test
    fun `a looping outline is read once instead of forever`() {
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (A) /Next 51 0 R /First 50 0 R /Dest [${p[0]} 0 R /Fit] >>")
            obj(51, "<< /Title (B) /Next 50 0 R /Dest [${p[1]} 0 R /Fit] >>")
        }).classic()

        assertEquals(listOf("0:A", "1:B"), read(pdf).summary())
    }

    @Test
    fun `titles written without a byte order mark still read as korean`() {
        // BOM 없이 UTF-8 이나 CP949 바이트를 넣는 변환기가 있다. 명세대로 읽으면 "ì„œì " 같은 글자가 된다.
        val cp949 = "제2장 서점".toByteArray(charset("MS949"))
        val utf8 = "제3장 커피".toByteArray(Charsets.UTF_8)
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (Caf\\351 menu) /Next 51 0 R /Dest [${p[0]} 0 R /Fit] >>")
            raw(51, "<< /Title (".toByteArray() + cp949 + ") /Next 52 0 R /Dest [${p[1]} 0 R /Fit] >>".toByteArray())
            raw(52, "<< /Title (".toByteArray() + utf8 + ") /Dest [${p[2]} 0 R /Fit] >>".toByteArray())
        }).classic()

        // 라틴 악센트(PDFDocEncoding)는 명세대로 é.
        assertEquals(listOf("Café menu", "제2장 서점", "제3장 커피"), read(pdf).map { it.label })
    }

    @Test
    fun `an encrypted file gives no contents instead of garbled titles`() {
        val pdf = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title <8A7F3301> /Dest [${p[0]} 0 R /Fit] >>")
            obj(70, "<< /Filter /Standard /V 2 /R 3 /O <00> /U <00> /P -4 >>")
        }).classic(trailerExtra = "/Encrypt 70 0 R")

        assertTrue(read(pdf).isEmpty())
    }

    @Test
    fun `files without contents or that are not pdfs give an empty list`() {
        val noOutline = TestPdf().apply {
            pages(10, 2)
            obj(1, "<< /Type /Catalog /Pages 10 0 R >>")
        }.classic()
        assertTrue(read(noOutline).isEmpty())
        assertTrue(read(ByteArray(0)).isEmpty())
        assertTrue(read("not a pdf at all".toByteArray()).isEmpty())
        // 아무 바이트나 — 예외 없이 빈 목록이어야 한다.
        val noise = ByteArray(20_000) { (it * 7919 % 251).toByte() }
        assertTrue(read(noise).isEmpty())
        // 목차 도중에 잘린 파일은 읽은 데까지.
        val whole = book({ p ->
            obj(2, "<< /Type /Outlines /First 50 0 R >>")
            obj(50, "<< /Title (Kept) /Dest [${p[0]} 0 R /Fit] >>")
        }).classic()
        read(whole.copyOf(whole.size / 2))
    }
}
