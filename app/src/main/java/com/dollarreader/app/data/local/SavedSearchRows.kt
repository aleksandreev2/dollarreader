package com.dollarreader.app.data.local

data class ReadingAnnotationOverviewRow(
    val id: Long,
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val paragraphIndex: Int,
    val selectedText: String,
    val type: String,
    val noteText: String?,
    val updatedAt: Long,
)

data class SearchableChapterRow(
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val localUri: String,
    val contentHash: String,
)
