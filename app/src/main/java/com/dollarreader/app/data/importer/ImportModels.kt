package com.dollarreader.app.data.importer

enum class ImportChapterChange {
    ADDED,
    CHANGED,
    UNCHANGED,
}

data class ImportChangeSummary(
    val added: Int,
    val changed: Int,
    val removed: Int,
    val unchanged: Int,
) {
    val hasChanges: Boolean
        get() = added > 0 || changed > 0 || removed > 0
}

data class ImportPreview(
    val titleId: String,
    val title: String,
    val format: String,
    val totalChapters: Int,
    val filesSkipped: Int,
    val updatedExistingTitle: Boolean,
    val volumes: List<ImportPreviewVolume>,
    val changes: ImportChangeSummary = ImportChangeSummary(
        added = totalChapters,
        changed = 0,
        removed = 0,
        unchanged = 0,
    ),
)

data class ImportPreviewVolume(
    val name: String,
    val chapters: List<ImportPreviewChapter>,
)

data class ImportPreviewChapter(
    val name: String,
    val number: String?,
    val sourcePath: String,
    val change: ImportChapterChange = ImportChapterChange.ADDED,
)

data class ImportResult(
    val titleId: String,
    val title: String,
    val chaptersImported: Int,
    val filesSkipped: Int,
    val format: String,
    val updatedExistingTitle: Boolean,
    val changes: ImportChangeSummary = ImportChangeSummary(
        added = chaptersImported,
        changed = 0,
        removed = 0,
        unchanged = 0,
    ),
)

data class LocalTitleImport(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val sourceUri: String,
    val volumes: List<LocalVolumeImport>,
)

data class LocalVolumeImport(
    val id: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val chapters: List<LocalChapterImport>,
)

data class LocalChapterImport(
    val id: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val localPath: String,
    val contentHash: String,
    val wordCount: Int,
)

data class StoredImportChapter(
    val id: String,
    val volumeId: String,
    val name: String,
    val number: String?,
    val sortOrder: Int,
    val localPath: String?,
    val contentHash: String?,
)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
