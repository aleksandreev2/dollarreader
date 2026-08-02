package com.dollarreader.app.model

data class SavedLibraryItem(
    val id: Long,
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val paragraphIndex: Int,
    val selectedText: String,
    val type: ReadingAnnotationType,
    val noteText: String?,
    val updatedAt: Long,
)

data class LibrarySearchResult(
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val paragraphIndex: Int,
    val excerpt: String,
)
