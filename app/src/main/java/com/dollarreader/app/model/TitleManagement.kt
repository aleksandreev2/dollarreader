package com.dollarreader.app.model

data class TitleManagement(
    val id: String,
    val title: String,
    val author: String,
    val description: String?,
    val format: String,
    val sourceType: String,
    val sourceUri: String?,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val history: List<TitleHistoryItem>,
)

data class TitleHistoryItem(
    val id: Long,
    val eventType: String,
    val details: String,
    val chapterCount: Int,
    val createdAt: Long,
)
