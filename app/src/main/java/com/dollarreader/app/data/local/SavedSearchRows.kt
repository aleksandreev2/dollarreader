package com.dollarreader.app.data.local

data class ReadingAnnotationOverviewRow(
    val id: Long,
    val titleId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String,
    val type: String,
    val noteText: String?,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleName: String,
    val chapterName: String,
    val chapterSortOrder: Int,
)

data class SearchableChapterRow(
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val localUri: String,
)
