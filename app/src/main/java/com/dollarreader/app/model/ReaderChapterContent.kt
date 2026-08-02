package com.dollarreader.app.model

data class ReaderChapterContent(
    val id: String,
    val title: String,
    val sortOrder: Int,
    val text: String?,
    val localPath: String? = null,
)
