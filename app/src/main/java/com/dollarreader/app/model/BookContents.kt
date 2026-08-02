package com.dollarreader.app.model

data class BookContents(
    val volumes: List<BookVolumeContents>,
)

data class BookVolumeContents(
    val id: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val chapters: List<BookChapterContents>,
)

data class BookChapterContents(
    val id: String,
    val title: String,
    val number: String?,
    val sortOrder: Int,
    val isRead: Boolean,
    val progress: Float,
)
