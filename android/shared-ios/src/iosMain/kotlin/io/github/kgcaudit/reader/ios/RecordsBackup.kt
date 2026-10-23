@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.io.currentTimeMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/*
 * 읽기 기록 백업(구상안 31A). 안드로이드 `RecordsCodec` · `RecordsBackup` 과 같은 파일 · 같은 합치기 — 두 앱이 서로의 백업을
 * 가져온다. 형식이 갈라지면 휴대폰을 바꿀 때 기록을 잃으므로, 시험이 안드로이드 시험과 같은 입력 · 기대값을 지킨다.
 *
 * iOS 에 없는 만화 · 작품 설정 항목은 읽은 JSON 그대로 간직했다가 다음 백업에 다시 담는다 — 버리면 안드로이드 → iOS → 안드로이드로
 * 옮길 때 만화 기록이 사라진다.
 */

/** 백업 속 책 한 권. 맞추는 열쇠는 [name](확장자까지의 파일 이름) + [size](바이트, 모르면 null). */
class BackupBook(
    val name: String,
    val size: Long?,
    val title: String?,
    val author: String?,
    val progress: BackupProgress?,
    val openedAt: Long?,
    val finishedAt: Long?,
    val bookmarks: List<BackupBookmark>,
    val annotations: List<BackupNote>,
    val time: List<BackupTime>,
) {
    /** 이 책에 담을 기록이 있는가(이름만 있는 항목도 버리지 않지만, 내보낼 때는 기록이 있는 책만 담는다). */
    val hasRecords: Boolean
        get() = progress != null || openedAt != null || finishedAt != null || bookmarks.isNotEmpty() || annotations.isNotEmpty() || time.isNotEmpty()

    internal val key: String get() = nfc(name) + "\u0000" + (size?.toString() ?: "")

    /**
     * 같은 책의 기록 두 벌을 하나로(파일 안의 중복 · 기다리는 기록에 새로 온 것). 안드로이드 `BookRecord.mergedWith`: 제목 · 저자는
     * 앞쪽, 읽은 자리는 더 뒤쪽, 연 때는 나중, 다 읽은 때는 먼저, 책갈피는 자리로 · 형광펜은 구간으로 겹침을 없애고, 시간은 큰 쪽.
     */
    fun mergedWith(other: BackupBook): BackupBook = BackupBook(
        name = name,
        size = size ?: other.size,
        title = title ?: other.title,
        author = author ?: other.author,
        progress = furthest(progress, other.progress),
        openedAt = listOfNotNull(openedAt, other.openedAt).maxOrNull(),
        finishedAt = listOfNotNull(finishedAt, other.finishedAt).minOrNull(),
        bookmarks = (bookmarks + other.bookmarks).distinctBy { it.locator },
        annotations = (annotations + other.annotations).groupBy { it.range }.values.map { same ->
            val first = same.first()
            BackupNote(first.start, first.end, first.color, same.firstNotNullOfOrNull { it.note }, first.snippet, first.createdAt)
        },
        time = (time + other.time).groupBy { it.day to it.mode }.values.map { same -> same.maxBy { it.ms } }
            .sortedWith(compareBy({ it.day }, { it.mode })),
    )
}

class BackupProgress(val locator: String, val percent: Float, val updatedAt: Long)

class BackupBookmark(val locator: String, val snippet: String?, val createdAt: Long)

/** 형광펜 한 줄. [start] · [end] 는 정규화한 `r:spine:offset`. [color] 는 모르는 이름도 그대로(옮겨 줄 뿐이다). */
class BackupNote(val start: String, val end: String, val color: String, val note: String?, val snippet: String, val createdAt: Long) {
    internal val range: String get() = "$start-$end"
}

/** 읽은 시간 한 칸. [day] 는 epoch day(파일에는 "2026-10-03"). */
class BackupTime(val day: Long, val mode: String, val ms: Long)

/**
 * 백업 속 만화 한 권(안드로이드 `ComicRecord`). 맞추는 열쇠는 책과 같다(이름 + 크기 — 그림 폴더는 크기가 없다).
 * [offset] · [half] 는 iOS 1단계에서 쓰지 않지만 옮겨 준다(웹툰 자리 · 펼침면 반).
 */
class BackupComic(
    val name: String,
    val size: Long?,
    val page: Int?,
    val pageCount: Int?,
    val offset: Double?,
    val half: String?,
    val updatedAt: Long,
    val finishedAt: Long?,
    val bookmarks: List<BackupComicMark>,
    val time: List<BackupTime>,
) {
    internal val key: String get() = nfc(name) + "\u0000" + (size?.toString() ?: "")

    /** 같은 권 두 벌을 하나로: 읽은 자리는 더 뒤쪽(쪽 → 그 안 자리), 다 읽은 때는 먼저, 책갈피는 쪽으로, 시간은 큰 쪽. */
    fun mergedWith(other: BackupComic): BackupComic {
        val further = if (furtherComic(other, this)) other else this
        return BackupComic(
            name, size ?: other.size, further.page ?: page ?: other.page, further.pageCount ?: pageCount ?: other.pageCount,
            further.offset, further.half, maxOf(updatedAt, other.updatedAt),
            listOfNotNull(finishedAt, other.finishedAt).minOrNull(),
            (bookmarks + other.bookmarks).distinctBy { it.page }.sortedBy { it.page },
            (time + other.time).groupBy { it.day to it.mode }.values.map { same -> same.maxBy { it.ms } }
                .sortedWith(compareBy({ it.day }, { it.mode })),
        )
    }
}

class BackupComicMark(val page: Int, val createdAt: Long)

/** [a] 가 [b] 보다 더 뒤를 읽었나(쪽 → 그 쪽 안 자리). 쪽을 모르면 뒤가 아니다. */
internal fun furtherComic(a: BackupComic, b: BackupComic): Boolean {
    val pa = a.page ?: return false
    val pb = b.page ?: return true
    return pa > pb || (pa == pb && (a.offset ?: 0.0) > (b.offset ?: 0.0))
}

/**
 * 백업 속 작품 설정 하나(안드로이드 `WorkRecord`). iOS 가 쓰는 것은 넘기는 방향 · 표지 · 갈래뿐이지만 다른 칸(이름 · 보는 방식 ·
 * 펼침면 나누기)도 옮겨 준다 — 버리면 안드로이드 → iOS → 안드로이드에서 사라진다.
 */
class BackupWork(
    val key: String,
    val title: String?,
    val rightToLeft: Boolean?,
    val view: String?,
    val shelf: String?,
    val coverAlone: Boolean?,
    val splitSpreads: Boolean?,
) {
    /** [other] 의 칸으로 비어 있는 칸만 채운다(앞쪽이 이긴다). */
    fun filledFrom(other: BackupWork): BackupWork = BackupWork(
        key, title ?: other.title, rightToLeft ?: other.rightToLeft, view ?: other.view, shelf ?: other.shelf,
        coverAlone ?: other.coverAlone, splitSpreads ?: other.splitSpreads,
    )
}

/** 백업 파일 하나. */
class BackupFile(val createdAt: Long, val books: List<BackupBook>, val comics: List<BackupComic>, val works: List<BackupWork>) {
    val isEmpty: Boolean get() = books.isEmpty() && comics.isEmpty() && works.isEmpty()
}

/** 서재의 책 한 권(맞추기 · 내보내기). [name] 은 파일 이름, [size] 는 바이트. */
class ShelfBook(val id: String, val name: String, val size: Long, val title: String?, val author: String?)

/** 서재의 만화 한 권(맞추기 · 내보내기). [size] 는 압축의 바이트, 그림 폴더는 null. */
class ShelfComic(val id: String, val name: String, val size: Long?)

/** 가져오기 전에 보이는 것. [found] · [missing] 은 책과 만화를 함께 센 권 수. */
class ImportPreview(
    val createdAt: Long,
    val books: Int,
    val comics: Int,
    val works: Int,
    val found: Int,
    val missing: Int,
    val isEmpty: Boolean,
    val comicsFound: Int,
)

/** 가져온 뒤 알리는 것(안드로이드 `DonePopup`). 수는 찾은 기록에 들어 있던 것(새로 더해진 수가 아니다 — 안드로이드와 같다). */
class ImportResult(
    val found: Int,
    val bookmarks: Int,
    val notes: Int,
    val finished: Int,
    val works: Int,
    val missingNames: List<String>,
    /** 찾은 만화 권 수([found] 에 들어 있다). */
    val comics: Int,
)

/** 서재 줄 부제의 수(안드로이드 `summaryLine`). */
class BackupSummary(val books: Int, val comics: Int, val bookmarks: Int, val notes: Int, val works: Int)

object RecordsCodec {
    const val FORMAT = "olo-ebook-reading-records"
    /** 이보다 큰 파일은 백업이 아니다(안드로이드와 같다) — 엉뚱한 큰 파일을 통째로 읽다 앱이 죽지 않게. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** 안드로이드 org.json `toString(1)` 처럼 한 칸 들여쓰기. null 칸은 키째 뺀다(안드로이드 `putOpt`). */
    fun encode(file: BackupFile): String {
        val w = JsonWriter()
        w.records(file)
        return w.toString()
    }

    /** [file] 을 객체 하나로 쓴다. 이어 읽기 파일(`SyncCodec`)이 이것을 `records` 칸에 그대로 품는다 — 같은 기록을 두 모양으로 적지 않게. */
    internal fun JsonWriter.records(file: BackupFile) {
        obj {
            field("format") { string(FORMAT) }
            field("version") { number(1) }
            field("createdAt") { number(file.createdAt) }
            field("books") { array(file.books) { book(it) } }
            field("comics") { array(file.comics) { comic(it) } }
            field("works") { array(file.works) { work(it) } }
        }
    }

    private fun JsonWriter.comic(c: BackupComic) = obj {
        field("name") { string(c.name) }
        c.size?.let { field("size") { number(it) } }
        c.page?.let { field("page") { number(it.toLong()) } }
        c.pageCount?.let { field("pageCount") { number(it.toLong()) } }
        c.offset?.let { field("offset") { decimal(it) } }
        c.half?.let { field("half") { string(it) } }
        field("updatedAt") { number(c.updatedAt) }
        c.finishedAt?.let { field("finishedAt") { number(it) } }
        field("bookmarks") {
            array(c.bookmarks) { m ->
                obj {
                    field("page") { number(m.page.toLong()) }
                    field("createdAt") { number(m.createdAt) }
                }
            }
        }
        if (c.time.isNotEmpty()) field("time") { times(c.time) }
    }

    private fun JsonWriter.work(w: BackupWork) = obj {
        field("key") { string(w.key) }
        w.title?.let { field("title") { string(it) } }
        w.rightToLeft?.let { field("rightToLeft") { bool(it) } }
        w.view?.let { field("view") { string(it) } }
        w.shelf?.let { field("shelf") { string(it) } }
        w.coverAlone?.let { field("coverAlone") { bool(it) } }
        w.splitSpreads?.let { field("splitSpreads") { bool(it) } }
    }

    private fun JsonWriter.times(list: List<BackupTime>) = array(list) { t ->
        obj {
            field("day") { string(isoDay(t.day)) }
            field("mode") { string(t.mode) }
            field("ms") { number(t.ms) }
        }
    }

    private fun JsonWriter.book(b: BackupBook) = obj {
        field("name") { string(b.name) }
        b.size?.let { field("size") { number(it) } }
        b.title?.let { field("title") { string(it) } }
        b.author?.let { field("author") { string(it) } }
        b.progress?.let { p ->
            field("progress") {
                obj {
                    field("locator") { string(p.locator) }
                    field("percent") { decimal(p.percent.toDouble()) }
                    field("updatedAt") { number(p.updatedAt) }
                }
            }
        }
        b.openedAt?.let { field("openedAt") { number(it) } }
        b.finishedAt?.let { field("finishedAt") { number(it) } }
        field("bookmarks") {
            array(b.bookmarks) { m ->
                obj {
                    field("locator") { string(m.locator) }
                    m.snippet?.let { field("snippet") { string(it) } }
                    field("createdAt") { number(m.createdAt) }
                }
            }
        }
        field("annotations") {
            array(b.annotations) { a ->
                obj {
                    field("start") { string(a.start) }
                    field("end") { string(a.end) }
                    field("color") { string(a.color) }
                    a.note?.let { field("note") { string(it) } }
                    field("snippet") { string(a.snippet) }
                    field("createdAt") { number(a.createdAt) }
                }
            }
        }
        // 빈 시간 목록은 키째 뺀다(안드로이드와 같다 — 0.51 이전 백업에는 이 칸이 없다).
        if (b.time.isNotEmpty()) {
            field("time") {
                array(b.time) { t ->
                    obj {
                        field("day") { string(isoDay(t.day)) }
                        field("mode") { string(t.mode) }
                        field("ms") { number(t.ms) }
                    }
                }
            }
        }
    }

    /**
     * 읽는다. 백업이 아니면(JSON 아님 · 맨 위가 객체 아님 · format 다름 · 너무 큼) null — 화면이 "백업 파일이 아닙니다" 를 알리고
     * 아무것도 바꾸지 않는다. 판 번호는 보지 않는다(새 판의 칸은 모르는 칸으로 넘어간다). 깨진 항목은 그것만 버린다.
     */
    fun decode(text: String): BackupFile? {
        if (text.length > MAX_BYTES) return null
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
        val root = runCatching { NSJSONSerialization.JSONObjectWithData(data, 0u, null) }.getOrNull() as? Map<*, *> ?: return null
        return decodeRoot(root)
    }

    /** 이미 풀린 JSON 객체에서 읽는다(이어 읽기 파일의 `records` 칸). 형식이 다르면 null. */
    internal fun decodeRoot(root: Map<*, *>): BackupFile? {
        if (root["format"] != FORMAT) return null
        val books = (root["books"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::book) }
        return BackupFile(
            createdAt = long(root["createdAt"]) ?: 0L,
            books = books,
            comics = (root["comics"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull(::comic),
            works = (root["works"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull(::work),
        )
    }

    private fun book(o: Map<*, *>): BackupBook? {
        val name = string(o["name"])?.takeIf { it.isNotEmpty() } ?: return null
        val progress = (o["progress"] as? Map<*, *>)?.let { p ->
            // 깨진 자리면 읽은 자리만 버린다.
            val locator = string(p["locator"])?.takeIf { Locator.decodeOrNull(it) != null } ?: return@let null
            val percent = double(p["percent"])?.takeIf { !it.isNaN() } ?: 0.0
            BackupProgress(locator, percent.toFloat().coerceIn(0f, 100f), long(p["updatedAt"]) ?: 0L)
        }
        val bookmarks = (o["bookmarks"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val locator = string(m["locator"])?.takeIf { Locator.decodeOrNull(it) != null } ?: return@mapNotNull null
            BackupBookmark(locator, string(m["snippet"]), long(m["createdAt"]) ?: 0L)
        }
        val notes = (o["annotations"] as? List<*>).orEmpty().mapNotNull { e ->
            val a = e as? Map<*, *> ?: return@mapNotNull null
            val start = string(a["start"])?.let(Locator::decodeOrNull) as? Locator.Reflow ?: return@mapNotNull null
            val end = string(a["end"])?.let(Locator::decodeOrNull) as? Locator.Reflow ?: return@mapNotNull null
            // 빈 · 뒤집힌 구간은 그 형광펜만 버린다.
            if (!(start.spine < end.spine || (start.spine == end.spine && start.charOffset < end.charOffset))) return@mapNotNull null
            BackupNote(
                Locator.encode(start), Locator.encode(end), string(a["color"]) ?: "Yellow",
                string(a["note"])?.trim()?.takeIf { it.isNotEmpty() }, string(a["snippet"]) ?: "", long(a["createdAt"]) ?: 0L,
            )
        }
        val time = times(o["time"])
        return BackupBook(
            name, long(o["size"])?.takeIf { it >= 0 }, string(o["title"]), string(o["author"]), progress,
            long(o["openedAt"]), long(o["finishedAt"]), bookmarks, notes, time,
        )
    }

    /**
     * 만화 한 권(안드로이드 `comic()`): 음수 쪽 · 범위 밖 자리 · 모르는 반은 그 칸만 버린다. 쪽 · 책갈피 · 시간이 모두 없으면 권째
     * 버린다(옮길 것이 없다).
     */
    private fun comic(o: Map<*, *>): BackupComic? {
        val name = string(o["name"])?.takeIf { it.isNotEmpty() } ?: return null
        val page = long(o["page"])?.takeIf { it >= 0 }?.toInt()
        val bookmarks = (o["bookmarks"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val p = long(m["page"])?.takeIf { it >= 0 }?.toInt() ?: return@mapNotNull null
            BackupComicMark(p, long(m["createdAt"]) ?: 0L)
        }.distinctBy { it.page }
        val time = times(o["time"])
        if (page == null && bookmarks.isEmpty() && time.isEmpty()) return null
        return BackupComic(
            name, long(o["size"])?.takeIf { it >= 0 }, page, long(o["pageCount"])?.takeIf { it > 0 }?.toInt(),
            // 자리 · 반은 쪽이 있을 때만 뜻이 있다. 모르는 반(손상 · "WHOLE")은 쪽 전체로 — 본 적 없는 반에서 열리지 않게.
            double(o["offset"])?.takeIf { page != null && it in 0.0..1.0 }, string(o["half"])?.takeIf { page != null && (it == "LEFT" || it == "RIGHT") },
            long(o["updatedAt"]) ?: 0L, long(o["finishedAt"]), bookmarks, time,
        )
    }

    /** 작품 설정 하나: 참 · 거짓 칸은 진짜 참 · 거짓만, 갈래는 읽을 수 있는 것만. 정한 것이 하나도 없으면 버린다. */
    private fun work(o: Map<*, *>): BackupWork? {
        val key = string(o["key"])?.takeIf { it.isNotBlank() } ?: return null
        val w = BackupWork(
            key, string(o["title"])?.trim()?.takeIf { it.isNotEmpty() }, bool(o["rightToLeft"]), string(o["view"]),
            string(o["shelf"])?.takeIf { io.github.kgcaudit.reader.document.comic.ShelfMark.parse(it) != null },
            bool(o["coverAlone"]), bool(o["splitSpreads"]),
        )
        return w.takeIf { it.title != null || it.rightToLeft != null || it.view != null || it.shelf != null || it.coverAlone != null || it.splitSpreads != null }
    }

    private fun times(v: Any?): List<BackupTime> = (v as? List<*>).orEmpty().mapNotNull { e ->
        val t = e as? Map<*, *> ?: return@mapNotNull null
        val day = string(t["day"])?.let(::parseDay) ?: return@mapNotNull null
        val mode = string(t["mode"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val ms = long(t["ms"])?.takeIf { it > 0 } ?: return@mapNotNull null
        BackupTime(day, mode, ms.coerceAtMost(DAY_MS))
    }.groupBy { it.day to it.mode }.values.map { same -> same.maxBy { it.ms } }.sortedWith(compareBy({ it.day }, { it.mode }))

    /** JSON 의 참 · 거짓만(1 · 0 같은 숫자는 아니다 — 안드로이드와 같다). */
    private fun bool(v: Any?): Boolean? {
        val n = v as? NSNumber ?: return null
        return if (n.objCType?.toKString() == "c") n.boolValue else null
    }

    /** 문자열만(JSON null · 숫자는 null — "null" 글자가 되지 않게). */
    private fun string(v: Any?): String? = v as? String

    /** 숫자만. "123" 같은 글은 숫자가 아니다(안드로이드 `longOrNull`). */
    private fun long(v: Any?): Long? = (v as? NSNumber)?.takeIf { v !is String }?.longLongValue

    private fun double(v: Any?): Double? = when (v) {
        is NSNumber -> v.doubleValue
        is String -> v.toDoubleOrNull() // 안드로이드 optDouble 은 "50" 도 받는다.
        else -> null
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** epoch day → "2026-10-03"(그레고리력, 안드로이드 `LocalDate.toString`). */
    fun isoDay(epochDay: Long): String {
        // Howard Hinnant 의 civil_from_days.
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** "2026-10-03" → epoch day. 깨진 날("어제" · 13월 · 2월 30일)이면 null. */
    fun parseDay(text: String): Long? {
        val p = text.split('-')
        if (p.size != 3 || p[0].length != 4 || p[1].length != 2 || p[2].length != 2) return null
        val y = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull()?.takeIf { it in 1..12 } ?: return null
        val d = p[2].toLongOrNull()?.takeIf { it >= 1 } ?: return null
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val day = era * 146097 + doe - 719468
        return day.takeIf { isoDay(it) == text }
    }
}

internal fun nfc(s: String): String {
    @Suppress("CAST_NEVER_SUCCEEDS")
    return (s as NSString).precomposedStringWithCanonicalMapping
}

/**
 * 가져오기 · 내보내기 · 기다리는 기록(안드로이드 `RecordsBackup`). 못 찾은 책의 기록은 [pendingPath] 에 같은 형식으로 두었다가
 * 서재를 훑을 때마다 다시 붙여 본다. 내보낼 때도 함께 담는다 — "가져오기 → 못 찾음 → 다시 백업" 에서 기록이 사라지지 않게.
 */
class RecordsBackup(private val store: ReadingStore, private val pendingPath: String) {

    /** 백업 파일 글. 기록이 있는 책 · 만화 · 작품 설정 + 기다리는 기록. */
    fun export(shelf: List<ShelfBook>, comics: List<ShelfComic>, now: Long = currentTimeMillis()): String =
        RecordsCodec.encode(collect(shelf, comics, now))

    /** 서재 줄의 부제. */
    fun summary(shelf: List<ShelfBook>, comics: List<ShelfComic>): BackupSummary {
        val all = collect(shelf, comics, 0)
        return BackupSummary(
            all.books.size, all.comics.size, all.books.sumOf { it.bookmarks.size } + all.comics.sumOf { it.bookmarks.size },
            all.books.sumOf { it.annotations.size }, all.works.size,
        )
    }

    private fun collect(shelf: List<ShelfBook>, comics: List<ShelfComic>, now: Long): BackupFile {
        val pending = readPending()
        val books = (shelf.mapNotNull { store.recordOf(it.id, it.name, it.size, it.title, it.author) } + pending.books).mergedBooks()
        val units = (comics.mapNotNull { store.recordOfComic(it.id, it.name, it.size) } + pending.comics).mergedComics()
        // 이 휴대폰에서 정한 것이 앞 — 옛 판이 기다림에 남긴 작품 설정은 빈 칸만 채운다.
        val works = (store.workRecords() + pending.works).mergedWorks()
        return BackupFile(now, books, units, works)
    }

    /** 고른 파일을 미리 본다. 백업이 아니면 null. */
    fun preview(text: String, shelf: List<ShelfBook>, comics: List<ShelfComic>): ImportPreview? {
        val file = RecordsCodec.decode(text) ?: return null
        val books = file.books.mergedBooks()
        val units = file.comics.mergedComics()
        val found = books.count { targets(it, shelf).isNotEmpty() }
        val comicsFound = units.count { comicTargets(it, comics).isNotEmpty() }
        return ImportPreview(
            file.createdAt, books.size, units.size, file.works.mergedWorks().size, found + comicsFound,
            books.size - found + units.size - comicsFound, file.isEmpty, comicsFound,
        )
    }

    /**
     * 합친다. 이 휴대폰의 기록은 지우지 않는다. 같은 파일을 두 번 가져와도 결과는 한 번 가져온 것과 같다. 못 찾은 책 · 만화 · 작품
     * 설정은 기다리는 기록으로. 백업이 아니면 null.
     */
    fun apply(text: String, shelf: List<ShelfBook>, comics: List<ShelfComic>): ImportResult? {
        val file = RecordsCodec.decode(text) ?: return null
        val books = file.books.mergedBooks()
        var found = 0
        var bookmarks = 0
        var notes = 0
        var finished = 0
        val missing = ArrayList<BackupBook>()
        for (b in books) {
            val targets = targets(b, shelf)
            if (targets.isEmpty()) { missing += b; continue }
            for (t in targets) store.merge(t.id, b)
            found++
            bookmarks += b.bookmarks.size
            notes += b.annotations.size
            if (b.finishedAt != null) finished++
        }
        var comicsFound = 0
        val comicsMissing = ArrayList<BackupComic>()
        for (c in file.comics.mergedComics()) {
            val targets = comicTargets(c, comics)
            if (targets.isEmpty()) { comicsMissing += c; continue }
            for (t in targets) store.mergeComic(t.id, c)
            comicsFound++
            bookmarks += c.bookmarks.size
            if (c.finishedAt != null) finished++
        }
        // 작품 설정은 작품이 아직 없어도 바로 둔다(안드로이드와 같다) — 열쇠가 작품 이름에서 나와, 그 만화를 나중에 넣으면 그대로 붙는다.
        val works = file.works.mergedWorks()
        store.mergeWorks(works)
        val pending = readPending()
        writePending(BackupFile(0, (pending.books + missing).mergedBooks(), (pending.comics + comicsMissing).mergedComics(), pending.works))
        return ImportResult(
            found + comicsFound, bookmarks, notes, finished, works.size, missing.map { it.name } + comicsMissing.map { it.name }, comicsFound,
        )
    }

    /** 서재를 훑은 뒤: 기다리던 책 · 만화 중 이제 보이는 것에 붙이고 기다림에서 뺀다. 붙인 권 수. */
    fun resumePending(shelf: List<ShelfBook>, comics: List<ShelfComic>): Int {
        val pending = readPending()
        if (pending.isEmpty) return 0
        val left = ArrayList<BackupBook>()
        var done = 0
        for (b in pending.books) {
            val targets = targets(b, shelf)
            if (targets.isEmpty()) { left += b; continue }
            for (t in targets) store.merge(t.id, b)
            done++
        }
        val leftComics = ArrayList<BackupComic>()
        for (c in pending.comics) {
            val targets = comicTargets(c, comics)
            if (targets.isEmpty()) { leftComics += c; continue }
            for (t in targets) store.mergeComic(t.id, c)
            done++
        }
        // 옛 판이 기다림에 둔 작품 설정은 이제 이 휴대폰 설정으로 옮긴다(작품이 없어도 열쇠로 붙는다).
        store.mergeWorks(pending.works)
        if (done > 0 || pending.works.isNotEmpty()) writePending(BackupFile(0, left, leftComics, emptyList()))
        return done
    }

    /**
     * 이 기록을 붙일 책(안드로이드 `targetsOf`). 크기를 아는 기록은 이름 + 크기가 같은 책 모두(같은 파일이 두 폴더에 있으면 둘 다).
     * 크기를 모르는 기록은 이름이 같은 책이 꼭 한 권일 때만. 이름이 같아도 크기가 다르면 다른 판이라 붙이지 않는다. 이름은 한글을
     * 모아 쓴 꼴로 견준다 — 맥을 거친 파일 이름은 풀어 쓴 꼴일 수 있다.
     */
    private fun targets(b: BackupBook, shelf: List<ShelfBook>): List<ShelfBook> {
        val name = nfc(b.name)
        val sameName = shelf.filter { nfc(it.name) == name }
        return if (b.size != null) sameName.filter { it.size == b.size } else sameName.takeIf { it.size == 1 }.orEmpty()
    }

    /**
     * 이 만화 기록을 붙일 권(안드로이드 `comicTargetsOf`). 크기를 아는 기록은 이름 + 크기가 같은 권 모두, 없으면 이름이 같은 그림
     * 폴더가 꼭 하나일 때(압축을 풀어 폴더로 옮긴 경우). 크기를 모르는 기록(그림 폴더)은 이름이 같은 권이 꼭 하나일 때만.
     */
    private fun comicTargets(c: BackupComic, comics: List<ShelfComic>): List<ShelfComic> {
        val name = nfc(c.name)
        val sameName = comics.filter { nfc(it.name) == name }
        return if (c.size != null) {
            sameName.filter { it.size == c.size }.ifEmpty { sameName.filter { it.size == null }.takeIf { it.size == 1 }.orEmpty() }
        } else {
            sameName.takeIf { it.size == 1 }.orEmpty()
        }
    }

    private fun List<BackupBook>.mergedBooks() = groupBy { it.key }.values.map { it.reduce(BackupBook::mergedWith) }
    private fun List<BackupComic>.mergedComics() = groupBy { it.key }.values.map { it.reduce(BackupComic::mergedWith) }
    private fun List<BackupWork>.mergedWorks() = groupBy { it.key }.values.map { it.reduce(BackupWork::filledFrom) }

    private fun readPending(): BackupFile {
        val text = NSString.stringWithContentsOfFile(pendingPath, NSUTF8StringEncoding, null) ?: return BackupFile(0, emptyList(), emptyList(), emptyList())
        // 망가진 기다림 파일은 빈 것으로 — 그 때문에 가져오기가 멈추면 안 된다.
        return RecordsCodec.decode(text) ?: BackupFile(0, emptyList(), emptyList(), emptyList())
    }

    private fun writePending(file: BackupFile) {
        if (file.isEmpty) {
            NSFileManager.defaultManager.removeItemAtPath(pendingPath, null)
            return
        }
        @Suppress("CAST_NEVER_SUCCEEDS")
        (RecordsCodec.encode(file) as NSString).writeToFile(pendingPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}

/** 더 뒤쪽 읽은 자리(안드로이드 `isFurtherThan`): 리플로우는 (장, 글자), 고정 쪽은 (쪽, y, x), 종류가 다르면 나중에 고친 것. */
internal fun furthest(a: BackupProgress?, b: BackupProgress?): BackupProgress? {
    if (a == null) return b
    if (b == null) return a
    return if (isFurther(b, a)) b else a
}

internal fun isFurther(candidate: BackupProgress, than: BackupProgress): Boolean {
    val c = Locator.decodeOrNull(candidate.locator) ?: return false
    val t = Locator.decodeOrNull(than.locator) ?: return true
    return when {
        c is Locator.Reflow && t is Locator.Reflow -> compareValuesBy(c, t, { it.spine }, { it.charOffset }) > 0
        c is Locator.FixedPage && t is Locator.FixedPage -> compareValuesBy(c, t, { it.page }, { it.yPermille }, { it.xPermille }) > 0
        else -> candidate.updatedAt > than.updatedAt
    }
}

/** 한 칸 들여쓰기 JSON 쓰기(안드로이드 org.json `toString(1)` 과 같은 모양). */
internal class JsonWriter {
    private val out = StringBuilder()
    private var depth = 0
    private val first = ArrayList<Boolean>()

    fun obj(block: JsonWriter.() -> Unit) {
        out.append('{')
        open()
        block()
        close('}')
    }

    fun <T> array(items: List<T>, each: JsonWriter.(T) -> Unit) {
        out.append('[')
        open()
        for (item in items) {
            next()
            each(item)
        }
        close(']')
    }

    fun field(name: String, value: JsonWriter.() -> Unit) {
        next()
        string(name)
        out.append(": ")
        value()
    }

    fun string(s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
        out.append('"')
    }

    fun number(n: Long) {
        out.append(n)
    }

    /** 정수 값이면 소수점 없이(org.json 과 같다: 40.0 → 40). */
    fun decimal(d: Double) {
        if (d == kotlin.math.floor(d) && !d.isInfinite()) out.append(d.toLong()) else out.append(d.toString())
    }

    fun bool(b: Boolean) {
        out.append(b)
    }

    private fun open() {
        depth++
        first.add(true)
    }

    private fun next() {
        if (!first.last()) out.append(',')
        first[first.size - 1] = false
        out.append('\n').append(" ".repeat(depth))
    }

    private fun close(c: Char) {
        val empty = first.removeAt(first.size - 1)
        depth--
        if (!empty) out.append('\n').append(" ".repeat(depth))
        out.append(c)
    }

    override fun toString() = out.toString()
}
