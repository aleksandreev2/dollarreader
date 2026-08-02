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

    @Query("SELECT * FROM chapter_states WHERE titleId = :titleId")
    fun observeChapterStates(titleId: String): Flow<List<ChapterStateEntity>>

    @Query("SELECT * FROM titles WHERE id = :titleId LIMIT 1")
    suspend fun titleById(titleId: String): TitleEntity?

    @Query("SELECT * FROM reading_progress WHERE titleId = :titleId LIMIT 1")
    suspend fun progressByTitle(titleId: String): ReadingProgressEntity?

    @Query("SELECT * FROM chapter_states WHERE chapterId = :chapterId LIMIT 1")
    suspend fun chapterStateById(chapterId: String): ChapterStateEntity?

    @Query("SELECT COUNT(*) FROM titles")
    suspend fun titleCount(): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE titleId = :titleId")
    suspend fun chapterCount(titleId: String): Int

    @Query("SELECT * FROM chapters WHERE titleId = :titleId AND sortOrder = :sortOrder LIMIT 1")
    suspend fun chapterByOrder(titleId: String, sortOrder: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE titleId = :titleId AND sortOrder <= :sortOrder ORDER BY sortOrder")
    suspend fun chaptersUpTo(titleId: String, sortOrder: Int): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTitles(titles: List<TitleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVolumes(volumes: List<VolumeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

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

    @Query("UPDATE chapters SET sortOrder = sortOrder + :offset WHERE titleId = :titleId")
    suspend fun shiftChapterSortOrders(titleId: String, offset: Int)

    @Query("UPDATE volumes SET sortOrder = sortOrder + :offset WHERE titleId = :titleId")
    suspend fun shiftVolumeSortOrders(titleId: String, offset: Int)

    @Query("DELETE FROM chapters WHERE titleId = :titleId AND id NOT IN (:activeIds)")
    suspend fun deleteChaptersNotIn(titleId: String, activeIds: List<String>)

    @Query("DELETE FROM volumes WHERE titleId = :titleId AND id NOT IN (:activeIds)")
    suspend fun deleteVolumesNotIn(titleId: String, activeIds: List<String>)

    @Query("UPDATE titles SET lastOpenedAt = :openedAt, updatedAt = :openedAt WHERE id = :titleId")
    suspend fun touchTitle(titleId: String, openedAt: Long)
}
