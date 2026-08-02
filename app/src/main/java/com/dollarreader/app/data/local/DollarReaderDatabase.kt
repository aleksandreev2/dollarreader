package com.dollarreader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TitleEntity::class,
        VolumeEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ChapterStateEntity::class,
        UpdateHistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class DollarReaderDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var instance: DollarReaderDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `update_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `titleId` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `details` TEXT NOT NULL,
                        `chapterCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_update_history_titleId` ON `update_history` (`titleId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_update_history_titleId_createdAt` ON `update_history` (`titleId`, `createdAt`)",
                )
            }
        }

        fun getInstance(context: Context): DollarReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DollarReaderDatabase::class.java,
                    "dollarreader.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
