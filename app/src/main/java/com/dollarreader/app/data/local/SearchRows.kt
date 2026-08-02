package com.dollarreader.app.data.local

data class IndexedChapterFingerprintRow(
    val chapterId: String,
    val titleName: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val contentHash: String,
)

data class LibrarySearchResultRow(
    val titleId: String,
    val titleName: String,
    val chapterId: String,
    val chapterName: String,
    val chapterSortOrder: Int,
    val paragraphIndex: Int,
    val excerpt: String,
)
