package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.ChapterPositionEntity
import com.dollarreader.app.data.local.ChapterStateEntity
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReaderPreferencesEntity
import com.dollarreader.app.data.local.ReadingProgressEntity
import com.dollarreader.app.model.ChapterReadingPosition
import com.dollarreader.app.model.ReaderColorTheme
import com.dollarreader.app.model.ReaderFontOption
import com.dollarreader.app.model.ReaderPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ReaderSettingsRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    val preferences: Flow<ReaderPreferences> = dao.observeReaderPreferences()
        .map { entity -> entity?.toModel() ?: ReaderPreferences.Default }
        .distinctUntilChanged()

    suspend fun savePreferences(preferences: ReaderPreferences) {
        val normalized = preferences.normalized()
        dao.upsertReaderPreferences(
            ReaderPreferencesEntity(
                id = 0,
                fontFamily = normalized.font.name,
                fontSizeSp = normalized.fontSizeSp,
                lineHeightMultiplier = normalized.lineHeightMultiplier,
                paragraphSpacingDp = normalized.paragraphSpacingDp,
                firstLineIndentEm = normalized.firstLineIndentEm,
                contentWidthDp = normalized.contentWidthDp,
                horizontalPaddingDp = normalized.horizontalPaddingDp,
                colorTheme = normalized.colorTheme.name,
                showChapterTitle = normalized.showChapterTitle,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun loadPosition(chapterId: String): ChapterReadingPosition? =
        dao.chapterPositionById(chapterId)?.let { position ->
            ChapterReadingPosition(
                chapterId = position.chapterId,
                firstVisibleItemIndex = position.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = position.firstVisibleItemScrollOffset,
                progress = position.progress.coerceIn(0f, 1f),
                updatedAt = position.updatedAt,
            )
        }

    suspend fun savePosition(
        titleId: String,
        chapterNumber: Int,
        position: ChapterReadingPosition,
    ) {
        val normalizedProgress = position.progress.coerceIn(0f, 1f)
        val normalizedItemIndex = position.firstVisibleItemIndex.coerceAtLeast(0)
        val normalizedScrollOffset = position.firstVisibleItemScrollOffset.coerceAtLeast(0)
        val now = System.currentTimeMillis()

        database.withTransaction {
            val previousState = dao.chapterStateById(position.chapterId)
            val isRead = previousState?.isRead == true || normalizedProgress >= READ_THRESHOLD
            val storedProgress = if (isRead) 1f else normalizedProgress

            dao.upsertChapterPosition(
                ChapterPositionEntity(
                    chapterId = position.chapterId,
                    titleId = titleId,
                    firstVisibleItemIndex = normalizedItemIndex,
                    firstVisibleItemScrollOffset = normalizedScrollOffset,
                    progress = normalizedProgress,
                    updatedAt = now,
                ),
            )
            dao.upsertChapterStates(
                listOf(
                    ChapterStateEntity(
                        chapterId = position.chapterId,
                        titleId = titleId,
                        progress = storedProgress,
                        isRead = isRead,
                        updatedAt = now,
                    ),
                ),
            )
            dao.upsertReadingProgress(
                ReadingProgressEntity(
                    titleId = titleId,
                    currentChapterId = position.chapterId,
                    chapterNumber = chapterNumber,
                    chapterProgress = storedProgress,
                    scrollOffset = normalizedScrollOffset,
                    locator = "$normalizedItemIndex:$normalizedScrollOffset",
                    updatedAt = now,
                ),
            )
            dao.touchTitle(titleId, now)
        }
    }

    private fun ReaderPreferencesEntity.toModel(): ReaderPreferences = ReaderPreferences(
        font = enumValueOrDefault(fontFamily, ReaderFontOption.SERIF),
        fontSizeSp = fontSizeSp,
        lineHeightMultiplier = lineHeightMultiplier,
        paragraphSpacingDp = paragraphSpacingDp,
        firstLineIndentEm = firstLineIndentEm,
        contentWidthDp = contentWidthDp,
        horizontalPaddingDp = horizontalPaddingDp,
        colorTheme = enumValueOrDefault(colorTheme, ReaderColorTheme.SYSTEM),
        showChapterTitle = showChapterTitle,
    ).normalized()

    private fun ReaderPreferences.normalized(): ReaderPreferences = copy(
        fontSizeSp = fontSizeSp.coerceIn(14f, 30f),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(1.1f, 2.2f),
        paragraphSpacingDp = paragraphSpacingDp.coerceIn(0f, 36f),
        firstLineIndentEm = firstLineIndentEm.coerceIn(0f, 2.5f),
        contentWidthDp = contentWidthDp.coerceIn(320, 1000),
        horizontalPaddingDp = horizontalPaddingDp.coerceIn(8, 56),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private companion object {
        const val READ_THRESHOLD = 0.995f
    }
}
