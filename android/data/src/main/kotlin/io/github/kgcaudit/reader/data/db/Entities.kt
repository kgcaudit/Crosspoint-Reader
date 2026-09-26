package io.github.kgcaudit.reader.data.db

import androidx.room.ColumnInfo
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

/** 최근에 연 책. 책마다 한 행이고, 열 때마다 시각만 바뀐다. */
@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val bookId: String,
    val openedAtEpochMs: Long,
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
