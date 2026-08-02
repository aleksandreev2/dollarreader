package com.dollarreader.app.data.importer

data class ImportPreview(
    val titleId: String,
    val title: String,
    val format: String,
    val totalChapters: Int,
    val filesSkipped: Int,
    val updatedExistingTitle: Boolean,
    val volumes: List<ImportPreviewVolume>,
)

data class ImportPreviewVolume(
    val name: String,
    val chapters: List<ImportPreviewChapter>,
)

data class ImportPreviewChapter(
    val name: String,
    val number: String?,
    val sourcePath: String,
)

data class ImportResult(
    val titleId: String,
    val title: String,
    val chaptersImported: Int,
    val filesSkipped: Int,
    val format: String,
    val updatedExistingTitle: Boolean,
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

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
