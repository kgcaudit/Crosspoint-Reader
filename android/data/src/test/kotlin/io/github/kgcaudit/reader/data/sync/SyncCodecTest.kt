package io.github.kgcaudit.reader.data.sync

import io.github.kgcaudit.reader.data.backup.AnnotationRecord
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.BookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicBookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.document.Locator
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 책 폴더의 `.olo/sync/<기기>.json` 형식. 다른 기기 · 다른 판 · 동기화 앱이 만진 파일을 읽어도 앱이 무너지지 않아야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncCodecTest {

    private val id = "0b5c2a6e-1f4d-4a3b-9c8d-7e6f5a4b3c2d"

    private val sample = SyncFile(
        deviceId = id,
        deviceName = "갤럭시 탭 S9",
        updatedAtEpochMs = 1_700_000_000_000,
        books = listOf(
            BookRecord(
                displayName = "데미안.epub",
                sizeBytes = 12_345,
                title = "데미안",
                progress = ProgressRecord(Locator.encode(Locator.Reflow(4, 1200)), 61f, 1_699_000_000_000),
                finishedAtEpochMs = null,
                bookmarks = listOf(BookmarkRecord(Locator.encode(Locator.Reflow(1, 0)), "싱클레어", 5)),
            ),
        ),
        comics = listOf(
            ComicRecord("옛날 만화 1권.cbz", 999, page = 127, pageCount = 180, offset = 0.25f, updatedAtEpochMs = 7, bookmarks = listOf(ComicBookmarkRecord(3, 1))),
        ),
    )

    @Test
    fun `what one device writes another reads back unchanged`() {
        val back = SyncCodec.decode(SyncCodec.encode(sample), expectedDeviceId = id)
        assertEquals(sample, back)
    }

    @Test
    fun `highlights and memos never travel between devices`() {
        // 결정 8-3: 읽은 자리 · 책갈피 · 다 읽은 때만. 형광펜이 들어 있으면(손으로 고친 파일 · 다른 판) 읽을 때도 버린다.
        val note = AnnotationRecord(Locator.encode(Locator.Reflow(0, 0)), Locator.encode(Locator.Reflow(0, 5)), "Yellow", "메모", "첫 글", 1)
        val withNotes = sample.copy(books = sample.books.map { it.copy(annotations = listOf(note)) })
        val text = SyncCodec.encode(withNotes)
        assertTrue("메모" !in text, "형광펜 메모가 파일에 적혔다")
        val records = JSONObject(text).getJSONObject("records")
        records.getJSONArray("books").getJSONObject(0).put("annotations", org.json.JSONArray().put(JSONObject().put("start", "r:0:0").put("end", "r:0:5").put("color", "Yellow").put("snippet", "x")))
        val sneaked = JSONObject(text).put("records", records).toString()
        assertTrue(SyncCodec.decode(sneaked, id)!!.books.single().annotations.isEmpty())
    }

    @Test
    fun `broken json, another app's json and a wrong device name are each just skipped`() {
        assertNull(SyncCodec.decode("{\"format\":\"olo-ebook-sync\",\"device\":", id))
        assertNull(SyncCodec.decode("", id))
        assertNull(SyncCodec.decode("[1,2,3]", id))
        // 다른 앱의 JSON(형식 표시가 다름) · 이 앱의 백업 파일은 기기 파일이 아니다.
        assertNull(SyncCodec.decode(JSONObject().put("format", "olo-ebook-reading-records").put("device", id).toString(), id))
        // 이름(파일)과 안의 기기가 다르다 — 사람이 다른 기기 파일을 복사해 이름만 바꿨다. 같은 기기가 둘로 보이지 않게 버린다.
        assertNull(SyncCodec.decode(SyncCodec.encode(sample), expectedDeviceId = "11111111-2222-3333-4444-555555555555"))
        // 기기 id 가 UUID 모양이 아니면 버린다.
        assertNull(SyncCodec.decode(SyncCodec.encode(sample.copy(deviceId = "../../etc"))))
    }

    @Test
    fun `unknown fields from a newer version are ignored and missing parts read as empty`() {
        val newer = JSONObject(SyncCodec.encode(sample)).put("version", 9).put("reading-minutes", 42).toString()
        assertEquals(sample, SyncCodec.decode(newer, id))
        // 기록 칸이 없어도(막 켠 기기 · 깨진 기록) 기기는 보인다 — "함께 읽는 기기" 에 이름이 나와야 한다.
        val bare = JSONObject().put("format", SyncCodec.FORMAT).put("device", id).toString()
        val read = assertNotNull(SyncCodec.decode(bare, id))
        assertEquals(SyncCodec.UNKNOWN_DEVICE, read.deviceName)
        assertTrue(read.books.isEmpty() && read.comics.isEmpty())
        // 상한 책갈피 하나는 그 줄만 버린다.
        val records = JSONObject(SyncCodec.encode(sample)).getJSONObject("records")
        records.getJSONArray("books").getJSONObject(0).getJSONArray("bookmarks").put(JSONObject().put("locator", "엉뚱"))
        val damaged = JSONObject(SyncCodec.encode(sample)).put("records", records).toString()
        assertEquals(1, SyncCodec.decode(damaged, id)!!.books.single().bookmarks.size)
    }

    @Test
    fun `only plain device file names are read and sync-app conflict copies are not`() {
        assertEquals(id, SyncCodec.deviceIdOf("$id.json"))
        assertEquals(id, SyncCodec.deviceIdOf("${id.uppercase()}.json"))
        listOf(
            "$id (1).json",
            "$id.sync-conflict-20261008-101010-ABCDEFG.json",
            "$id - 복사본.json",
            "${id}_conflict.json",
            "$id.json.tmp",
            "notes.json",
            ".$id.json",
        ).forEach { assertNull(SyncCodec.deviceIdOf(it), it) }
    }
}
