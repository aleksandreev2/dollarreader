package com.dollarreader.app.model

enum class ReadingAnnotationType {
    HIGHLIGHT,
    NOTE,
}

data class ReaderTextSelection(
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

data class ReadingAnnotation(
    val id: Long,
    val titleId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String,
    val type: ReadingAnnotationType,
    val noteText: String?,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
)
