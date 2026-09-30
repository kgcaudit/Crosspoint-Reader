package io.github.kgcaudit.reader.data.backup

import io.github.kgcaudit.reader.document.Locator
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 책 한 권의 읽기 기록. 백업 파일의 한 항목이다.
 *
 * 책을 **파일 이름 + 크기**로 가리킨다. 이 휴대폰의 책 id(SAF 주소)는 다른 휴대폰에서 모양이 달라, 주소로 적으면
 * 새 휴대폰에서 한 권도 이어지지 않는다. 크기를 모르면(제공자가 알려 주지 않음) null 이고, 그때는 이름으로만 찾는다.
 */
data class BookRecord(
    val displayName: String,
    val sizeBytes: Long?,
    val title: String? = null,
    val author: String? = null,
    val progress: ProgressRecord? = null,
    val openedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val annotations: List<AnnotationRecord> = emptyList(),
) {
    internal val key: String get() = "$displayName\u0000${sizeBytes ?: ""}"

    /**
     * 같은 책의 기록 두 벌을 하나로. 가져오기가 DB 에 합치는 규칙과 같다 — 읽은 자리는 더 뒤쪽, 책갈피 · 형광펜은
     * 둘 다(같은 자리는 하나), 다 읽은 때는 먼저 적힌 것. 기다리는 기록(못 찾은 책)을 두 번 가져와도 두 벌로
     * 쌓이지 않게 쓴다.
     */
    internal fun mergedWith(other: BookRecord): BookRecord = copy(
        title = title ?: other.title,
        author = author ?: other.author,
        progress = when {
            progress == null -> other.progress
            other.progress == null -> progress
            other.progress.isFurtherThan(progress) -> other.progress
            else -> progress
        },
        openedAtEpochMs = listOfNotNull(openedAtEpochMs, other.openedAtEpochMs).maxOrNull(),
        finishedAtEpochMs = finishedAtEpochMs ?: other.finishedAtEpochMs,
        bookmarks = (bookmarks + other.bookmarks).distinctBy { it.locator },
        annotations = (annotations + other.annotations).groupBy { it.range }.values.map { same ->
            same.first().let { first -> if (first.note == null) first.copy(note = same.firstNotNullOfOrNull { it.note }) else first }
        },
    )
}

data class ProgressRecord(val locator: String, val percent: Float, val updatedAtEpochMs: Long) {
    /**
     * 이 자리가 [other] 보다 책의 더 뒤인가. 두 휴대폰에서 번갈아 읽었으면 더 많이 읽은 쪽이 맞다 — "나중에 적힌 것"
     * 으로 고르면, 옛 휴대폰에서 첫 장을 잠깐 펼친 기록이 새 휴대폰의 200쪽을 덮는다.
     */
    fun isFurtherThan(other: ProgressRecord): Boolean {
        val mine = Locator.decodeOrNull(locator) ?: return false
        val theirs = Locator.decodeOrNull(other.locator) ?: return true
        return when {
            mine is Locator.Reflow && theirs is Locator.Reflow ->
                compareValuesBy(mine, theirs, { it.spine }, { it.charOffset }) > 0
            mine is Locator.FixedPage && theirs is Locator.FixedPage ->
                compareValuesBy(mine, theirs, { it.page }, { it.yPermille }, { it.xPermille }) > 0
            // 같은 파일인데 위치 종류가 다르면(있을 수 없는 일) 나중에 적힌 쪽을 믿는다.
            else -> updatedAtEpochMs > other.updatedAtEpochMs
        }
    }
}

data class BookmarkRecord(val locator: String, val snippet: String?, val createdAtEpochMs: Long)

data class AnnotationRecord(
    val start: String,
    val end: String,
    /** `HighlightColor` 이름. 모르는 색이어도 그대로 옮긴다 — 읽을 때 노랑으로 보인다. */
    val color: String,
    val note: String?,
    val snippet: String,
    val createdAtEpochMs: Long,
) {
    internal val range: String get() = "$start-$end"
}

/** 백업 파일 하나. */
data class RecordsFile(val createdAtEpochMs: Long, val books: List<BookRecord>) {
    val bookmarkCount: Int get() = books.sumOf { it.bookmarks.size }
    val annotationCount: Int get() = books.sumOf { it.annotations.size }
}

/** 같은 책(이름 + 크기)의 기록을 한 벌로 모은다. */
internal fun List<BookRecord>.mergedByBook(): List<BookRecord> =
    groupBy { it.key }.values.map { same -> same.reduce { a, b -> a.mergedWith(b) } }

/**
 * 백업 파일 형식. 사람이 열어 봐도 읽히는 JSON 이다 — 무엇이 들었는지 사용자가 확인할 수 있고, 앱이 사라져도 기록이
 * 글로 남는다.
 *
 * 읽기는 관대하다: 모르는 칸은 건너뛰고, 상한 항목(자리가 깨진 책갈피 등)은 그 항목만 버린다. 한 줄 때문에 백업
 * 전체를 못 쓰면 휴대폰을 바꾼 사람은 기록을 통째로 잃는다. 다만 이 앱의 백업이라는 표시([FORMAT])가 없으면
 * 아무것도 읽지 않는다 — 다른 JSON 을 골랐을 때 엉뚱한 기록을 만들지 않게.
 */
object RecordsCodec {
    const val FORMAT: String = "olo-ebook-reading-records"
    const val VERSION: Int = 1

    fun encode(file: RecordsFile): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("createdAt", file.createdAtEpochMs)
        .put("books", JSONArray(file.books.map(::bookJson)))
        .toString(1)

    /** 이 앱의 백업이 아니면(JSON 이 아님, 표시가 없음) null. */
    fun decode(text: String): RecordsFile? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (root.optString("format") != FORMAT) return null
        val books = root.optJSONArray("books") ?: JSONArray()
        return RecordsFile(
            createdAtEpochMs = root.longOrNull("createdAt") ?: 0L,
            books = books.objects().mapNotNull(::book),
        )
    }

    private fun bookJson(b: BookRecord) = JSONObject().apply {
        put("name", b.displayName)
        putOpt("size", b.sizeBytes)
        putOpt("title", b.title)
        putOpt("author", b.author)
        b.progress?.let {
            put("progress", JSONObject().put("locator", it.locator).put("percent", it.percent.toDouble()).put("updatedAt", it.updatedAtEpochMs))
        }
        putOpt("openedAt", b.openedAtEpochMs)
        putOpt("finishedAt", b.finishedAtEpochMs)
        put("bookmarks", JSONArray(b.bookmarks.map { JSONObject().put("locator", it.locator).putOpt("snippet", it.snippet).put("createdAt", it.createdAtEpochMs) }))
        put(
            "annotations",
            JSONArray(
                b.annotations.map {
                    JSONObject().put("start", it.start).put("end", it.end).put("color", it.color).putOpt("note", it.note)
                        .put("snippet", it.snippet).put("createdAt", it.createdAtEpochMs)
                },
            ),
        )
    }

    private fun book(o: JSONObject): BookRecord? {
        val name = o.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        return BookRecord(
            displayName = name,
            sizeBytes = o.longOrNull("size")?.takeIf { it >= 0 },
            title = o.stringOrNull("title"),
            author = o.stringOrNull("author"),
            progress = o.optJSONObject("progress")?.let(::progress),
            openedAtEpochMs = o.longOrNull("openedAt"),
            finishedAtEpochMs = o.longOrNull("finishedAt"),
            bookmarks = (o.optJSONArray("bookmarks") ?: JSONArray()).objects().mapNotNull { b ->
                val locator = b.stringOrNull("locator")?.takeIf { Locator.decodeOrNull(it) != null } ?: return@mapNotNull null
                BookmarkRecord(locator, b.stringOrNull("snippet"), b.longOrNull("createdAt") ?: 0L)
            },
            annotations = (o.optJSONArray("annotations") ?: JSONArray()).objects().mapNotNull(::annotation),
        )
    }

    private fun progress(p: JSONObject): ProgressRecord? {
        val locator = p.stringOrNull("locator")?.takeIf { Locator.decodeOrNull(it) != null } ?: return null
        val percent = p.optDouble("percent", 0.0).toFloat().takeIf { !it.isNaN() } ?: 0f
        return ProgressRecord(locator, percent.coerceIn(0f, 100f), p.longOrNull("updatedAt") ?: 0L)
    }

    private fun annotation(a: JSONObject): AnnotationRecord? {
        // 형광펜은 리플로우 책에만 있다. 빈 구간 · 뒤집힌 구간은 칠할 수 없다 — 들여보내면 형광펜을 읽을 때 걸러져
        // 보이지도 지워지지도 않는 행이 남는다.
        val start = a.stringOrNull("start")?.let { Locator.decodeOrNull(it) } as? Locator.Reflow ?: return null
        val end = a.stringOrNull("end")?.let { Locator.decodeOrNull(it) } as? Locator.Reflow ?: return null
        if (compareValuesBy(start, end, { it.spine }, { it.charOffset }) >= 0) return null
        return AnnotationRecord(
            start = Locator.encode(start),
            end = Locator.encode(end),
            color = a.stringOrNull("color") ?: "Yellow",
            note = a.stringOrNull("note")?.trim()?.takeIf { it.isNotEmpty() },
            snippet = a.stringOrNull("snippet") ?: "",
            createdAtEpochMs = a.longOrNull("createdAt") ?: 0L,
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    // optString 은 없는 칸에 "" 를, JSON null 에 "null" 글자를 돌려준다. 그대로 쓰면 제목이 "null" 인 책이 생긴다.
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else (opt(key) as? String)

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else (opt(key) as? Number)?.toLong()
}
