package com.dollarreader.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "chapter_search_fts")
data class ChapterSearchIndexEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val paragraphIndex: Int,
    val content: String,
    val contentHash: String,
    val updatedAt: Long,
)
