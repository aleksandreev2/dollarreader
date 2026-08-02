package com.dollarreader.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "titles",
    indices = [
        Index(value = ["title"]),
        Index(value = ["lastOpenedAt"]),
    ],
)
data class TitleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val sourceType: String,
    val sourceUri: String?,
    val description: String?,
    val coverUri: String?,
    val accentSeed: Int,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)

@Entity(
    tableName = "volumes",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["titleId"]),
        Index(value = ["titleId", "sortOrder"], unique = true),
    ],
)
data class VolumeEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VolumeEntity::class,
            parentColumns = ["id"],
            childColumns = ["volumeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["titleId"]),
        Index(value = ["volumeId"]),
        Index(value = ["titleId", "sortOrder"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val titleId: String,
    val volumeId: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val localUri: String?,
    val contentHash: String?,
    val wordCount: Int?,
    val isDownloaded: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["currentChapterId"])],
)
data class ReadingProgressEntity(
    @PrimaryKey val titleId: String,
    val currentChapterId: String?,
    val chapterNumber: Int,
    val chapterProgress: Float,
    val scrollOffset: Int,
    val locator: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_states",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["titleId"])],
)
data class ChapterStateEntity(
    @PrimaryKey val chapterId: String,
    val titleId: String,
    val progress: Float,
    val isRead: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "update_history",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["titleId"]),
        Index(value = ["titleId", "createdAt"]),
    ],
)
data class UpdateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: String,
    val eventType: String,
    val details: String,
    val chapterCount: Int,
    val createdAt: Long,
)
