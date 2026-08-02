package com.dollarreader.app.data

import android.content.Context
import android.net.Uri
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.model.SavedLibraryItem
import java.io.BufferedOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LibraryExportService(
    private val context: Context,
    private val database: DollarReaderDatabase,
    private val repository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
) {
    suspend fun exportNotes(uri: Uri): ExportSummary = withContext(Dispatchers.IO) {
        val saved = annotationRepository.savedItems.first()
        val markdown = buildNotesMarkdown(saved)
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Не удалось создать файл экспорта")
        output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(markdown) }
        ExportSummary(
            itemCount = saved.size,
            byteCount = markdown.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    suspend fun createBackup(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        checkpointDatabase()
        val books = repository.books.first()
        val saved = annotationRepository.savedItems.first()
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Не удалось создать архив резервной копии")
        var filesWritten = 0
        var bytesWritten = 0L

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val metadata = JSONObject().apply {
                put("format", BACKUP_FORMAT)
                put("createdAt", Instant.now().toString())
                put("titles", books.size)
                put("savedItems", saved.size)
                put("databaseName", DATABASE_NAME)
                put("databaseVersion", DATABASE_VERSION)
            }.toString(2).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("backup-info.json"))
            zip.write(metadata)
            zip.closeEntry()
            filesWritten += 1
            bytesWritten += metadata.size

            val notes = buildNotesMarkdown(saved).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("exports/notes.md"))
            zip.write(notes)
            zip.closeEntry()
            filesWritten += 1
            bytesWritten += notes.size

            val databaseFiles = listOf(
                context.getDatabasePath(DATABASE_NAME),
                File(context.getDatabasePath(DATABASE_NAME).absolutePath + "-wal"),
                File(context.getDatabasePath(DATABASE_NAME).absolutePath + "-shm"),
            )
            databaseFiles.filter(File::isFile).forEach { file ->
                val result = zipFile(zip, file, "database/${file.name}")
                filesWritten += result.first
                bytesWritten += result.second
            }

            val libraryRoot = File(context.filesDir, "library")
            if (libraryRoot.isDirectory) {
                libraryRoot.walkTopDown()
                    .filter(File::isFile)
                    .forEach { file ->
                        val relative = file.relativeTo(libraryRoot).invariantSeparatorsPath
                        val result = zipFile(zip, file, "library/$relative")
                        filesWritten += result.first
                        bytesWritten += result.second
                    }
            }
        }

        BackupSummary(
            titleCount = books.size,
            savedItemCount = saved.size,
            fileCount = filesWritten,
            byteCount = bytesWritten,
        )
    }

    private fun checkpointDatabase() {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { cursor -> while (cursor.moveToNext()) Unit }
    }

    private fun zipFile(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
    ): Pair<Int, Long> {
        zip.putNextEntry(ZipEntry(entryName))
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    zip.write(buffer, 0, read)
                    total += read
                }
            }
        }
        zip.closeEntry()
        return 1 to total
    }

    private fun buildNotesMarkdown(items: List<SavedLibraryItem>): String = buildString {
        appendLine("# DollarReader — сохранённое")
        appendLine()
        appendLine("Экспортировано: ${Instant.now()}")
        appendLine()
        if (items.isEmpty()) {
            appendLine("Сохранённых заметок, подсветок и закладок пока нет.")
            return@buildString
        }

        items.groupBy { it.titleId }.values.forEach { titleItems ->
            appendLine("## ${titleItems.first().titleName.escapeMarkdown()}")
            appendLine()
            titleItems.groupBy { it.chapterId }.values.forEach { chapterItems ->
                appendLine("### ${chapterItems.first().chapterName.escapeMarkdown()}")
                appendLine()
                chapterItems.sortedBy { it.paragraphIndex }.forEach { item ->
                    when {
                        isBookmarkNote(item.noteText) -> {
                            val label = decodeBookmarkLabel(item.noteText)
                            appendLine("- **Закладка, абзац ${item.paragraphIndex + 1}:** ${label ?: item.selectedText.escapeMarkdown()}")
                        }
                        item.type == ReadingAnnotationType.NOTE -> {
                            appendLine("- **Заметка, абзац ${item.paragraphIndex + 1}:** ${item.noteText.orEmpty().escapeMarkdown()}")
                            appendLine("  > ${item.selectedText.escapeMarkdown()}")
                        }
                        else -> {
                            appendLine("- **Подсветка, абзац ${item.paragraphIndex + 1}:** ${item.selectedText.escapeMarkdown()}")
                        }
                    }
                }
                appendLine()
            }
        }
    }

    private fun String.escapeMarkdown(): String =
        replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "\\`")
            .replace(Regex("""\s+"""), " ")
            .trim()

    data class ExportSummary(
        val itemCount: Int,
        val byteCount: Long,
    )

    data class BackupSummary(
        val titleCount: Int,
        val savedItemCount: Int,
        val fileCount: Int,
        val byteCount: Long,
    )

    private companion object {
        const val DATABASE_NAME = "dollarreader.db"
        const val DATABASE_VERSION = 6
        const val BACKUP_FORMAT = "DollarReader portable backup v1"
    }
}
