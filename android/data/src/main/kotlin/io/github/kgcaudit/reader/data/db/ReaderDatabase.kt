package io.github.kgcaudit.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ProgressEntity::class, BookmarkEntity::class, RecentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun recent(): RecentDao

    companion object {
        const val FILE_NAME: String = "reader.db"

        /**
         * 앱 전체에서 하나만 연다.
         *
         * `fallbackToDestructiveMigration` 을 **쓰지 않는다.** 마이그레이션을 빠뜨렸을 때
         * 그 옵션은 조용히 DB 를 지우는데, 그러면 사용자의 책갈피가 업데이트 한 번에
         * 사라진다. 차라리 개발 중에 크래시로 드러나는 편이 낫다.
         */
        fun open(context: Context): ReaderDatabase =
            Room.databaseBuilder(context.applicationContext, ReaderDatabase::class.java, FILE_NAME).build()
    }
}
