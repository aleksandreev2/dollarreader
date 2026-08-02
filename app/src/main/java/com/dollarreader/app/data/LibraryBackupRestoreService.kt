package com.dollarreader.app.data

import android.content.Context
import android.net.Uri
import com.dollarreader.app.data.local.DollarReaderDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LibraryBackupRestoreService(
    private val context: Context,
) {
    suspend fun inspectBackup(uri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        scanBackup(uri, destination = null)
    }

    suspend fun stageRestore(uri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        val root = pendingRoot(context)
        root.deleteRecursively()
        root.mkdirs()
        try {
            val preview = scanBackup(uri, destination = root)
            File(root, READY_MARKER).writeText(
                JSONObject().apply {
                    put("format", BACKUP_FORMAT)
                    put("titles", preview.titleCount)
                    put("savedItems", preview.savedItemCount)
                }.toString(),
                StandardCharsets.UTF_8,
            )
            preview
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    private fun scanBackup(uri: Uri, destination: File?): RestorePreview {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть резервную копию")
        var entryCount = 0
        var acceptedFiles = 0
        var totalBytes = 0L
        var metadataBytes: ByteArray? = null
        var databaseFound = false
        var sqliteHeader: ByteArray? = null

        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= MAX_ENTRIES) { "В резервной копии слишком много файлов" }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val path = normalizePath(entry.name)
                    val accepted = path == METADATA_FILE ||
                        path.startsWith("database/") ||
                        path.startsWith("library/")
                    if (!accepted) {
                        drainEntry(zip, MAX_ENTRY_BYTES)
                        zip.closeEntry()
                        continue
                    }

                    val target = destination?.let { safeChild(it, path) }
                    target?.parentFile?.mkdirs()
                    val captureMetadata = path == METADATA_FILE
                    val captureHeader = path == "database/$DATABASE_NAME"
                    val capture = if (captureMetadata) ByteArrayOutput(MAX_METADATA_BYTES.toInt()) else null
                    val header = if (captureHeader) ByteArrayOutput(SQLITE_HEADER_SIZE) else null
                    val written = target?.let { FileOutputStream(it) }
                    val entryBytes = written.useNullable { output ->
                        copyEntry(
                            input = zip,
                            output = output,
                            metadataCapture = capture,
                            headerCapture = header,
                        )
                    }
                    totalBytes += entryBytes
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Резервная копия слишком большая" }
                    acceptedFiles += 1
                    if (captureMetadata) metadataBytes = capture?.toByteArray()
                    if (captureHeader) {
                        databaseFound = true
                        sqliteHeader = header?.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalArgumentException(
                "Не удалось проверить резервную копию: ${error.message ?: "повреждённый ZIP"}",
                error,
            )
        }

        require(databaseFound) { "В архиве отсутствует база DollarReader" }
        require(sqliteHeader?.contentEquals(SQLITE_HEADER) == true) {
            "Файл базы в архиве повреждён или имеет неизвестный формат"
        }
        val metadata = metadataBytes?.toString(StandardCharsets.UTF_8)
            ?.let(::JSONObject)
            ?: throw IllegalArgumentException("В архиве отсутствует backup-info.json")
        require(metadata.optString("format") == BACKUP_FORMAT) {
            "Это не поддерживаемая резервная копия DollarReader"
        }
        val databaseVersion = metadata.optInt("databaseVersion", 0)
        require(databaseVersion == 0 || databaseVersion <= CURRENT_DATABASE_VERSION) {
            "Копия создана более новой версией DollarReader"
        }

        return RestorePreview(
            titleCount = metadata.optInt("titles", 0).coerceAtLeast(0),
            savedItemCount = metadata.optInt("savedItems", 0).coerceAtLeast(0),
            fileCount = acceptedFiles,
            byteCount = totalBytes,
            createdAt = metadata.optString("createdAt").takeIf(String::isNotBlank),
            databaseVersion = databaseVersion.takeIf { it > 0 },
        )
    }

    private fun copyEntry(
        input: ZipInputStream,
        output: OutputStream?,
        metadataCapture: ByteArrayOutput?,
        headerCapture: ByteArrayOutput?,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= MAX_ENTRY_BYTES) { "Один файл резервной копии слишком большой" }
            output?.write(buffer, 0, read)
            metadataCapture?.writeLimited(buffer, read)
            headerCapture?.writeLimited(buffer, read)
        }
        output?.flush()
        return total
    }

    private fun drainEntry(input: ZipInputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Один файл резервной копии слишком большой" }
        }
    }

    private fun normalizePath(raw: String): String {
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> throw IllegalArgumentException("Архив содержит небезопасный путь")
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun safeChild(root: File, relativePath: String): File {
        val child = File(root, relativePath)
        val rootPath = root.canonicalPath + File.separator
        require(child.canonicalPath.startsWith(rootPath)) { "Архив содержит небезопасный путь" }
        return child
    }

    data class RestorePreview(
        val titleCount: Int,
        val savedItemCount: Int,
        val fileCount: Int,
        val byteCount: Long,
        val createdAt: String?,
        val databaseVersion: Int?,
    )

    companion object {
        fun applyPendingRestore(context: Context): Boolean {
            val root = pendingRoot(context)
            if (!File(root, READY_MARKER).isFile) return false
            val stagedDatabase = File(root, "database/$DATABASE_NAME")
            require(stagedDatabase.isFile) { "Подготовленная база восстановления отсутствует" }

            DollarReaderDatabase.closeInstance()
            val rollback = File(context.cacheDir, "restore-rollback").apply {
                deleteRecursively()
                mkdirs()
            }
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val currentDatabaseFiles = listOf(
                databaseFile,
                File(databaseFile.absolutePath + "-wal"),
                File(databaseFile.absolutePath + "-shm"),
            )
            val stagedDatabaseFiles = listOf(
                stagedDatabase,
                File(stagedDatabase.absolutePath + "-wal"),
                File(stagedDatabase.absolutePath + "-shm"),
            )
            val library = File(context.filesDir, "library")
            val stagedLibrary = File(root, "library")

            try {
                currentDatabaseFiles.filter(File::exists).forEach { file ->
                    movePath(file, File(rollback, "database/${file.name}"))
                }
                if (library.exists()) movePath(library, File(rollback, "library"))

                databaseFile.parentFile?.mkdirs()
                stagedDatabaseFiles.filter(File::exists).forEach { file ->
                    val suffix = file.name.removePrefix(DATABASE_NAME)
                    movePath(file, File(databaseFile.absolutePath + suffix))
                }
                if (stagedLibrary.exists()) {
                    movePath(stagedLibrary, library)
                } else {
                    library.mkdirs()
                }
                root.deleteRecursively()
                rollback.deleteRecursively()
                return true
            } catch (error: Throwable) {
                currentDatabaseFiles.forEach { it.delete() }
                library.deleteRecursively()
                File(rollback, "database").listFiles()?.forEach { file ->
                    val suffix = file.name.removePrefix(DATABASE_NAME)
                    movePath(file, File(databaseFile.absolutePath + suffix))
                }
                val rollbackLibrary = File(rollback, "library")
                if (rollbackLibrary.exists()) movePath(rollbackLibrary, library)
                root.deleteRecursively()
                rollback.deleteRecursively()
                throw IllegalStateException(
                    "Не удалось применить резервную копию: ${error.message ?: "ошибка файловой системы"}",
                    error,
                )
            }
        }

        private fun pendingRoot(context: Context): File =
            File(context.filesDir, "restore-pending")

        private fun movePath(source: File, target: File) {
            target.parentFile?.mkdirs()
            target.deleteRecursively()
            if (source.renameTo(target)) return
            if (source.isDirectory) {
                require(source.copyRecursively(target, overwrite = true)) {
                    "Не удалось скопировать ${source.name}"
                }
                source.deleteRecursively()
            } else {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }

        private const val DATABASE_NAME = "dollarreader.db"
        private const val BACKUP_FORMAT = "DollarReader portable backup v1"
        private const val METADATA_FILE = "backup-info.json"
        private const val READY_MARKER = "restore-ready.json"
        private const val CURRENT_DATABASE_VERSION = 6
        private const val MAX_ENTRIES = 50_000
        private const val MAX_METADATA_BYTES = 1024L * 1024L
        private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
        private const val SQLITE_HEADER_SIZE = 16
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    }
}

private class ByteArrayOutput(
    private val limit: Int,
) {
    private val output = java.io.ByteArrayOutputStream(limit.coerceAtMost(4096))

    fun writeLimited(buffer: ByteArray, count: Int) {
        val remaining = limit - output.size()
        if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

private inline fun <T : AutoCloseable?, R> T.useNullable(block: (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        if (this != null) {
            if (failure == null) close() else runCatching { close() }
        }
    }
}
