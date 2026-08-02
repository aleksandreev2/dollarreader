package com.dollarreader.app.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class TitleSummaryRow(
    @Embedded val title: TitleEntity,
    val totalChapters: Int,
    val currentChapter: Int,
    val chapterProgress: Float,
)

data class VolumeWithChapters(
    @Embedded val volume: VolumeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "volumeId",
    )
    val chapters: List<ChapterEntity>,
)

data class TitleWithVolumes(
    @Embedded val title: TitleEntity,
    @Relation(
        entity = VolumeEntity::class,
        parentColumn = "id",
        entityColumn = "titleId",
    )
    val volumes: List<VolumeWithChapters>,
)
