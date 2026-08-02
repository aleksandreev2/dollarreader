package com.dollarreader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TitleEntity::class,
        VolumeEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        ChapterStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DollarReaderDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var instance: DollarReaderDatabase? = null

        fun getInstance(context: Context): DollarReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DollarReaderDatabase::class.java,
                    "dollarreader.db",
                ).build().also { instance = it }
            }
    }
}
