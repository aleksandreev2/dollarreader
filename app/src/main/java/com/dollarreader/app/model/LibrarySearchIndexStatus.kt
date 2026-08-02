package com.dollarreader.app.model

data class LibrarySearchIndexStatus(
    val indexedChapters: Int = 0,
    val indexedParagraphs: Int = 0,
    val expectedChapters: Int = 0,
    val isRebuilding: Boolean = false,
    val lastError: String? = null,
) {
    val isComplete: Boolean
        get() = expectedChapters == 0 || indexedChapters >= expectedChapters
}
