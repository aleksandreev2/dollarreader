package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.ChapterEntity
import com.dollarreader.app.data.local.ChapterStateEntity
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingProgressEntity
import com.dollarreader.app.data.local.TitleEntity
import com.dollarreader.app.data.local.TitleSummaryRow
import com.dollarreader.app.data.local.TitleWithVolumes
import com.dollarreader.app.data.local.VolumeEntity
import com.dollarreader.app.model.Book
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    val books: Flow<List<Book>> = dao.observeTitleSummaries().map { rows ->
        rows.map(TitleSummaryRow::toBook)
    }

    fun observeBook(titleId: String): Flow<Book?> =
        dao.observeTitleSummary(titleId).map { it?.toBook() }

    fun observeTitle(titleId: String): Flow<TitleWithVolumes?> =
        dao.observeTitleWithVolumes(titleId)

    suspend fun seedDemoLibraryIfEmpty() {
        database.withTransaction {
            if (dao.titleCount() != 0) return@withTransaction

            val now = System.currentTimeMillis()
            val seeds = listOf(
                DemoTitle(
                    id = "solo-leveling",
                    title = "Поднятие уровня в одиночку",
                    author = "Чугон",
                    format = "EPUB",
                    totalChapters = 200,
                    currentChapter = 48,
                    overallProgress = 0.24f,
                    accentSeed = 0,
                ),
                DemoTitle(
                    id = "omniscient-reader",
                    title = "Всеведущий читатель",
                    author = "Sing N Song",
                    format = "TXT",
                    totalChapters = 188,
                    currentChapter = 12,
                    overallProgress = 0.06f,
                    accentSeed = 1,
                ),
                DemoTitle(
                    id = "north-blade",
                    title = "Легенда о северном клинке",
                    author = "Угак",
                    format = "FB2",
                    totalChapters = 96,
                    currentChapter = 7,
                    overallProgress = 0.07f,
                    accentSeed = 2,
                ),
            )

            val titles = seeds.map { seed ->
                TitleEntity(
                    id = seed.id,
                    title = seed.title,
                    author = seed.author,
                    format = seed.format,
                    sourceType = "demo",
                    sourceUri = null,
                    description = null,
                    coverUri = null,
                    accentSeed = seed.accentSeed,
                    isFavorite = false,
                    createdAt = now,
                    updatedAt = now,
                    lastOpenedAt = now - seed.accentSeed * 60_000L,
                )
            }
            val volumes = seeds.map { seed ->
                VolumeEntity(
                    id = seed.volumeId,
                    titleId = seed.id,
                    name = "Том 1",
                    number = "1",
                    sortOrder = 1,
                )
            }
            val chapters = seeds.flatMap { seed ->
                (1..seed.totalChapters).map { chapterNumber ->
                    ChapterEntity(
                        id = seed.chapterId(chapterNumber),
                        titleId = seed.id,
                        volumeId = seed.volumeId,
                        name = "Глава $chapterNumber",
                        number = chapterNumber.toString(),
                        sortOrder = chapterNumber,
                        localUri = null,
                        contentHash = null,
                        wordCount = null,
                        isDownloaded = true,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            }

            dao.insertTitles(titles)
            dao.insertVolumes(volumes)
            dao.insertChapters(chapters)

            seeds.forEach { seed ->
                val exactProgress = seed.overallProgress * seed.totalChapters
                val chapterProgress = (
                    exactProgress - (seed.currentChapter - 1)
                ).coerceIn(0f, 1f)
                dao.upsertReadingProgress(
                    ReadingProgressEntity(
                        titleId = seed.id,
                        currentChapterId = seed.chapterId(seed.currentChapter),
                        chapterNumber = seed.currentChapter,
                        chapterProgress = chapterProgress,
                        scrollOffset = 0,
                        locator = null,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun saveOverallProgress(titleId: String, overallProgress: Float) {
        val totalChapters = dao.chapterCount(titleId)
        if (totalChapters == 0) return

        val normalized = overallProgress.coerceIn(0f, 1f)
        val exact = normalized * totalChapters
        val rounded = exact.roundToInt()
        val isBoundary = rounded > 0 && abs(exact - rounded) < 0.0001f

        val chapterNumber = when {
            normalized >= 1f -> totalChapters
            isBoundary -> rounded
            else -> floor(exact).toInt() + 1
        }.coerceIn(1, totalChapters)

        val chapterProgress = when {
            normalized >= 1f || isBoundary -> 1f
            else -> exact - floor(exact)
        }.coerceIn(0f, 1f)

        val currentChapter = dao.chapterByOrder(titleId, chapterNumber) ?: return
        val now = System.currentTimeMillis()

        database.withTransaction {
            dao.upsertReadingProgress(
                ReadingProgressEntity(
                    titleId = titleId,
                    currentChapterId = currentChapter.id,
                    chapterNumber = chapterNumber,
                    chapterProgress = chapterProgress,
                    scrollOffset = 0,
                    locator = null,
                    updatedAt = now,
                ),
            )

            val completedThrough = if (chapterProgress >= 1f) chapterNumber else chapterNumber - 1
            val completed = if (completedThrough > 0) {
                dao.chaptersUpTo(titleId, completedThrough)
            } else {
                emptyList()
            }
            val states = completed.map { chapter ->
                ChapterStateEntity(
                    chapterId = chapter.id,
                    titleId = titleId,
                    progress = 1f,
                    isRead = true,
                    updatedAt = now,
                )
            } + ChapterStateEntity(
                chapterId = currentChapter.id,
                titleId = titleId,
                progress = chapterProgress,
                isRead = chapterProgress >= 1f,
                updatedAt = now,
            )
            dao.upsertChapterStates(states.distinctBy { it.chapterId })
            dao.touchTitle(titleId, now)
        }
    }
}

private fun TitleSummaryRow.toBook(): Book {
    val total = totalChapters.coerceAtLeast(0)
    val current = if (total == 0) 0 else currentChapter.coerceIn(1, total)
    val totalProgress = if (total == 0) {
        0f
    } else {
        ((current - 1) + chapterProgress.coerceIn(0f, 1f)) / total
    }

    return Book(
        id = title.id,
        title = title.title,
        author = title.author,
        currentChapter = current,
        totalChapters = total,
        progress = totalProgress.coerceIn(0f, 1f),
        format = title.format,
        accentSeed = title.accentSeed,
        lastOpenedAt = title.lastOpenedAt,
        isFavorite = title.isFavorite,
    )
}

private data class DemoTitle(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val totalChapters: Int,
    val currentChapter: Int,
    val overallProgress: Float,
    val accentSeed: Int,
) {
    val volumeId: String = "$id:volume:1"

    fun chapterId(number: Int): String = "$id:chapter:$number"
}
