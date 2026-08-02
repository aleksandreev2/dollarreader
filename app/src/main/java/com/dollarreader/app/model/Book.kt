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
)

val sampleBooks = listOf(
    Book(
        id = "solo-leveling",
        title = "Поднятие уровня в одиночку",
        author = "Чугон",
        currentChapter = 48,
        totalChapters = 200,
        progress = 0.24f,
        format = "EPUB",
        accentSeed = 0,
    ),
    Book(
        id = "omniscient-reader",
        title = "Всеведущий читатель",
        author = "Sing N Song",
        currentChapter = 12,
        totalChapters = 188,
        progress = 0.06f,
        format = "TXT",
        accentSeed = 1,
    ),
    Book(
        id = "north-blade",
        title = "Легенда о северном клинке",
        author = "Угак",
        currentChapter = 7,
        totalChapters = 96,
        progress = 0.07f,
        format = "FB2",
        accentSeed = 2,
    ),
)
