package io.github.kgcaudit.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 라이브러리에 등록된 책 한 권.
 *
 * 기본 키는 SAF 문서 URI 다(`BookId` 와 같은 값). 진도·책갈피는 이 값을 **외래 키 없이**
 * 참조한다 — 외래 키에 CASCADE 를 걸면 폴더 스캔이 책을 한 번 놓치는 순간 책갈피가
 * 같이 지워진다. 그래서 책이 안 보이면 행을 지우지 않고 [missing] 으로 숨긴다.
 */
@Entity(tableName = "books", indices = [Index("folderUri")])
data class BookEntity(
    @PrimaryKey val id: String,
    /** 이 책을 찾은 등록 폴더(트리 URI). 폴더 등록을 풀 때 이 값으로 고른다. */
    val folderUri: String,
    val displayName: String,
    /** `BookFormat` 이름. 모르는 값이 들어 있으면 그 행만 건너뛴다. */
    val format: String,
    /** null 은 "제공자가 알려 주지 않음" 이다. 0 바이트 파일과 다르다. */
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    /** 책을 열어 메타데이터를 읽기 전에는 null. 목록은 그동안 파일 이름을 보여 준다. */
    val title: String?,
    val author: String?,
    val addedAtEpochMs: Long,
    /** 마지막으로 끝까지 성공한 스캔에서 보이지 않았다. 목록에서만 숨긴다. */
    @ColumnInfo(defaultValue = "0") val missing: Boolean,
)

/** 책마다 하나뿐인 이어읽기 위치. */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    /** `Locator.encode` 형식. 사람이 DB 를 열어 봐도 읽힌다. */
    val locator: String,
    val percent: Float,
    val updatedAtEpochMs: Long,
)

/**
 * 책갈피.
 *
 * [locator] 문자열만으로는 정렬할 수 없다. `r:10:5` 가 `r:2:900` 보다 사전순으로 앞에
 * 와서, 10장 책갈피가 2장보다 먼저 나온다. 그래서 읽는 순서를 숫자 세 개로 따로 둔다
 * ([LocatorOrder]). 위치에서 파생되는 값이라 [locator] 와 함께만 쓴다.
 */
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val bookId: String,
    val locator: String,
    val orderMajor: Int,
    val orderMinor: Int,
    val orderPatch: Int,
    val snippet: String?,
    val createdAtEpochMs: Long,
)

/**
 * 최근에 연 책. 책마다 한 행이고, 열 때마다 시각만 바뀐다. [finishedAtEpochMs] 는 다 읽은 때(0.23.0) — 없으면 읽는 중.
 * 다시 열어도 지우지 않는다(다시 읽는 책이 "읽는 중" 줄을 어지럽히지 않게, 사용자 결정).
 */
@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val bookId: String,
    val openedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "NULL") val finishedAtEpochMs: Long? = null,
)

/** 책장 한 칸: 책과 다 읽은 때. */
data class ShelfRow(
    @Embedded val book: BookEntity,
    val finishedAtEpochMs: Long?,
)

/**
 * 형광펜 · 메모.
 *
 * 책갈피처럼 읽는 순서를 숫자로 따로 둔다(시작 위치 기준). [color] 는 `HighlightColor` 이름이다 — 모르는
 * 값(다음 버전이 더한 색을 옛 버전이 읽음)이면 그 행을 버리지 않고 노랑으로 보인다. 칠한 자리와 메모는
 * 멀쩡한데 색 하나 때문에 메모가 사라지면 안 된다.
 */
@Entity(tableName = "annotations", indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val bookId: String,
    /** `Locator.encode` 형식. */
    val start: String,
    val end: String,
    val orderMajor: Int,
    val orderMinor: Int,
    val color: String,
    val note: String?,
    val snippet: String,
    val createdAtEpochMs: Long,
)

/**
 * 만화 단위 하나(압축 파일 하나 · 그림 폴더 하나, 0.33.0). 책([BookEntity])과 표를 나눈다 — 책 표의 형식(`BookFormat`)
 * 으로 넣으면 리더 쪽 갈래(조판할 책인가 · PDF 인가)마다 만화가 끼어든다. 책처럼 **지우지 않고 숨긴다**([missing]).
 *
 * 살핀 결과(`probed*` · `page*` · `info*`)는 압축을 열어 목록만 읽은 것이다. 파일 크기나 수정 시각이 바뀌면 다시 살핀다.
 * 모든 null 은 "모름"(아직 살피지 않음 · 적혀 있지 않음)이다 — 0 · false 와 다르다(규칙 5).
 */
@Entity(tableName = "comic_units", indices = [Index("folderUri")])
data class ComicUnitEntity(
    /** 문서 URI(압축 파일 · 그림 폴더). 진도 · 책갈피 · 손 고침의 열쇠. */
    @PrimaryKey val id: String,
    val folderUri: String,
    val name: String,
    /** `ARCHIVE` · `IMAGE_FOLDER`. */
    val kind: String,
    /** 확장자(소문자). 그림 폴더는 빈 글. 그냥 zip 은 살펴서 그림만 들어 있어야 보인다. */
    val extension: String,
    /** 등록 폴더에서 이 단위가 든 폴더까지의 이름들, [FOLDER_SEPARATOR] 로 잇는다. 작품 이름 · 모은 곳에 쓴다. */
    val folders: String,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    val addedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val missing: Boolean,
    /**
     * 살핀 적이 있다. 크기 · 수정 시각만으로 가리면 둘 다 알려 주지 않는 제공자의 zip 은 살핀 뒤에도 "안 살핌" 으로 보여
     * 영영 목록에 나오지 않는다.
     */
    @ColumnInfo(defaultValue = "0") val probed: Boolean = false,
    /** 마지막으로 살핀 파일의 크기 · 수정 시각. 지금 값과 다르면 다시 살핀다. */
    val probedSize: Long? = null,
    val probedModified: Long? = null,
    /** 살펴 보니 만화가 아니었다(그림만 든 zip 이 아님). 목록에서 뺀다. */
    @ColumnInfo(defaultValue = "0") val notComic: Boolean = false,
    val pageCount: Int? = null,
    /** 표지로 쓸 항목 이름(압축 안 · 폴더 안). */
    val coverEntry: String? = null,
    /** 합본 안 목차: "이름[COUNT_SEPARATOR]쪽 수" 를 [FOLDER_SEPARATOR] 로 이은 것. 없으면 null. */
    val sections: String? = null,
    val infoSeries: String? = null,
    val infoNumber: Double? = null,
    val infoFormat: String? = null,
    val infoRightToLeft: Boolean? = null,
) {
    companion object {
        /** 폴더 이름에 들어갈 수 없는 글자(단위 구분자, U+001F). */
        const val FOLDER_SEPARATOR: String = "\u001F"
        /** [sections] 한 칸 안에서 이름과 쪽 수를 가르는 글자(U+001E). */
        const val COUNT_SEPARATOR: String = "\u001E"
    }
}

/**
 * 만화 묶음을 손으로 고친 것(0.33.0). 다시 훑어도 남는다 — 그래서 단위 URI · 작품 열쇠로 적고, 단위 행이 숨겨져도 지우지
 * 않는다.
 *
 * @param kind `WORK_OF`(단위 → 작품 열쇠: 합치기 · 빼기), `TITLE`(작품 열쇠 → 보이는 이름), `PREFERRED`(같은 권 열쇠 → 고른 단위),
 *   `RTL`(작품 열쇠 → 넘기는 방향 "1"/"0", 0.34.0),
 *   `VIEW`(작품 열쇠 → 보는 방식 `PAGE`/`WEBTOON`, 0.35.0),
 *   `SHELF`(작품 열쇠 → 사람이 옮긴 서재 갈래, `ShelfMark.format`, 0.38.0).
 */
@Entity(tableName = "comic_overrides", primaryKeys = ["kind", "subject"])
data class ComicOverrideEntity(
    val kind: String,
    val subject: String,
    val value: String,
)

/**
 * 만화 한 권(단위)의 읽은 자리(0.34.0). 책의 진도(`progress`)와 따로 둔다 — 책 진도는 글자 자리(Locator)이고 책 표의 행을
 * 가리키지만, 만화는 쪽 번호이고 만화 단위를 가리킨다.
 *
 * @param finishedAtEpochMs 마지막 쪽까지 넘긴 때. null 은 "아직" — 0 과 다르다(규칙 5).
 */
@Entity(tableName = "comic_progress")
data class ComicProgressEntity(
    @PrimaryKey val unitId: String,
    val page: Int,
    val pageCount: Int,
    val updatedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    /** 웹툰: 그 그림 안의 비율(0..1, 0.35.0). null 은 쪽 넘김으로 읽음. */
    val offset: Float? = null,
)

/** 만화 책갈피: 권 하나의 쪽 하나. 같은 쪽에 두 번 꽂지 않는다(열쇠). */
@Entity(tableName = "comic_bookmarks", primaryKeys = ["unitId", "page"])
data class ComicBookmarkEntity(
    val unitId: String,
    val page: Int,
    val createdAtEpochMs: Long,
)
