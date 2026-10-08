package io.github.kgcaudit.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * 독서 기록(0.51.0)의 저장: 리더가 잰 시간이 하루 합으로 쌓인다. 날 · 방식이 섞이면 달력의 칸과 "읽은 시간" 이 틀린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingTimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db: ReaderDatabase = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private val seoul = ZoneId.of("Asia/Seoul")
    private val time = ReadingTime(db) { seoul }

    @After
    fun close() = db.close()

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    private suspend fun total(id: String, day: Int, mode: TimeMode = TimeMode.READ) =
        time.all().filter { it.id == id && it.day == LocalDate.of(2026, 10, day) && it.mode == mode }.sumOf { it.millis }

    @Test
    fun `pages read on the same day add up into one day and the next day starts its own`() = runTest {
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 60_000, at(7, 9))
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 90_000, at(7, 22))
        // 자정을 넘겨 읽은 쪽은 이 휴대폰 시간대의 다음 날이다(UTC 로 세면 아직 7일이다).
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 30_000, at(8, 0, 30))

        assertEquals(150_000, total("a", 7))
        assertEquals(30_000, total("a", 8))
        // 하루 합이라 행은 둘뿐이다 — 넘긴 쪽마다 행을 쌓지 않는다.
        assertEquals(2, db.readingTime().all().size)
    }

    @Test
    fun `reading, listening and auto turning are kept apart`() = runTest {
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 60_000, at(7, 9))
        time.record(TimeItem.BOOK, "a", TimeMode.LISTEN, 600_000, at(7, 9))
        time.record(TimeItem.BOOK, "a", TimeMode.AUTO, 45_000, at(7, 9))
        // 같은 문서 주소라도 만화 단위는 다른 것이다.
        time.record(TimeItem.COMIC, "a", TimeMode.READ, 5_000, at(7, 9))

        assertEquals(60_000, time.all().filter { it.item == TimeItem.BOOK && it.mode == TimeMode.READ }.sumOf { it.millis })
        assertEquals(600_000, total("a", 7, TimeMode.LISTEN))
        assertEquals(45_000, total("a", 7, TimeMode.AUTO))
        assertEquals(5_000, time.all().single { it.item == TimeItem.COMIC }.millis)
    }

    @Test
    fun `a clock jump cannot make a day longer than a day and a negative time is dropped`() = runTest {
        // 시계를 뒤로 돌렸다(음수) · 잠든 사이 시각이 뛰었다(30시간). 깨진 값이 한 해 통계를 덮지 않는다.
        time.record(TimeItem.BOOK, "a", TimeMode.READ, -5_000, at(7, 9))
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 0, at(7, 9))
        assertEquals(0, db.readingTime().all().size)

        time.record(TimeItem.BOOK, "a", TimeMode.READ, 30 * 3_600_000L, at(7, 9))
        assertEquals(ReadingTime.DAY_MS, total("a", 7))
        time.record(TimeItem.BOOK, "a", TimeMode.READ, 60_000, at(7, 10))
        assertEquals(ReadingTime.DAY_MS, total("a", 7), "하루 합이 하루를 넘었다")
    }

    @Test
    fun `importing the same day twice keeps the larger time instead of doubling`() = runTest {
        val dao = db.readingTime()
        val day = LocalDate.of(2026, 10, 7).toEpochDay()
        dao.add("BOOK", "a", day, "READ", 1_000, ReadingTime.DAY_MS)
        dao.mergeMax("BOOK", "a", day, "READ", 5_000, ReadingTime.DAY_MS)
        dao.mergeMax("BOOK", "a", day, "READ", 5_000, ReadingTime.DAY_MS)
        assertEquals(5_000, dao.all().single().millis)
        // 이 휴대폰 쪽이 더 크면 그대로 둔다.
        dao.mergeMax("BOOK", "a", day, "READ", 2_000, ReadingTime.DAY_MS)
        assertEquals(5_000, dao.all().single().millis)
    }

    @Test
    fun `a row with an unknown mode is kept but not read as reading`() = runTest {
        // 다음 판이 더한 방식(예: 소리 내어 읽기)을 옛 판이 읽었다. 행은 두되 "읽은 시간" 에 더하지 않는다.
        db.readingTime().add("BOOK", "a", LocalDate.of(2026, 10, 7).toEpochDay(), "ALOUD", 9_000, ReadingTime.DAY_MS)
        db.readingTime().add("SHELF", "a", LocalDate.of(2026, 10, 7).toEpochDay(), "READ", 9_000, ReadingTime.DAY_MS)
        val rows = time.all()
        assertEquals(1, rows.size, "모르는 갈래의 행을 읽었다")
        assertEquals(null, rows.single().mode)
    }
}
