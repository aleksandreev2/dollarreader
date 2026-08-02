package com.dollarreader.app.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val currentChapter: Int,
    val totalChapters: Int,
    val progress: Float,
    val format: String,
    val accentSeed: Int,
    val lastOpenedAt: Long?,
    val isFavorite: Boolean,
)
