package com.dollarreader.app.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.dollarreader.app.data.LibraryRepository
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class LocalBookService(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    private val legacyPreviewer = LocalBookPreviewer(context, repository)
    private val legacyImporter = LocalBookImporter(context, repository)

    suspend fun previewBook(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val displayName = queryDisplayName(uri) ?: "Новая книга"
        when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "zip" -> previewZip(uri, displayName)
            "txt" -> legacyPreviewer.previewBook(uri)
            else -> throw ImportException("Пока поддерживается импорт TXT и ZIP")
        }
    }

    suspend fun importBook(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val displayName = queryDisplayName(uri) ?: "Новая книга"
        when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "zip" -> importZip(uri, displayName)
            "txt" -> legacyImporter.importBook(uri)
            else -> throw ImportException("Пока поддерживается импорт TXT и ZIP")
        }
    }

    private suspend fun previewZip(uri: Uri, displayName: String): ImportPreview {
        val scan = scanZip(uri, displayName)
        val existing = loadExistingChapters(scan.titleId)
        val diff = calculateImportDiff(
            existing = existing,
            planned = scan.volumes.flatMap { volume ->
                volume.chapters.map(PlannedZipChapter::toDiffChapter)
            },
        )

        return ImportPreview(
            titleId = scan.titleId,
            title = scan.title,
            format = ZIP_FORMAT,
            totalChapters = scan.volumes.sumOf { it.chapters.size },
            filesSkipped = scan.filesSkipped,
            updatedExistingTitle = existing.isNotEmpty(),
            volumes = scan.volumes.map { volume ->
                ImportPreviewVolume(
                    name = volume.name,
                    chapters = volume.chapters.map { chapter ->
                        ImportPreviewChapter(
                            name = chapter.candidate.descriptor.displayName,
                            number = chapter.candidate.descriptor.number,
                            sourcePath = chapter.candidate.relativePath,
                            change = diff.entries.getValue(chapter.id).change,
                        )
                    },
                )
            },
            changes = diff.summary,
        )
    }

    private suspend fun importZip(uri: Uri, displayName: String): ImportResult {
        val scan = scanZip(uri, displayName)
        val existing = loadExistingChapters(scan.titleId)
        val diff = calculateImportDiff(
            existing = existing,
            planned = scan.volumes.flatMap { volume ->
                volume.chapters.map(PlannedZipChapter::toDiffChapter)
            },
        )

        if (!diff.summary.hasChanges && existing.isNotEmpty()) {
            return ImportResult(
                titleId = scan.titleId,
                title = scan.title,
                chaptersImported = scan.volumes.sumOf { it.chapters.size },
                filesSkipped = scan.filesSkipped,
                format = ZIP_FORMAT,
                updatedExistingTitle = true,
                changes = diff.summary,
            )
        }

        val volumes = scan.volumes.map { volume ->
            LocalVolumeImport(
                id = volume.id,
                name = volume.name,
                number = volume.number,
                sortOrder = volume.sortOrder,
                chapters = volume.chapters.map { chapter ->
                    val diffEntry = diff.entries.getValue(chapter.id)
                    val localPath = if (diffEntry.requiresCopy) {
                        val target = chapterFile(scan.titleId, chapter.id)
                        writeUtf8(target, chapter.candidate.text)
                        target.absolutePath
                    } else {
                        diffEntry.existingLocalPath
                            ?: throw ImportException("Не найден локальный файл неизменённой главы")
                    }
                    LocalChapterImport(
                        id = chapter.id,
                        name = chapter.candidate.descriptor.displayName,
                        number = chapter.candidate.descriptor.number,
                        sortOrder = chapter.sortOrder,
                        localPath = localPath,
                        contentHash = chapter.candidate.contentHash,
                        wordCount = chapter.candidate.wordCount,
                    )
                },
            )
        }

        val updated = repository.importLocalTitle(
            LocalTitleImport(
                id = scan.titleId,
                title = scan.title,
                author = "Не указан",
                format = ZIP_FORMAT,
                sourceUri = uri.toString(),
                volumes = volumes,
            ),
        )
        cleanupUnusedChapterFiles(
            titleId = scan.titleId,
            activePaths = volumes.flatMap { volume -> volume.chapters }
                .map { chapter -> chapter.localPath }
                .toSet(),
        )

        return ImportResult(
            titleId = scan.titleId,
            title = scan.title,
            chaptersImported = volumes.sumOf { it.chapters.size },
            filesSkipped = scan.filesSkipped,
            format = ZIP_FORMAT,
            updatedExistingTitle = updated,
            changes = diff.summary,
        )
    }

    private fun scanZip(uri: Uri, displayName: String): ZipScan {
        var seenEntries = 0
        var skippedEntries = 0
        var totalBytes = 0L
        val candidates = mutableListOf<ZipCandidate>()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть ZIP-архив")
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    seenEntries += 1
                    if (seenEntries > MAX_ZIP_ENTRIES) {
                        throw ImportException("В архиве слишком много файлов")
                    }

                    val path = normalizeArchivePath(entry.name)
                    if (
                        entry.isDirectory ||
                        path.isBlank() ||
                        isIgnoredPath(path) ||
                        !path.endsWith(".txt", ignoreCase = true)
                    ) {
                        skippedEntries += 1
                        zip.closeEntry()
                        continue
                    }

                    val bytes = readLimited(zip, MAX_SINGLE_CHAPTER_BYTES)
                    totalBytes += bytes.size
                    if (totalBytes > MAX_TOTAL_IMPORT_BYTES) {
                        throw ImportException("Архив слишком большой для безопасного импорта")
                    }

                    val text = decodeText(bytes)
                    if (text.isBlank()) {
                        skippedEntries += 1
                    } else {
                        candidates += ZipCandidate(
                            relativePath = path,
                            text = text,
                            descriptor = describeChapter(
                                fileName = path.substringAfterLast('/'),
                                firstLine = firstMeaningfulLine(text),
                                relativePath = path,
                            ),
                            contentHash = sha256(text.toByteArray(StandardCharsets.UTF_8)),
                            wordCount = WORD_PATTERN.findAll(text).count(),
                        )
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException(
                "Не удалось разобрать ZIP-архив: ${error.message ?: "неизвестная ошибка"}",
                error,
            )
        }

        if (candidates.isEmpty()) {
            throw ImportException("В архиве не найдено подходящих TXT-глав")
        }

        val commonRoot = commonRootDirectory(candidates.map { it.relativePath })
        val archiveTitle = cleanBookTitle(displayName.substringBeforeLast('.'))
        val title = cleanBookTitle(commonRoot ?: archiveTitle)
        val titleId = stableId("title", title)
        val volumes = arrange(titleId, candidates, commonRoot)

        return ZipScan(
            title = title,
            titleId = titleId,
            filesSkipped = skippedEntries,
            volumes = volumes,
        )
    }

    private fun arrange(
        titleId: String,
        candidates: List<ZipCandidate>,
        commonRoot: String?,
    ): List<PlannedZipVolume> {
        val sorted = candidates.sortedWith(
            compareBy<ZipCandidate>({ it.descriptor.kindRank }, { it.descriptor.numericOrder })
                .thenBy { naturalSortKey(it.relativePath) },
        )
        val grouped = sorted.groupBy { detectVolumeName(it.relativePath, commonRoot) }
        val volumeNames = grouped.keys.sortedWith(
            compareBy<String> { volumeSortOrder(it) }
                .thenBy { naturalSortKey(it) },
        )

        var globalOrder = 0
        return volumeNames.mapIndexed { volumeIndex, volumeName ->
            val volumeId = stableId(titleId, "volume:${normalizeKey(volumeName)}")
            PlannedZipVolume(
                id = volumeId,
                name = volumeName,
                number = volumeNumber(volumeName),
                sortOrder = volumeIndex + 1,
                chapters = grouped.getValue(volumeName).map { candidate ->
                    globalOrder += 1
                    val relativeKey = stripCommonRoot(candidate.relativePath, commonRoot)
                    PlannedZipChapter(
                        id = stableId(titleId, "chapter:${normalizeKey(relativeKey)}"),
                        volumeId = volumeId,
                        sortOrder = globalOrder,
                        candidate = candidate,
                    )
                },
            )
        }
    }

    private suspend fun loadExistingChapters(titleId: String): Map<String, StoredImportChapter> {
        val storedTitle = repository.observeTitle(titleId).first() ?: return emptyMap()
        return storedTitle.volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                StoredImportChapter(
                    id = chapter.id,
                    volumeId = chapter.volumeId,
                    name = chapter.name,
                    number = chapter.number,
                    sortOrder = chapter.sortOrder,
                    localPath = chapter.localUri,
                    contentHash = chapter.contentHash,
                )
            }
        }.associateBy { it.id }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun chapterFile(titleId: String, chapterId: String): File {
        val directory = File(context.filesDir, "library/$titleId/chapters").apply { mkdirs() }
        return File(directory, "$chapterId.txt")
    }

    private fun cleanupUnusedChapterFiles(titleId: String, activePaths: Set<String>) {
        val directory = File(context.filesDir, "library/$titleId/chapters")
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in activePaths) file.delete()
        }
    }

    private fun writeUtf8(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text, StandardCharsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw ImportException("Одна глава в архиве превышает допустимый размер")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2
            else -> null to 0
        }

        if (charset != null) {
            return String(bytes, offset, bytes.size - offset, charset).normalizeNewLines()
        }

        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .normalizeNewLines()
        } catch (_: CharacterCodingException) {
            String(bytes, java.nio.charset.Charset.forName("windows-1251")).normalizeNewLines()
        }
    }

    private fun String.normalizeNewLines(): String =
        replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

    private fun firstMeaningfulLine(text: String): String? =
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            ?.take(180)

    private fun describeChapter(
        fileName: String,
        firstLine: String?,
        relativePath: String,
    ): ChapterDescriptor {
        val stem = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val lower = stem.lowercase(Locale.ROOT)
        val numberMatch = CHAPTER_NUMBER.find(stem)
        val first = firstLine?.trim()?.takeIf { it.length in 1..160 }

        return when {
            lower.contains("пролог") || lower.contains("prologue") ->
                ChapterDescriptor(mergeHeading("Пролог", first), null, 0, 0)
            numberMatch != null -> {
                val number = numberMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
                ChapterDescriptor(
                    displayName = mergeHeading("Глава $number", first),
                    number = number,
                    kindRank = 1,
                    numericOrder = number.toIntOrNull() ?: Int.MAX_VALUE / 4,
                )
            }
            lower.contains("эпилог") || lower.contains("epilogue") ->
                ChapterDescriptor(mergeHeading("Эпилог", first), null, 3, Int.MAX_VALUE - 2)
            lower.contains("послеслов") || lower.contains("afterword") ->
                ChapterDescriptor(mergeHeading("Послесловие", first), null, 4, Int.MAX_VALUE - 1)
            else -> ChapterDescriptor(
                displayName = first ?: stem.ifBlank { relativePath.substringAfterLast('/') },
                number = null,
                kindRank = 2,
                numericOrder = Int.MAX_VALUE / 2,
            )
        }
    }

    private fun mergeHeading(prefix: String, firstLine: String?): String {
        if (firstLine.isNullOrBlank()) return prefix
        return if (normalizeKey(firstLine).startsWith(normalizeKey(prefix))) {
            firstLine
        } else {
            "$prefix. $firstLine"
        }
    }

    private fun cleanBookTitle(raw: String): String = raw
        .replace('_', ' ')
        .replace(Regex("""(?i)\s*дополнительн(?:ый|ого)\s+аудит.*$"""), "")
        .replace(Regex("""(?i)\s*additional\s+audit.*$"""), "")
        .replace(Regex("""\s*\(\d+\)\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '—', '_')
        .ifBlank { "Новая книга" }

    private fun normalizeArchivePath(raw: String): String =
        raw.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")

    private fun isIgnoredPath(path: String): Boolean {
        val ignored = setOf(
            "служебные файлы",
            "служебное",
            "service files",
            "service",
            "равка",
            "равки",
            "raw",
            "raws",
            "macosx",
            "git",
            "архив устаревших",
            "metadata",
        )
        return path.split('/').any { normalizeKey(it) in ignored }
    }

    private fun commonRootDirectory(paths: List<String>): String? =
        paths.mapNotNull { path ->
            path.substringBefore('/', "").ifBlank { null }
        }.distinct().singleOrNull()

    private fun stripCommonRoot(path: String, root: String?): String =
        if (root != null && path.startsWith("$root/")) path.removePrefix("$root/") else path

    private fun detectVolumeName(path: String, root: String?): String {
        val folders = stripCommonRoot(path, root)
            .substringBeforeLast('/', "")
            .split('/')
            .filter(String::isNotBlank)
        return folders.firstOrNull { VOLUME_PATTERN.matches(it.trim()) }
            ?.replace('_', ' ')
            ?.trim()
            ?: "Основное"
    }

    private fun volumeNumber(name: String): String? =
        VOLUME_NUMBER.find(name)?.groupValues?.getOrNull(1)

    private fun volumeSortOrder(name: String): Int =
        volumeNumber(name)?.toIntOrNull() ?: if (name == "Основное") 0 else Int.MAX_VALUE

    private fun naturalSortKey(value: String): String =
        NATURAL_NUMBER.replace(value.lowercase(Locale.ROOT)) {
            it.value.padStart(12, '0')
        }

    private fun normalizeKey(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun stableId(namespace: String, value: String): String =
        "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(20)}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ZipScan(
        val title: String,
        val titleId: String,
        val filesSkipped: Int,
        val volumes: List<PlannedZipVolume>,
    )

    private data class ZipCandidate(
        val relativePath: String,
        val text: String,
        val descriptor: ChapterDescriptor,
        val contentHash: String,
        val wordCount: Int,
    )

    private data class PlannedZipVolume(
        val id: String,
        val name: String,
        val number: String?,
        val sortOrder: Int,
        val chapters: List<PlannedZipChapter>,
    )

    private data class PlannedZipChapter(
        val id: String,
        val volumeId: String,
        val sortOrder: Int,
        val candidate: ZipCandidate,
    ) {
        fun toDiffChapter(): PlannedImportChapter = PlannedImportChapter(
            id = id,
            volumeId = volumeId,
            name = candidate.descriptor.displayName,
            number = candidate.descriptor.number,
            sortOrder = sortOrder,
            contentHash = candidate.contentHash,
        )
    }

    private data class ChapterDescriptor(
        val displayName: String,
        val number: String?,
        val kindRank: Int,
        val numericOrder: Int,
    )

    private companion object {
        const val ZIP_FORMAT = "ZIP/TXT"
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_SINGLE_CHAPTER_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_IMPORT_BYTES = 192L * 1024L * 1024L
        val CHAPTER_NUMBER = Regex("""(?i)(?:глава|chapter|chap|ch)[ _.-]*(\d+)""")
        val VOLUME_PATTERN = Regex("""(?i)(?:том|volume|vol)[ _.-]*\d+""")
        val VOLUME_NUMBER = Regex("""(\d+)""")
        val NATURAL_NUMBER = Regex("""\d+""")
        val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
    }
}
