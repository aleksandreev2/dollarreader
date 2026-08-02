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
        ReaderPreferencesEntity::class,
        ChapterPositionEntity::class,
        ReadingAnnotationEntity::class,
    ],
    version = 4,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reader_preferences` (
                        `id` INTEGER NOT NULL,
                        `fontFamily` TEXT NOT NULL,
                        `fontSizeSp` REAL NOT NULL,
                        `lineHeightMultiplier` REAL NOT NULL,
                        `paragraphSpacingDp` REAL NOT NULL,
                        `firstLineIndentEm` REAL NOT NULL,
                        `contentWidthDp` INTEGER NOT NULL,
                        `horizontalPaddingDp` INTEGER NOT NULL,
                        `colorTheme` TEXT NOT NULL,
                        `showChapterTitle` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO `reader_preferences` (
                        `id`,
                        `fontFamily`,
                        `fontSizeSp`,
                        `lineHeightMultiplier`,
                        `paragraphSpacingDp`,
                        `firstLineIndentEm`,
                        `contentWidthDp`,
                        `horizontalPaddingDp`,
                        `colorTheme`,
                        `showChapterTitle`,
                        `updatedAt`
                    ) VALUES (0, 'SERIF', 18.0, 1.6, 16.0, 1.25, 720, 24, 'SYSTEM', 1, 0)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chapter_positions` (
                        `chapterId` TEXT NOT NULL,
                        `titleId` TEXT NOT NULL,
                        `firstVisibleItemIndex` INTEGER NOT NULL,
                        `firstVisibleItemScrollOffset` INTEGER NOT NULL,
                        `progress` REAL NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`chapterId`),
                        FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_positions_titleId` ON `chapter_positions` (`titleId`)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_annotations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `titleId` TEXT NOT NULL,
                        `chapterId` TEXT NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `startOffset` INTEGER NOT NULL,
                        `endOffset` INTEGER NOT NULL,
                        `selectedText` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `noteText` TEXT,
                        `color` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`titleId`) REFERENCES `titles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`chapterId`) REFERENCES `chapters`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_annotations_titleId` ON `reading_annotations` (`titleId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_annotations_chapterId` ON `reading_annotations` (`chapterId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reading_annotations_chapterId_paragraphIndex` ON `reading_annotations` (`chapterId`, `paragraphIndex`)",
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
