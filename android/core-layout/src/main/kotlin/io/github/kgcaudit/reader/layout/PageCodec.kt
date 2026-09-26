package io.github.kgcaudit.reader.layout

/**
 * 챕터 하나의 조판 캐시. 세 덩이로 나뉜다.
 *
 * 나눈 이유는 접근 패턴이 다르기 때문이다. [runs] 는 수가 많고 페이지 하나만 골라
 * 읽어야 하므로 **고정 길이 레코드**로 두어 색인에서 바로 잘라 온다. 그림과 구분선은
 * 챕터당 몇 개뿐이고 이름 길이가 제각각이라, 한 번에 읽는 가변 길이 덩이가 낫다.
 * 텍스트는 파일 하나에 따로 둔다 — 조판·책갈피 조각·검색·낭독이 모두 이 좌표계를 쓴다.
 */
data class EncodedChapter(
    val index: ByteArray,
    val runs: ByteArray,
    val objects: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedChapter &&
            index.contentEquals(other.index) &&
            runs.contentEquals(other.runs) &&
            objects.contentEquals(other.objects)

    override fun hashCode(): Int =
        (index.contentHashCode() * 31 + runs.contentHashCode()) * 31 + objects.contentHashCode()
}

/** 캐시 머리말. 페이지를 읽기 전에 이것만 보고 유효성과 개수를 판단한다. */
data class ChapterIndex(
    val version: Int,
    val pageCount: Int,
    /** 조판이 끝까지 진행됐는지. false 면 부분 캐시이고 뒤쪽은 다시 조판해야 한다. */
    val complete: Boolean,
    /** 이 캐시를 만들 때의 텍스트 길이. `.txt` 와 다르면 짝이 맞지 않는 캐시다. */
    val textLength: Int,
    /**
     * 그 텍스트의 UTF-8 바이트 수(`.txt` 파일 크기). 모르면 -1.
     *
     * 짝 맞추기를 파일 크기로 한다. 글자 수로 하려면 `.txt` 를 통째로 읽어 풀어야 하는데, 한 번 넘길
     * 때마다 여러 번 불려 7MB 짜리 TXT 한 챕터면 넘길 때마다 수십 MB 를 읽었다.
     */
    val textBytes: Long = -1,
)

/**
 * [Page] ↔ 바이트 변환.
 *
 * 직렬화 라이브러리를 쓰지 않고 손으로 쓰는 이유: 이 경로가 페이지 넘김의 핵심이다.
 * 고정 길이 레코드면 "페이지 N 읽기" 가 색인에서 24바이트를 읽고 런 배열을 잘라 오는
 * 일로 끝나고, 파싱이 아예 없다. 일반 직렬화는 그 자리에 객체 그래프 복원을 넣는다.
 *
 * 모든 정수는 **리틀엔디안**이고 실수는 IEEE 754 비트 패턴이다. 플랫폼에 무관하게
 * 같은 바이트가 나오므로, JVM 에서 만든 캐시를 기기가 읽어도 된다(테스트가 그 성질에
 * 기댄다).
 */
object PageCodec {

    /**
     * 판 번호. **조판 결과가 바뀌는 수정을 하면 올린다.**
     *
     * 캐시 키는 설정만 본다. 조판 규칙이 바뀌어도 설정이 같으면 같은 키라서, 올리지 않으면
     * 업데이트 뒤에도 옛 규칙으로 만든 페이지가 그대로 보인다. 올리면 옛 캐시는 "다른 판"
     * 으로 읽히지 않아 자동으로 다시 조판된다.
     *
     * 2: 그림 크기(파일 크기·CSS·퍼센트 반영, 비율 유지).
     * 3: 런 레코드의 예약 16비트에 책 글꼴 번호([TextStyle.face]).
     * 4: 머리말 끝(예약 u32)에 텍스트 바이트 수. 페이지 수는 색인 크기로 센다(u16 칸의 65,535 쪽 한계를 없앰).
     */
    const val VERSION: Int = 4

    private const val MAGIC = 0x31505043 // "CPP1" 리틀엔디안
    private const val HEADER_SIZE = 32
    private const val PAGE_ENTRY_SIZE = 24
    private const val RUN_SIZE = 24

    /** 페이지 엔트리 안에서 startChar 가 놓인 위치. */
    private const val START_CHAR_OFFSET = 12

    private const val OBJECT_IMAGE: Byte = 1
    private const val OBJECT_RULE: Byte = 2

    private const val FLAG_BOLD = 1 shl 0
    private const val FLAG_ITALIC = 1 shl 1
    private const val FLAG_UNDERLINE = 1 shl 2
    private const val FLAG_STRIKETHROUGH = 1 shl 3
    private const val FLAG_SUPERSCRIPT = 1 shl 4
    private const val FLAG_SUBSCRIPT = 1 shl 5

    // ── 쓰기 ────────────────────────────────────────────────────────

    /**
     * @param complete 조판이 끝까지 갔는지. 중간에 멈춘 결과를 저장할 때 false 로 둔다. 부분 캐시는
     *   다음에 열 때 **처음부터 다시** 조판한다(이어서 조판하는 길은 아직 없다).
     */
    fun encode(pages: List<Page>, textLength: Int, complete: Boolean = true, textBytes: Long = -1): EncodedChapter {
        val runs = ByteWriter(pages.sumOf { it.runs.size } * RUN_SIZE)
        val objects = ByteWriter(256)
        val index = ByteWriter(HEADER_SIZE + pages.size * PAGE_ENTRY_SIZE)

        var runCursor = 0
        var imageCursor = 0
        var ruleCursor = 0

        index.skip(HEADER_SIZE) // 머리말은 개수가 확정된 뒤에 채운다

        for (page in pages) {
            index.putU32(runCursor.toLong())
            index.putU16(page.runs.size)
            index.putU16(imageCursor)
            index.putU8(page.images.size)
            index.putU8(page.rules.size)
            index.putU16(ruleCursor)
            index.putU32(page.startChar.toLong())
            index.putU32(page.endCharExclusive.toLong())
            index.putU32(0) // 예약

            page.runs.forEach { run ->
                runs.putU32(run.start.toLong())
                runs.putU32(run.endExclusive.toLong())
                runs.putF32(run.xPx)
                runs.putF32(run.baselineYPx)
                runs.putF32(run.style.sizeScale)
                runs.putU8(styleFlags(run.style))
                runs.putU8(0)
                runs.putU16(run.style.face.coerceIn(0, 0xFFFF))
            }
            page.images.forEach { image ->
                objects.putU8(OBJECT_IMAGE.toInt())
                objects.putString(image.href)
                objects.putF32(image.xPx)
                objects.putF32(image.yPx)
                objects.putF32(image.widthPx)
                objects.putF32(image.heightPx)
            }
            page.rules.forEach { rule ->
                objects.putU8(OBJECT_RULE.toInt())
                objects.putF32(rule.xPx)
                objects.putF32(rule.yPx)
                objects.putF32(rule.widthPx)
                objects.putF32(rule.thicknessPx)
            }

            runCursor += page.runs.size
            imageCursor += page.images.size
            ruleCursor += page.rules.size
        }

        val header = ByteWriter(HEADER_SIZE)
        header.putU32(MAGIC.toLong())
        header.putU16(VERSION)
        // 옛 칸. 읽을 때는 쓰지 않는다 — 한 챕터가 65,535 쪽을 넘으면(큰 글자의 20MB TXT) 넘쳐서 뒤쪽이
        // 사라진다. 페이지 수는 색인의 길이로 센다.
        header.putU16(pages.size.coerceAtMost(0xFFFF))
        header.putU8(if (complete) 1 else 0)
        header.putU8(0); header.putU16(0)
        header.putU32(runCursor.toLong())
        header.putU32(imageCursor.toLong())
        header.putU32(ruleCursor.toLong())
        header.putU32(textLength.toLong())
        header.putU32(if (textBytes in 0..0xFFFFFFFFL) textBytes else 0xFFFFFFFFL)

        val indexBytes = index.toByteArray()
        header.toByteArray().copyInto(indexBytes, 0)
        return EncodedChapter(indexBytes, runs.toByteArray(), objects.toByteArray())
    }

    // ── 읽기 ────────────────────────────────────────────────────────

    /**
     * 머리말만 읽는다. 알아볼 수 없거나 버전이 다르면 null — 호출부는 캐시를 버리고
     * 다시 조판한다. 오래된 캐시로 엉뚱한 화면을 그리는 것보다 한 번 더 조판하는 게 낫다.
     */
    fun decodeIndex(index: ByteArray): ChapterIndex? {
        if (index.size < HEADER_SIZE) return null
        val reader = ByteReader(index)
        if (reader.u32().toInt() != MAGIC) return null
        val version = reader.u16()
        if (version != VERSION) return null

        reader.skip(2) // 옛 u16 페이지 수
        val complete = reader.u8() == 1
        reader.skip(3)
        reader.skip(12) // run/image/rule 개수는 페이지 엔트리로 충분하다
        val textLength = reader.u32().toInt()
        val textBytes = reader.u32().let { if (it == 0xFFFFFFFFL) -1L else it }

        // 페이지 엔트리는 고정 길이이고 머리말 뒤에 빈틈없이 이어진다. 남는 바이트가 있으면 잘린 파일이다.
        val body = index.size - HEADER_SIZE
        if (body % PAGE_ENTRY_SIZE != 0) return null
        return ChapterIndex(version, body / PAGE_ENTRY_SIZE, complete, textLength, textBytes)
    }

    /**
     * 페이지마다 시작 글자 오프셋만 뽑는다. 알아볼 수 없으면 null.
     *
     * 책갈피와 이어읽기는 "이 글자가 몇 번째 페이지인가" 만 알면 되고, 그건 색인
     * 파일만 읽어 이분 탐색으로 끝난다. 이것 없이 위치를 찾으려면 페이지를 하나씩
     * 복원해야 하고, 그러면 책을 열 때마다 챕터 전체를 되돌리는 값을 치른다.
     */
    fun decodeStarts(index: ByteArray): IntArray? {
        val header = decodeIndex(index) ?: return null
        val starts = IntArray(header.pageCount)
        for (page in 0 until header.pageCount) {
            // 페이지 엔트리 안에서 startChar 의 자리: runStart(4) + runCount(2) +
            // imageStart(2) + imageCount(1) + ruleCount(1) + ruleStart(2) = 12바이트째.
            starts[page] = ByteReader(index, HEADER_SIZE + page * PAGE_ENTRY_SIZE + START_CHAR_OFFSET)
                .u32().toInt()
        }
        return starts
    }

    /**
     * 페이지 하나를 복원한다. 범위를 벗어나거나 캐시가 잘렸으면 null.
     *
     * [objects] 는 챕터 전체의 그림·구분선이 순서대로 들어 있는 덩이다. 페이지마다
     * 몇 개뿐이라 처음부터 훑어도 싸고, 이 덕분에 런 배열은 고정 길이를 유지한다.
     */
    fun decodePage(encoded: EncodedChapter, pageIndex: Int): Page? {
        val header = decodeIndex(encoded.index) ?: return null
        if (pageIndex !in 0 until header.pageCount) return null

        val entry = ByteReader(encoded.index, HEADER_SIZE + pageIndex * PAGE_ENTRY_SIZE)
        val runStart = entry.u32().toInt()
        val runCount = entry.u16()
        val imageStart = entry.u16()
        val imageCount = entry.u8()
        val ruleCount = entry.u8()
        val ruleStart = entry.u16()
        val startChar = entry.u32().toInt()
        val endChar = entry.u32().toInt()

        val runsEnd = (runStart + runCount) * RUN_SIZE
        if (runsEnd > encoded.runs.size) return null

        val runs = ArrayList<PlacedRun>(runCount)
        val runReader = ByteReader(encoded.runs, runStart * RUN_SIZE)
        repeat(runCount) {
            val from = runReader.u32().toInt()
            val to = runReader.u32().toInt()
            val x = runReader.f32()
            val baseline = runReader.f32()
            val scale = runReader.f32()
            val flags = runReader.u8()
            runReader.skip(1)
            val face = runReader.u16()
            runs.add(PlacedRun(from, to, styleOf(flags, scale, face), x, baseline))
        }

        val (images, rules) = readObjects(encoded.objects, imageStart, imageCount, ruleStart, ruleCount)
            ?: return null

        return Page(pageIndex, startChar, endChar, runs, images, rules)
    }

    /**
     * 그림·구분선 덩이를 훑어 필요한 구간만 골라 낸다.
     *
     * 가변 길이라 색인으로 바로 자를 수 없고 앞에서부터 세어야 한다. 챕터당 수십 개를
     * 넘지 않으므로 이 비용은 무시할 만하고, 대신 런 배열이 고정 길이를 유지해
     * 페이지 넘김 경로에 파싱이 남지 않는다.
     */
    private fun readObjects(
        objects: ByteArray,
        imageStart: Int,
        imageCount: Int,
        ruleStart: Int,
        ruleCount: Int,
    ): Pair<List<PlacedImage>, List<PlacedRule>>? {
        if (imageCount == 0 && ruleCount == 0) return emptyList<PlacedImage>() to emptyList()

        val images = ArrayList<PlacedImage>(imageCount)
        val rules = ArrayList<PlacedRule>(ruleCount)
        val reader = ByteReader(objects)
        var imageSeen = 0
        var ruleSeen = 0

        while (reader.hasMore && (images.size < imageCount || rules.size < ruleCount)) {
            when (reader.u8().toByte()) {
                OBJECT_IMAGE -> {
                    val href = reader.string() ?: return null
                    val x = reader.f32(); val y = reader.f32()
                    val w = reader.f32(); val h = reader.f32()
                    if (imageSeen >= imageStart && images.size < imageCount) {
                        images.add(PlacedImage(href, x, y, w, h))
                    }
                    imageSeen++
                }

                OBJECT_RULE -> {
                    val x = reader.f32(); val y = reader.f32()
                    val w = reader.f32(); val t = reader.f32()
                    if (ruleSeen >= ruleStart && rules.size < ruleCount) {
                        rules.add(PlacedRule(x, y, w, t))
                    }
                    ruleSeen++
                }

                else -> return null // 알 수 없는 표식: 캐시가 상했다
            }
        }
        if (images.size != imageCount || rules.size != ruleCount) return null
        return images to rules
    }

    // ── 서식 비트 ───────────────────────────────────────────────────

    private fun styleFlags(style: TextStyle): Int {
        var flags = 0
        if (style.bold) flags = flags or FLAG_BOLD
        if (style.italic) flags = flags or FLAG_ITALIC
        if (style.underline) flags = flags or FLAG_UNDERLINE
        if (style.strikethrough) flags = flags or FLAG_STRIKETHROUGH
        when (style.vertical) {
            VerticalAlign.Superscript -> flags = flags or FLAG_SUPERSCRIPT
            VerticalAlign.Subscript -> flags = flags or FLAG_SUBSCRIPT
            VerticalAlign.Baseline -> Unit
        }
        return flags
    }

    private fun styleOf(flags: Int, sizeScale: Float, face: Int): TextStyle = TextStyle(
        face = face,
        bold = flags and FLAG_BOLD != 0,
        italic = flags and FLAG_ITALIC != 0,
        sizeScale = sizeScale,
        underline = flags and FLAG_UNDERLINE != 0,
        strikethrough = flags and FLAG_STRIKETHROUGH != 0,
        vertical = when {
            flags and FLAG_SUPERSCRIPT != 0 -> VerticalAlign.Superscript
            flags and FLAG_SUBSCRIPT != 0 -> VerticalAlign.Subscript
            else -> VerticalAlign.Baseline
        },
    )
}

// ── 리틀엔디안 바이트 버퍼 ──────────────────────────────────────────

private class ByteWriter(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    fun skip(count: Int) { ensure(count); size += count }

    fun putU8(value: Int) { ensure(1); buffer[size++] = value.toByte() }

    fun putU16(value: Int) {
        ensure(2)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
    }

    fun putU32(value: Long) {
        ensure(4)
        for (shift in 0..24 step 8) buffer[size++] = (value ushr shift).toByte()
    }

    fun putF32(value: Float) = putU32(value.toRawBits().toLong() and 0xFFFFFFFFL)

    /** 길이(u16) + UTF-8 바이트. 64KB 를 넘는 경로는 없다. */
    fun putString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        putU16(bytes.size.coerceAtMost(0xFFFF))
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}

private class ByteReader(private val buffer: ByteArray, private var position: Int = 0) {

    val hasMore: Boolean get() = position < buffer.size

    fun skip(count: Int) { position += count }

    fun u8(): Int = if (position < buffer.size) buffer[position++].toInt() and 0xFF else 0

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long {
        var value = 0L
        for (shift in 0..24 step 8) value = value or (u8().toLong() shl shift)
        return value
    }

    fun f32(): Float = Float.fromBits(u32().toInt())

    fun string(): String? {
        val length = u16()
        if (position + length > buffer.size) return null
        val text = String(buffer, position, length, Charsets.UTF_8)
        position += length
        return text
    }
}
