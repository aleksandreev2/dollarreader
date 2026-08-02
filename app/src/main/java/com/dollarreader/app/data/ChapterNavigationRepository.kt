package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.ChapterStateEntity
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingProgressEntity

class ChapterNavigationRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    suspend fun moveToAdjacentChapter(
        titleId: String,
        currentChapterId: String,
        forward: Boolean,
    ): Boolean {
        val chapters = dao.chaptersByTitle(titleId)
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }
        if (currentIndex < 0) return false

        val targetIndex = if (forward) currentIndex + 1 else currentIndex - 1
        val target = chapters.getOrNull(targetIndex) ?: return false
        val current = chapters[currentIndex]
        val targetState = dao.chapterStateById(target.id)
        val targetPosition = dao.chapterPositionById(target.id)
        val now = System.currentTimeMillis()

        database.withTransaction {
            if (forward) {
                dao.upsertChapterStates(
                    listOf(
                        ChapterStateEntity(
                            chapterId = current.id,
                            titleId = titleId,
                            progress = 1f,
                            isRead = true,
                            updatedAt = now,
                        ),
                    ),
                )
            }

            val targetProgress = when {
                targetState?.isRead == true -> 1f
                targetState != null -> targetState.progress.coerceIn(0f, 1f)
                targetPosition != null -> targetPosition.progress.coerceIn(0f, 1f)
                else -> 0f
            }
            dao.upsertReadingProgress(
                ReadingProgressEntity(
                    titleId = titleId,
                    currentChapterId = target.id,
                    chapterNumber = target.sortOrder,
                    chapterProgress = targetProgress,
                    scrollOffset = targetPosition
                        ?.firstVisibleItemScrollOffset
                        ?.coerceAtLeast(0)
                        ?: 0,
                    locator = targetPosition?.let { position ->
                        "${position.firstVisibleItemIndex.coerceAtLeast(0)}:" +
                            position.firstVisibleItemScrollOffset.coerceAtLeast(0)
                    },
                    updatedAt = now,
                ),
            )
            dao.touchTitle(titleId, now)
        }
        return true
    }
}
