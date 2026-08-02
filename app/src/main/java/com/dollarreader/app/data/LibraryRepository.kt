package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.importer.LocalTitleImport
import com.dollarreader.app.data.local.ChapterEntity
import com.dollarreader.app.data.local.ChapterStateEntity
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingProgressEntity
import com.dollarreader.app.data.local.TitleEntity
import com.dollarreader.app.data.local.TitleSummaryRow
import com.dollarreader.app.data.local.TitleWithVolumes
import com.dollarreader.app.data.local.VolumeEntity
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.BookChapterContents
import com.dollarreader.app.model.BookContents
import com.dollarreader.app.model.BookVolumeContents
import com.dollarreader.app.model.ReaderChapterContent
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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

    fun observeBookContents(titleId: String): Flow<BookContents?> =
        combine(
            dao.observeTitleWithVolumes(titleId),
            dao.observeChapterStates(titleId),
        ) { title, states ->
            title?.let { currentTitle ->
                val stateByChapter = states.associateBy { it.chapterId }
                BookContents(
                    volumes = currentTitle.volumes
                        .sortedBy { it.volume.sortOrder }
                        .map { volume ->
                            BookVolumeContents(
                                id = volume.volume.id,
                                name = volume.volume.name,
                                number = volume.volume.number,
                                sortOrder = volume.volume.sortOrder,
                                chapters = volume.chapters
                                    .sortedBy { it.sortOrder }
                                    .map { chapter ->
                                        val state = stateByChapter[chapter.id]
                                        BookChapterContents(
                                            id = chapter.id,
                                            title = chapter.name,
                                            number = chapter.number,
                                            sortOrder = chapter.sortOrder,
                                            isRead = state?.isRead == true,
                                            progress = state?.progress?.coerceIn(0f, 1f) ?: 0f,
                                        )
                                    },
                            )
                        },
                )
            }
        }

    suspend fun titleExists(titleId: String): Boolean = dao.titleById(titleId) != null

    suspend fun seedDemoLibraryIfEmpty() {
        database.withTransaction {
            if (dao.titleCount() != 0) return@withTransaction

            val now = System.currentTimeMillis()
            val seeds = listOf(
                DemoTitle("solo-leveling", "Поднятие уровня в одиночку", "Чугон", "EPUB", 200, 48, 0.24f, 0),
                DemoTitle("omniscient-reader", "Всеведущий читатель", "Sing N Song", "TXT", 188, 12, 0.06f, 1),
                DemoTitle("north-blade", "Легенда о северном клинке", "Угак", "FB2", 96, 7, 0.07f, 2),
            )

            dao.insertTitles(
                seeds.map { seed ->
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
                },
            )
            dao.insertVolumes(
                seeds.map { seed ->
                    VolumeEntity(seed.volumeId, seed.id, "Том 1", "1", 1)
                },
            )
            dao.insertChapters(
                seeds.flatMap { seed ->
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
                },
            )

            seeds.forEach { seed ->
                val exactProgress = seed.overallProgress * seed.totalChapters
                val chapterProgress = (exactProgress - (seed.currentChapter - 1)).coerceIn(0f, 1f)
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
                val states = (1 until seed.currentChapter).map { chapterNumber ->
                    ChapterStateEntity(
                        chapterId = seed.chapterId(chapterNumber),
                        titleId = seed.id,
                        progress = 1f,
                        isRead = true,
                        updatedAt = now,
                    )
                }
                if (states.isNotEmpty()) dao.upsertChapterStates(states)
            }
        }
    }

    suspend fun importLocalTitle(plan: LocalTitleImport): Boolean {
        require(plan.volumes.isNotEmpty()) { "Import plan must contain at least one volume" }
        require(plan.volumes.any { it.chapters.isNotEmpty() }) { "Import plan must contain chapters" }

        return database.withTransaction {
            val existingTitle = dao.titleById(plan.id)
            val existingProgress = dao.progressByTitle(plan.id)
            val now = System.currentTimeMillis()
            val chapterImports = plan.volumes.flatMap { it.chapters }
            val activeChapterIds = chapterImports.map { it.id }
            val activeVolumeIds = plan.volumes.map { it.id }

            dao.upsertTitle(
                TitleEntity(
                    id = plan.id,
                    title = plan.title,
                    author = plan.author,
                    format = plan.format,
                    sourceType = "local",
                    sourceUri = plan.sourceUri,
                    description = existingTitle?.description,
                    coverUri = existingTitle?.coverUri,
                    accentSeed = existingTitle?.accentSeed ?: positiveAccentSeed(plan.id),
                    isFavorite = existingTitle?.isFavorite ?: false,
                    createdAt = existingTitle?.createdAt ?: now,
                    updatedAt = now,
                    lastOpenedAt = existingTitle?.lastOpenedAt,
                ),
            )

            if (existingTitle != null) {
                dao.shiftChapterSortOrders(plan.id, SORT_ORDER_SHIFT)
                dao.shiftVolumeSortOrders(plan.id, SORT_ORDER_SHIFT)
            }

            dao.upsertVolumes(
                plan.volumes.map { volume ->
                    VolumeEntity(volume.id, plan.id, volume.name, volume.number, volume.sortOrder)
                },
            )
            dao.upsertChapters(
                plan.volumes.flatMap { volume ->
                    volume.chapters.map { chapter ->
                        ChapterEntity(
                            id = chapter.id,
                            titleId = plan.id,
                            volumeId = volume.id,
                            name = chapter.name,
                            number = chapter.number,
                            sortOrder = chapter.sortOrder,
                            localUri = chapter.localPath,
                            contentHash = chapter.contentHash,
                            wordCount = chapter.wordCount,
                            isDownloaded = true,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                },
            )

            dao.deleteChaptersNotIn(plan.id, activeChapterIds)
            dao.deleteVolumesNotIn(plan.id, activeVolumeIds)

            val preservedProgress = existingProgress?.takeIf { progress ->
                progress.currentChapterId == null || progress.currentChapterId in activeChapterIds
            }
            if (preservedProgress == null) {
                val firstChapter = chapterImports.minBy { it.sortOrder }
                dao.upsertReadingProgress(
                    ReadingProgressEntity(
                        titleId = plan.id,
                        currentChapterId = firstChapter.id,
                        chapterNumber = firstChapter.sortOrder,
                        chapterProgress = 0f,
                        scrollOffset = 0,
                        locator = null,
                        updatedAt = now,
                    ),
                )
            } else {
                val reorderedChapter = chapterImports.firstOrNull { it.id == preservedProgress.currentChapterId }
                if (reorderedChapter != null && reorderedChapter.sortOrder != preservedProgress.chapterNumber) {
                    dao.upsertReadingProgress(
                        preservedProgress.copy(chapterNumber = reorderedChapter.sortOrder, updatedAt = now),
                    )
                }
            }
            existingTitle != null
        }
    }

    suspend fun openChapter(titleId: String, sortOrder: Int) {
        val chapter = dao.chapterByOrder(titleId, sortOrder) ?: return
        val existing = dao.chapterStateById(chapter.id)
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.upsertReadingProgress(
                ReadingProgressEntity(
                    titleId = titleId,
                    currentChapterId = chapter.id,
                    chapterNumber = chapter.sortOrder,
                    chapterProgress = existing?.progress?.takeIf { !existing.isRead } ?: 0f,
                    scrollOffset = 0,
                    locator = null,
                    updatedAt = now,
                ),
            )
            dao.touchTitle(titleId, now)
        }
    }

    suspend fun loadChapter(titleId: String, sortOrder: Int): ReaderChapterContent? =
        withContext(Dispatchers.IO) {
            val chapter = dao.chapterByOrder(titleId, sortOrder) ?: return@withContext null
            val text = chapter.localUri?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
            }
            ReaderChapterContent(chapter.id, chapter.name, chapter.sortOrder, text)
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
        val previousCurrentState = dao.chapterStateById(currentChapter.id)
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
            val completed = if (completedThrough > 0) dao.chaptersUpTo(titleId, completedThrough) else emptyList()
            val currentIsRead = previousCurrentState?.isRead == true || chapterProgress >= 1f
            val currentStoredProgress = if (currentIsRead) 1f else maxOf(previousCurrentState?.progress ?: 0f, chapterProgress)
            val states = completed.map { chapter ->
                ChapterStateEntity(chapter.id, titleId, 1f, true, now)
            } + ChapterStateEntity(
                chapterId = currentChapter.id,
                titleId = titleId,
                progress = currentStoredProgress,
                isRead = currentIsRead,
                updatedAt = now,
            )
            dao.upsertChapterStates(states.distinctBy { it.chapterId })
            dao.touchTitle(titleId, now)
        }
    }

    private fun positiveAccentSeed(value: String): Int = value.hashCode().and(Int.MAX_VALUE) % 8

    private companion object {
        const val SORT_ORDER_SHIFT = 1_000_000
    }
}

private fun TitleSummaryRow.toBook(): Book {
    val total = totalChapters.coerceAtLeast(0)
    val current = if (total == 0) 0 else currentChapter.coerceIn(1, total)
    val totalProgress = if (total == 0) 0f else ((current - 1) + chapterProgress.coerceIn(0f, 1f)) / total
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
