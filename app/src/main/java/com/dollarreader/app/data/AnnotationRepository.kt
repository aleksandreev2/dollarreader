package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingAnnotationEntity
import com.dollarreader.app.model.ReaderTextSelection
import com.dollarreader.app.model.ReadingAnnotation
import com.dollarreader.app.model.ReadingAnnotationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnnotationRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    fun observeChapterAnnotations(chapterId: String): Flow<List<ReadingAnnotation>> =
        dao.observeReadingAnnotations(chapterId).map { entities ->
            entities.map(ReadingAnnotationEntity::toModel)
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

    suspend fun deleteAnnotation(annotationId: Long): Boolean =
        dao.deleteReadingAnnotation(annotationId) > 0

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

    private companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = "YELLOW"
        const val DEFAULT_NOTE_COLOR = "PURPLE"
    }
}
