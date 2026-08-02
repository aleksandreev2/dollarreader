package com.dollarreader.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT
            t.*,
            COUNT(c.id) AS totalChapters,
            COALESCE(r.chapterNumber, 1) AS currentChapter,
            COALESCE(r.chapterProgress, 0.0) AS chapterProgress
        FROM titles AS t
        LEFT JOIN chapters AS c ON c.titleId = t.id
        LEFT JOIN reading_progress AS r ON r.titleId = t.id
        GROUP BY t.id
        ORDER BY COALESCE(t.lastOpenedAt, t.createdAt) DESC, t.title COLLATE NOCASE ASC
        """,
    )
    fun observeTitleSummaries(): Flow<List<TitleSummaryRow>>

    @Query(
        """
        SELECT
            t.*,
            COUNT(c.id) AS totalChapters,
            COALESCE(r.chapterNumber, 1) AS currentChapter,
            COALESCE(r.chapterProgress, 0.0) AS chapterProgress
        FROM titles AS t
        LEFT JOIN chapters AS c ON c.titleId = t.id
        LEFT JOIN reading_progress AS r ON r.titleId = t.id
        WHERE t.id = :titleId
        GROUP BY t.id
        LIMIT 1
        """,
    )
    fun observeTitleSummary(titleId: String): Flow<TitleSummaryRow?>

    @Transaction
    @Query("SELECT * FROM titles WHERE id = :titleId LIMIT 1")
    fun observeTitleWithVolumes(titleId: String): Flow<TitleWithVolumes?>

    @Query("SELECT * FROM titles WHERE id = :titleId LIMIT 1")
    fun observeTitleEntity(titleId: String): Flow<TitleEntity?>

    @Query("SELECT * FROM chapter_states WHERE titleId = :titleId")
    fun observeChapterStates(titleId: String): Flow<List<ChapterStateEntity>>

    @Query(
        "SELECT * FROM update_history WHERE titleId = :titleId ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    fun observeUpdateHistory(titleId: String, limit: Int = 20): Flow<List<UpdateHistoryEntity>>

    @Query("SELECT * FROM reader_preferences WHERE id = 0 LIMIT 1")
    fun observeReaderPreferences(): Flow<ReaderPreferencesEntity?>

    @Query(
        """
        SELECT * FROM reading_annotations
        WHERE chapterId = :chapterId
        ORDER BY paragraphIndex, startOffset, createdAt
        """,
    )
    fun observeReadingAnnotations(chapterId: String): Flow<List<ReadingAnnotationEntity>>

    @Query(
        """
        SELECT
            a.id AS id,
            a.titleId AS titleId,
            t.title AS titleName,
            a.chapterId AS chapterId,
            c.name AS chapterName,
            c.sortOrder AS chapterSortOrder,
            a.paragraphIndex AS paragraphIndex,
            a.selectedText AS selectedText,
            a.type AS type,
            a.noteText AS noteText,
            a.updatedAt AS updatedAt
        FROM reading_annotations AS a
        INNER JOIN titles AS t ON t.id = a.titleId
        INNER JOIN chapters AS c ON c.id = a.chapterId
        ORDER BY a.updatedAt DESC, a.id DESC
        """,
    )
    fun observeAllReadingAnnotations(): Flow<List<ReadingAnnotationOverviewRow>>

    @Query("SELECT COUNT(DISTINCT chapterId) FROM chapter_search_fts")
    fun observeIndexedChapterCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chapter_search_fts")
    fun observeIndexedParagraphCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chapters WHERE localUri IS NOT NULL")
    fun observeSearchableChapterCount(): Flow<Int>

    @Query("SELECT * FROM titles WHERE id = :titleId LIMIT 1")
    suspend fun titleById(titleId: String): TitleEntity?

    @Query("SELECT * FROM reading_progress WHERE titleId = :titleId LIMIT 1")
    suspend fun progressByTitle(titleId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reader_preferences WHERE id = 0 LIMIT 1")
    suspend fun readerPreferencesById(): ReaderPreferencesEntity?

    @Query("SELECT * FROM chapter_states WHERE chapterId = :chapterId LIMIT 1")
    suspend fun chapterStateById(chapterId: String): ChapterStateEntity?

    @Query("SELECT * FROM chapter_positions WHERE chapterId = :chapterId LIMIT 1")
    suspend fun chapterPositionById(chapterId: String): ChapterPositionEntity?

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun chapterById(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE titleId = :titleId ORDER BY sortOrder")
    suspend fun chaptersByTitle(titleId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM titles")
    suspend fun titleCount(): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE titleId = :titleId")
    suspend fun chapterCount(titleId: String): Int

    @Query("SELECT * FROM chapters WHERE titleId = :titleId AND sortOrder = :sortOrder LIMIT 1")
    suspend fun chapterByOrder(titleId: String, sortOrder: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE titleId = :titleId AND sortOrder <= :sortOrder ORDER BY sortOrder")
    suspend fun chaptersUpTo(titleId: String, sortOrder: Int): List<ChapterEntity>

    @Query(
        """
        SELECT
            c.titleId AS titleId,
            t.title AS titleName,
            c.id AS chapterId,
            c.name AS chapterName,
            c.sortOrder AS chapterSortOrder,
            c.localUri AS localUri,
            c.contentHash AS contentHash
        FROM chapters AS c
        INNER JOIN titles AS t ON t.id = c.titleId
        WHERE c.localUri IS NOT NULL
          AND (:titleId IS NULL OR c.titleId = :titleId)
        ORDER BY t.title COLLATE NOCASE, c.sortOrder
        """,
    )
    suspend fun searchableChapters(titleId: String?): List<SearchableChapterRow>

    @Query(
        """
        SELECT
            chapterId AS chapterId,
            titleName AS titleName,
            chapterName AS chapterName,
            chapterSortOrder AS chapterSortOrder,
            contentHash AS contentHash
        FROM chapter_search_fts
        GROUP BY chapterId, titleName, chapterName, chapterSortOrder, contentHash
        """,
    )
    suspend fun indexedChapterFingerprints(): List<IndexedChapterFingerprintRow>

    @Query(
        """
        SELECT
            titleId AS titleId,
            titleName AS titleName,
            chapterId AS chapterId,
            chapterName AS chapterName,
            chapterSortOrder AS chapterSortOrder,
            paragraphIndex AS paragraphIndex,
            snippet(chapter_search_fts, '‹', '›', '…', 6, 24) AS excerpt
        FROM chapter_search_fts
        WHERE chapter_search_fts MATCH :matchQuery
          AND (:titleId IS NULL OR titleId = :titleId)
        ORDER BY titleName COLLATE NOCASE, chapterSortOrder, paragraphIndex
        LIMIT :limit
        """,
    )
    suspend fun searchIndexedLibrary(
        matchQuery: String,
        titleId: String?,
        limit: Int,
    ): List<LibrarySearchResultRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTitles(titles: List<TitleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVolumes(volumes: List<VolumeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert
    suspend fun insertUpdateHistory(history: UpdateHistoryEntity): Long

    @Insert
    suspend fun insertReadingAnnotation(annotation: ReadingAnnotationEntity): Long

    @Insert
    suspend fun insertSearchDocuments(documents: List<ChapterSearchIndexEntity>)

    @Upsert
    suspend fun upsertTitle(title: TitleEntity)

    @Upsert
    suspend fun upsertVolumes(volumes: List<VolumeEntity>)

    @Upsert
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Upsert
    suspend fun upsertReadingProgress(progress: ReadingProgressEntity)

    @Upsert
    suspend fun upsertChapterStates(states: List<ChapterStateEntity>)

    @Upsert
    suspend fun upsertReaderPreferences(preferences: ReaderPreferencesEntity)

    @Upsert
    suspend fun upsertChapterPosition(position: ChapterPositionEntity)

    @Query(
        """
        UPDATE titles
        SET title = :title,
            author = :author,
            description = :description,
            updatedAt = :updatedAt
        WHERE id = :titleId
        """,
    )
    suspend fun updateTitleMetadata(
        titleId: String,
        title: String,
        author: String,
        description: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE titles SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :titleId")
    suspend fun updateFavorite(titleId: String, isFavorite: Boolean, updatedAt: Long): Int

    @Query(
        """
        UPDATE reading_annotations
        SET noteText = :noteText,
            updatedAt = :updatedAt
        WHERE id = :annotationId
        """,
    )
    suspend fun updateReadingAnnotationText(
        annotationId: Long,
        noteText: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE chapters SET sortOrder = sortOrder + :offset WHERE titleId = :titleId")
    suspend fun shiftChapterSortOrders(titleId: String, offset: Int)

    @Query("UPDATE volumes SET sortOrder = sortOrder + :offset WHERE titleId = :titleId")
    suspend fun shiftVolumeSortOrders(titleId: String, offset: Int)

    @Query("DELETE FROM chapters WHERE titleId = :titleId AND id NOT IN (:activeIds)")
    suspend fun deleteChaptersNotIn(titleId: String, activeIds: List<String>)

    @Query("DELETE FROM volumes WHERE titleId = :titleId AND id NOT IN (:activeIds)")
    suspend fun deleteVolumesNotIn(titleId: String, activeIds: List<String>)

    @Query("DELETE FROM titles WHERE id = :titleId")
    suspend fun deleteTitleById(titleId: String): Int

    @Query("DELETE FROM reading_annotations WHERE id = :annotationId")
    suspend fun deleteReadingAnnotation(annotationId: Long): Int

    @Query("DELETE FROM reading_annotations WHERE id IN (:annotationIds)")
    suspend fun deleteReadingAnnotations(annotationIds: List<Long>): Int

    @Query(
        """
        DELETE FROM reading_annotations
        WHERE chapterId = :chapterId
          AND paragraphIndex = :paragraphIndex
          AND noteText LIKE :bookmarkPrefix || '%'
        """,
    )
    suspend fun deleteBookmarkAtLocation(
        chapterId: String,
        paragraphIndex: Int,
        bookmarkPrefix: String,
    ): Int

    @Query(
        """
        DELETE FROM reading_annotations
        WHERE chapterId = :chapterId
          AND paragraphIndex = :paragraphIndex
          AND startOffset = :startOffset
          AND endOffset = :endOffset
          AND type = :type
        """,
    )
    suspend fun deleteReadingAnnotationAtRange(
        chapterId: String,
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        type: String,
    )

    @Query("DELETE FROM chapter_search_fts WHERE chapterId = :chapterId")
    suspend fun deleteSearchDocumentsForChapter(chapterId: String)

    @Query("DELETE FROM chapter_search_fts")
    suspend fun clearSearchIndex()

    @Query("DELETE FROM chapter_search_fts WHERE chapterId NOT IN (:activeChapterIds)")
    suspend fun deleteSearchDocumentsNotIn(activeChapterIds: List<String>)

    @Query(
        """
        DELETE FROM update_history
        WHERE titleId = :titleId
          AND id NOT IN (
              SELECT id FROM update_history
              WHERE titleId = :titleId
              ORDER BY createdAt DESC, id DESC
              LIMIT :keep
          )
        """,
    )
    suspend fun trimUpdateHistory(titleId: String, keep: Int)

    @Query("UPDATE titles SET lastOpenedAt = :openedAt, updatedAt = :openedAt WHERE id = :titleId")
    suspend fun touchTitle(titleId: String, openedAt: Long)
}
