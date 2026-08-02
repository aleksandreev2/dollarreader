package com.dollarreader.app.data.importer

import java.io.File

internal data class PlannedImportChapter(
    val id: String,
    val volumeId: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val contentHash: String,
)

internal data class ImportDiffEntry(
    val change: ImportChapterChange,
    val requiresCopy: Boolean,
    val existingLocalPath: String?,
)

internal data class ImportDiff(
    val summary: ImportChangeSummary,
    val entries: Map<String, ImportDiffEntry>,
)

internal fun calculateImportDiff(
    existing: Map<String, StoredImportChapter>,
    planned: List<PlannedImportChapter>,
): ImportDiff {
    val plannedIds = planned.mapTo(linkedSetOf()) { it.id }
    val entries = planned.associate { chapter ->
        val stored = existing[chapter.id]
        val localFileMissing = stored?.localPath.isNullOrBlank() ||
            stored?.localPath?.let(::File)?.isFile != true
        val contentChanged = stored?.contentHash != chapter.contentHash
        val metadataChanged = stored != null && (
            stored.volumeId != chapter.volumeId ||
                stored.name != chapter.name ||
                stored.number != chapter.number ||
                stored.sortOrder != chapter.sortOrder
            )

        val change = when {
            stored == null -> ImportChapterChange.ADDED
            contentChanged || metadataChanged || localFileMissing -> ImportChapterChange.CHANGED
            else -> ImportChapterChange.UNCHANGED
        }
        chapter.id to ImportDiffEntry(
            change = change,
            requiresCopy = stored == null || contentChanged || localFileMissing,
            existingLocalPath = stored?.localPath,
        )
    }

    val summary = ImportChangeSummary(
        added = entries.values.count { it.change == ImportChapterChange.ADDED },
        changed = entries.values.count { it.change == ImportChapterChange.CHANGED },
        removed = existing.keys.count { it !in plannedIds },
        unchanged = entries.values.count { it.change == ImportChapterChange.UNCHANGED },
    )
    return ImportDiff(summary = summary, entries = entries)
}
