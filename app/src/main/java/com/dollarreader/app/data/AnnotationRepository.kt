package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.ReadingAnnotationEntity
import com.dollarreader.app.data.local.ReadingAnnotationOverviewRow
import com.dollarreader.app.data.local.SearchableChapterRow
import com.dollarreader.app.model.LibrarySearchResult
import com.dollarreader.app.model.ReaderTextSelection
import com.dollarreader.app.model.ReadingAnnotation
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.model.SavedLibraryItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AnnotationRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    val savedItems: Flow<List<SavedLibraryItem>> =
        dao.observeAllReadingAnnotations().map { rows ->
            rows.map { row -> row.toSavedItem() }
        }

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

    suspend fun deleteAnnotation(annotationId: Long): Boolean =
        dao.deleteReadingAnnotation(annotationId) > 0

    suspend fun searchLibrary(
        query: String,
        titleId: String? = null,
        limit: Int = SEARCH_RESULT_LIMIT,
    ): List<LibrarySearchResult> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_SEARCH_LENGTH) return@withContext emptyList()

        val queryLowercase = normalizedQuery.lowercase(Locale.ROOT)
        val results = ArrayList<LibrarySearchResult>(minOf(limit, SEARCH_RESULT_LIMIT))
        val chapters = dao.searchableChapters(titleId)

        for (chapter in chapters) {
            val source = File(chapter.localUri)
            if (!source.isFile) continue
            val text = runCatching { source.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val paragraphs = text.toReaderParagraphs(chapter.chapterName)
            var matchesInChapter = 0

            for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
                val matchIndex = paragraph.lowercase(Locale.ROOT).indexOf(queryLowercase)
                if (matchIndex < 0) continue

                results += chapter.toSearchResult(
                    paragraphIndex = paragraphIndex,
                    excerpt = paragraph.buildSearchExcerpt(
                        matchIndex = matchIndex,
                        matchLength = normalizedQuery.length,
                    ),
                )
                matchesInChapter += 1
                if (results.size >= limit.coerceIn(1, SEARCH_RESULT_LIMIT)) {
                    return@withContext results
                }
                if (matchesInChapter >= MAX_MATCHES_PER_CHAPTER) break
            }
        }
        results
    }

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

    private fun SearchableChapterRow.toSearchResult(
        paragraphIndex: Int,
        excerpt: String,
    ): LibrarySearchResult = LibrarySearchResult(
        titleId = titleId,
        titleName = titleName,
        chapterId = chapterId,
        chapterName = chapterName,
        chapterSortOrder = chapterSortOrder,
        paragraphIndex = paragraphIndex,
        excerpt = excerpt,
    )

    private fun String.toReaderParagraphs(chapterTitle: String): List<String> {
        val paragraphs = trim()
            .split(Regex("""\n[\t ]*\n+"""))
            .map { paragraph -> paragraph.trim() }
            .filter(String::isNotBlank)
            .toMutableList()

        val first = paragraphs.firstOrNull()
        if (first != null && isHeadingDuplicate(first, chapterTitle)) {
            paragraphs.removeAt(0)
        }
        return paragraphs
    }

    private fun isHeadingDuplicate(firstParagraph: String, chapterTitle: String): Boolean {
        val first = normalizeHeading(firstParagraph.lineSequence().firstOrNull().orEmpty())
        val title = normalizeHeading(chapterTitle)
        return first == title || title.endsWith(first) || first.endsWith(title)
    }

    private fun normalizeHeading(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun String.buildSearchExcerpt(
        matchIndex: Int,
        matchLength: Int,
    ): String {
        val start = (matchIndex - EXCERPT_BEFORE_MATCH).coerceAtLeast(0)
        val end = (matchIndex + matchLength + EXCERPT_AFTER_MATCH).coerceAtMost(length)
        return buildString {
            if (start > 0) append('…')
            append(substring(start, end).replace(Regex("""\s+"""), " ").trim())
            if (end < length) append('…')
        }
    }

    private companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = "YELLOW"
        const val DEFAULT_NOTE_COLOR = "PURPLE"
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_RESULT_LIMIT = 100
        const val MAX_MATCHES_PER_CHAPTER = 3
        const val EXCERPT_BEFORE_MATCH = 60
        const val EXCERPT_AFTER_MATCH = 120
    }
}
