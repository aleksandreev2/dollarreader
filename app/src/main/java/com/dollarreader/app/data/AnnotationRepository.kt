package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingAnnotationEntity
import com.dollarreader.app.data.local.ReadingAnnotationOverviewRow
import com.dollarreader.app.model.LibrarySearchIndexStatus
import com.dollarreader.app.model.LibrarySearchResult
import com.dollarreader.app.model.ReaderTextSelection
import com.dollarreader.app.model.ReadingAnnotation
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.model.SavedLibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnnotationRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()
    private val searchRepository = LibrarySearchRepository(database)

    val savedItems: Flow<List<SavedLibraryItem>> =
        dao.observeAllReadingAnnotations().map { rows ->
            rows.map { row -> row.toSavedItem() }
        }

    val searchIndexStatus: Flow<LibrarySearchIndexStatus> = searchRepository.status

    fun observeChapterAnnotations(chapterId: String): Flow<List<ReadingAnnotation>> =
        dao.observeReadingAnnotations(chapterId).map { entities ->
            entities.map { entity -> entity.toModel() }
        }

    suspend fun addHighlight(
        titleId: String,
        chapterId: String,
        selection: ReaderTextSelection,
    ): Long = saveAnnotation(
        titleId = titleId,
        chapterId = chapterId,
        selection = selection,
        type = ReadingAnnotationType.HIGHLIGHT,
        noteText = null,
        color = DEFAULT_HIGHLIGHT_COLOR,
    )

    suspend fun addNote(
        titleId: String,
        chapterId: String,
        selection: ReaderTextSelection,
        noteText: String,
    ): Long {
        val normalizedNote = noteText.trim()
        require(normalizedNote.isNotEmpty()) { "Заметка не может быть пустой" }
        return saveAnnotation(
            titleId = titleId,
            chapterId = chapterId,
            selection = selection,
            type = ReadingAnnotationType.NOTE,
            noteText = normalizedNote,
            color = DEFAULT_NOTE_COLOR,
        )
    }

    suspend fun addCurrentBookmark(
        titleId: String,
        label: String? = null,
    ): Long {
        val progress = requireNotNull(dao.progressByTitle(titleId)) {
            "Для тайтла ещё не сохранена позиция чтения"
        }
        val chapterId = requireNotNull(progress.currentChapterId) {
            "Не удалось определить текущую главу"
        }
        val chapter = requireNotNull(dao.chapterById(chapterId)) {
            "Текущая глава больше недоступна"
        }
        val preferences = dao.readerPreferencesById()
        val position = dao.chapterPositionById(chapterId)
        val listItemIndex = position?.firstVisibleItemIndex
            ?: progress.locator
                ?.substringBefore(':')
                ?.toIntOrNull()
            ?: 0
        val paragraphOffset = if (preferences?.showChapterTitle != false) 2 else 1
        val paragraphIndex = (listItemIndex - paragraphOffset).coerceAtLeast(0)
        val now = System.currentTimeMillis()

        return database.withTransaction {
            dao.deleteBookmarkAtLocation(
                chapterId = chapterId,
                paragraphIndex = paragraphIndex,
                bookmarkPrefix = BOOKMARK_NOTE_PREFIX,
            )
            dao.insertReadingAnnotation(
                ReadingAnnotationEntity(
                    titleId = titleId,
                    chapterId = chapterId,
                    paragraphIndex = paragraphIndex,
                    startOffset = 0,
                    endOffset = 0,
                    selectedText = chapter.name,
                    type = ReadingAnnotationType.NOTE.name,
                    noteText = encodeBookmarkLabel(label),
                    color = DEFAULT_BOOKMARK_COLOR,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun updateNote(annotationId: Long, noteText: String): Boolean {
        val normalized = noteText.trim()
        require(normalized.isNotEmpty()) { "Заметка не может быть пустой" }
        return dao.updateReadingAnnotationText(
            annotationId = annotationId,
            noteText = normalized,
            updatedAt = System.currentTimeMillis(),
        ) > 0
    }

    suspend fun updateBookmark(annotationId: Long, label: String?): Boolean =
        dao.updateReadingAnnotationText(
            annotationId = annotationId,
            noteText = encodeBookmarkLabel(label),
            updatedAt = System.currentTimeMillis(),
        ) > 0

    suspend fun deleteAnnotation(annotationId: Long): Boolean =
        dao.deleteReadingAnnotation(annotationId) > 0

    suspend fun deleteAnnotations(annotationIds: Collection<Long>): Int {
        val normalized = annotationIds.distinct()
        if (normalized.isEmpty()) return 0
        return dao.deleteReadingAnnotations(normalized)
    }

    suspend fun refreshSearchIndex(force: Boolean = false) {
        searchRepository.refreshIndex(force)
    }

    suspend fun searchLibrary(
        query: String,
        titleId: String? = null,
        limit: Int = SEARCH_RESULT_LIMIT,
    ): List<LibrarySearchResult> = searchRepository.search(query, titleId, limit)

    private suspend fun saveAnnotation(
        titleId: String,
        chapterId: String,
        selection: ReaderTextSelection,
        type: ReadingAnnotationType,
        noteText: String?,
        color: String,
    ): Long {
        val start = minOf(selection.startOffset, selection.endOffset).coerceAtLeast(0)
        val end = maxOf(selection.startOffset, selection.endOffset)
        val selectedText = selection.text
        require(selection.paragraphIndex >= 0) { "Некорректный абзац" }
        require(end > start) { "Нужно выделить текст" }
        require(selectedText.isNotBlank()) { "Нужно выделить текст" }

        val now = System.currentTimeMillis()
        return database.withTransaction {
            dao.deleteReadingAnnotationAtRange(
                chapterId = chapterId,
                paragraphIndex = selection.paragraphIndex,
                startOffset = start,
                endOffset = end,
                type = type.name,
            )
            dao.insertReadingAnnotation(
                ReadingAnnotationEntity(
                    titleId = titleId,
                    chapterId = chapterId,
                    paragraphIndex = selection.paragraphIndex,
                    startOffset = start,
                    endOffset = end,
                    selectedText = selectedText,
                    type = type.name,
                    noteText = noteText,
                    color = color,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun ReadingAnnotationEntity.toModel(): ReadingAnnotation = ReadingAnnotation(
        id = id,
        titleId = titleId,
        chapterId = chapterId,
        paragraphIndex = paragraphIndex,
        startOffset = startOffset,
        endOffset = endOffset,
        selectedText = selectedText,
        type = runCatching { ReadingAnnotationType.valueOf(type) }
            .getOrDefault(ReadingAnnotationType.HIGHLIGHT),
        noteText = noteText,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ReadingAnnotationOverviewRow.toSavedItem(): SavedLibraryItem =
        SavedLibraryItem(
            id = id,
            titleId = titleId,
            titleName = titleName,
            chapterId = chapterId,
            chapterName = chapterName,
            chapterSortOrder = chapterSortOrder,
            paragraphIndex = paragraphIndex,
            selectedText = selectedText,
            type = runCatching { ReadingAnnotationType.valueOf(type) }
                .getOrDefault(ReadingAnnotationType.HIGHLIGHT),
            noteText = noteText,
            updatedAt = updatedAt,
        )

    private companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = "YELLOW"
        const val DEFAULT_NOTE_COLOR = "PURPLE"
        const val DEFAULT_BOOKMARK_COLOR = "BLUE"
        const val SEARCH_RESULT_LIMIT = 120
    }
}
