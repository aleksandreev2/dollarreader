package com.dollarreader.app.data

import androidx.room.withTransaction
import com.dollarreader.app.data.local.ChapterSearchIndexEntity
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.data.local.IndexedChapterFingerprintRow
import com.dollarreader.app.data.local.LibrarySearchResultRow
import com.dollarreader.app.data.local.SearchableChapterRow
import com.dollarreader.app.model.LibrarySearchIndexStatus
import com.dollarreader.app.model.LibrarySearchResult
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibrarySearchRepository(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()
    private val rebuildMutex = Mutex()
    private val buildState = MutableStateFlow(BuildState())

    val status: Flow<LibrarySearchIndexStatus> = combine(
        dao.observeIndexedChapterCount(),
        dao.observeIndexedParagraphCount(),
        dao.observeSearchableChapterCount(),
        buildState,
    ) { indexedChapters, indexedParagraphs, expectedChapters, state ->
        LibrarySearchIndexStatus(
            indexedChapters = indexedChapters,
            indexedParagraphs = indexedParagraphs,
            expectedChapters = expectedChapters,
            isRebuilding = state.isRebuilding,
            lastError = state.lastError,
        )
    }

    suspend fun refreshIndex(force: Boolean = false) = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            buildState.value = BuildState(isRebuilding = true)
            try {
                if (force) dao.clearSearchIndex()

                val chapters = dao.searchableChapters(titleId = null)
                val indexed = dao.indexedChapterFingerprints()
                    .associateBy(IndexedChapterFingerprintRow::chapterId)
                val activeChapterIds = chapters.map(SearchableChapterRow::chapterId)

                if (activeChapterIds.isEmpty()) {
                    dao.clearSearchIndex()
                    buildState.value = BuildState()
                    return@withLock
                }

                chapters.forEach { chapter ->
                    val file = File(chapter.localUri)
                    if (!file.isFile) {
                        dao.deleteSearchDocumentsForChapter(chapter.chapterId)
                        return@forEach
                    }

                    val actualHash = chapter.contentHash
                        .takeIf(String::isNotBlank)
                        ?: file.sha256()
                    val previous = indexed[chapter.chapterId]
                    val unchanged = !force &&
                        previous?.contentHash == actualHash &&
                        previous.titleName == chapter.titleName &&
                        previous.chapterName == chapter.chapterName &&
                        previous.chapterSortOrder == chapter.chapterSortOrder
                    if (unchanged) return@forEach

                    val text = runCatching { file.readText(Charsets.UTF_8) }
                        .getOrElse {
                            dao.deleteSearchDocumentsForChapter(chapter.chapterId)
                            return@forEach
                        }
                    val now = System.currentTimeMillis()
                    val documents = text.toReaderParagraphs(chapter.chapterName)
                        .mapIndexed { paragraphIndex, paragraph ->
                            ChapterSearchIndexEntity(
                                titleId = chapter.titleId,
                                titleName = chapter.titleName,
                                chapterId = chapter.chapterId,
                                chapterName = chapter.chapterName,
                                chapterSortOrder = chapter.chapterSortOrder,
                                paragraphIndex = paragraphIndex,
                                content = paragraph,
                                contentHash = actualHash,
                                updatedAt = now,
                            )
                        }

                    database.withTransaction {
                        dao.deleteSearchDocumentsForChapter(chapter.chapterId)
                        if (documents.isNotEmpty()) {
                            dao.insertSearchDocuments(documents)
                        }
                    }
                }

                dao.deleteSearchDocumentsNotIn(activeChapterIds)
                buildState.value = BuildState()
            } catch (error: Throwable) {
                buildState.value = BuildState(
                    isRebuilding = false,
                    lastError = error.message ?: "Не удалось обновить поисковый индекс",
                )
                throw error
            }
        }
    }

    suspend fun search(
        query: String,
        titleId: String? = null,
        limit: Int = SEARCH_RESULT_LIMIT,
    ): List<LibrarySearchResult> = withContext(Dispatchers.IO) {
        val matchQuery = query.toMatchQuery()
        if (matchQuery.isEmpty()) return@withContext emptyList()

        refreshIndex(force = false)
        dao.searchIndexedLibrary(
            matchQuery = matchQuery,
            titleId = titleId,
            limit = limit.coerceIn(1, SEARCH_RESULT_LIMIT),
        ).map { row -> row.toModel() }
    }

    private fun LibrarySearchResultRow.toModel(): LibrarySearchResult =
        LibrarySearchResult(
            titleId = titleId,
            titleName = titleName,
            chapterId = chapterId,
            chapterName = chapterName,
            chapterSortOrder = chapterSortOrder,
            paragraphIndex = paragraphIndex,
            excerpt = excerpt,
        )

    private fun String.toMatchQuery(): String =
        lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { token -> token.length >= MIN_TOKEN_LENGTH }
            .take(MAX_QUERY_TOKENS)
            .joinToString(" AND ") { token ->
                val escaped = token.replace("\"", "\"\"")
                "\"$escaped\"*"
            }

    private fun String.toReaderParagraphs(chapterTitle: String): List<String> {
        val paragraphs = trim()
            .split(Regex("""\n[\t ]*\n+"""))
            .map(String::trim)
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

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class BuildState(
        val isRebuilding: Boolean = false,
        val lastError: String? = null,
    )

    private companion object {
        const val SEARCH_RESULT_LIMIT = 120
        const val MIN_TOKEN_LENGTH = 2
        const val MAX_QUERY_TOKENS = 8
    }
}
