package com.dollarreader.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dollarreader.app.data.LibraryRepository
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalBookImporter(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun importBook(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "Новая книга"
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)

        when (extension) {
            "txt" -> importTxt(uri, displayName)
            "zip" -> importZip(uri, displayName)
            else -> throw ImportException("Пока поддерживается импорт TXT и ZIP")
        }
    }

    private suspend fun importTxt(uri: Uri, displayName: String): ImportResult {
        val sourceBytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readLimited(input, MAX_SINGLE_CHAPTER_BYTES)
        } ?: throw ImportException("Не удалось открыть выбранный TXT-файл")

        val text = decodeText(sourceBytes)
        if (text.isBlank()) throw ImportException("TXT-файл не содержит текста")

        val title = cleanBookTitle(displayName.substringBeforeLast('.'))
        val titleId = stableId("title", title)
        val chapterDescriptor = describeChapter(
            fileName = displayName,
            firstLine = firstMeaningfulLine(text),
            relativePath = displayName,
        )
        val volumeId = stableId(titleId, "Основное")
        val chapterId = stableId(titleId, normalizeKey(displayName))
        val finalFile = chapterFile(titleId, chapterId)
        writeUtf8(finalFile, text)

        val plan = LocalTitleImport(
            id = titleId,
            title = title,
            author = "Не указан",
            format = "TXT",
            sourceUri = uri.toString(),
            volumes = listOf(
                LocalVolumeImport(
                    id = volumeId,
                    name = "Основное",
                    number = null,
                    sortOrder = 1,
                    chapters = listOf(
                        LocalChapterImport(
                            id = chapterId,
                            name = chapterDescriptor.displayName,
                            number = chapterDescriptor.number,
                            sortOrder = 1,
                            localPath = finalFile.absolutePath,
                            contentHash = sha256(text.toByteArray(StandardCharsets.UTF_8)),
                            wordCount = wordCount(text),
                        ),
                    ),
                ),
            ),
        )
        val updated = repository.importLocalTitle(plan)
        ImportResult(
            titleId = titleId,
            title = title,
            chaptersImported = 1,
            filesSkipped = 0,
            format = "TXT",
            updatedExistingTitle = updated,
        )
    }

    private suspend fun importZip(uri: Uri, displayName: String): ImportResult {
        val tempRoot = File(context.cacheDir, "imports/${UUID.randomUUID()}").apply { mkdirs() }
        var seenEntries = 0
        var skippedEntries = 0
        var totalBytes = 0L
        val candidates = mutableListOf<ZipCandidate>()

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException("Не удалось открыть ZIP-архив")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    seenEntries += 1
                    if (seenEntries > MAX_ZIP_ENTRIES) {
                        throw ImportException("В архиве слишком много файлов")
                    }

                    val normalizedPath = normalizeArchivePath(entry.name)
                    if (
                        entry.isDirectory ||
                        normalizedPath.isBlank() ||
                        isIgnoredPath(normalizedPath) ||
                        !normalizedPath.endsWith(".txt", ignoreCase = true)
                    ) {
                        skippedEntries += 1
                        zip.closeEntry()
                        continue
                    }

                    val tempFile = File(tempRoot, "chapter_${candidates.size.toString().padStart(5, '0')}.bin")
                    val copied = copyEntryLimited(zip, tempFile, MAX_SINGLE_CHAPTER_BYTES)
                    totalBytes += copied
                    if (totalBytes > MAX_TOTAL_IMPORT_BYTES) {
                        throw ImportException("Архив слишком большой для безопасного импорта")
                    }

                    val bytes = tempFile.readBytes()
                    val text = decodeText(bytes)
                    if (text.isBlank()) {
                        skippedEntries += 1
                        tempFile.delete()
                    } else {
                        candidates += ZipCandidate(
                            relativePath = normalizedPath,
                            sourceFile = tempFile,
                            text = text,
                            descriptor = describeChapter(
                                fileName = normalizedPath.substringAfterLast('/'),
                                firstLine = firstMeaningfulLine(text),
                                relativePath = normalizedPath,
                            ),
                        )
                    }
                    zip.closeEntry()
                }
            }

            if (candidates.isEmpty()) {
                throw ImportException("В архиве не найдено подходящих TXT-глав")
            }

            val commonRoot = commonRootDirectory(candidates.map { it.relativePath })
            val archiveTitle = cleanBookTitle(displayName.substringBeforeLast('.'))
            val title = cleanBookTitle(commonRoot ?: archiveTitle)
            val titleId = stableId("title", title)

            val sorted = candidates.sortedWith(
                compareBy<ZipCandidate>({ it.descriptor.kindRank }, { it.descriptor.numericOrder })
                    .thenBy { naturalSortKey(it.relativePath) },
            )
            val grouped = sorted.groupBy { detectVolumeName(it.relativePath, commonRoot) }
            val orderedVolumes = grouped.keys.sortedWith(
                compareBy<String> { name -> volumeSortOrder(name) }
                    .thenBy { name -> naturalSortKey(name) },
            )

            var globalChapterOrder = 0
            val volumes = orderedVolumes.mapIndexed { volumeIndex, volumeName ->
                val volumeId = stableId(titleId, "volume:${normalizeKey(volumeName)}")
                val chapters = grouped.getValue(volumeName).map { candidate ->
                    globalChapterOrder += 1
                    val relativeKey = stripCommonRoot(candidate.relativePath, commonRoot)
                    val chapterId = stableId(titleId, "chapter:${normalizeKey(relativeKey)}")
                    val finalFile = chapterFile(titleId, chapterId)
                    writeUtf8(finalFile, candidate.text)
                    LocalChapterImport(
                        id = chapterId,
                        name = candidate.descriptor.displayName,
                        number = candidate.descriptor.number,
                        sortOrder = globalChapterOrder,
                        localPath = finalFile.absolutePath,
                        contentHash = sha256(candidate.text.toByteArray(StandardCharsets.UTF_8)),
                        wordCount = wordCount(candidate.text),
                    )
                }
                LocalVolumeImport(
                    id = volumeId,
                    name = volumeName,
                    number = volumeNumber(volumeName),
                    sortOrder = volumeIndex + 1,
                    chapters = chapters,
                )
            }

            val plan = LocalTitleImport(
                id = titleId,
                title = title,
                author = "Не указан",
                format = "ZIP/TXT",
                sourceUri = uri.toString(),
                volumes = volumes,
            )
            val updated = repository.importLocalTitle(plan)
            cleanupUnusedChapterFiles(titleId, volumes.flatMap { volume -> volume.chapters }.map { it.localPath }.toSet())

            return ImportResult(
                titleId = titleId,
                title = title,
                chaptersImported = volumes.sumOf { it.chapters.size },
                filesSkipped = skippedEntries,
                format = "ZIP/TXT",
                updatedExistingTitle = updated,
            )
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("Не удалось разобрать ZIP-архив: ${error.message ?: "неизвестная ошибка"}", error)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
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
            if (total > limit) throw ImportException("Одна глава превышает допустимый размер")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun copyEntryLimited(zip: ZipInputStream, destination: File, limit: Long): Long {
        var total = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw ImportException("Одна глава в архиве превышает допустимый размер")
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                StandardCharsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                StandardCharsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                StandardCharsets.UTF_16BE to 2
            else -> null to 0
        }

        if (charset != null) {
            return String(bytes, offset, bytes.size - offset, charset).normalizeNewLines()
        }

        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString().normalizeNewLines()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1251")).normalizeNewLines()
        }
    }

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)

    private fun String.normalizeNewLines(): String =
        replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

    private fun firstMeaningfulLine(text: String): String? =
        text.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }?.take(180)

    private fun wordCount(text: String): Int =
        WORD_PATTERN.findAll(text).count()

    private fun describeChapter(fileName: String, firstLine: String?, relativePath: String): ChapterDescriptor {
        val stem = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val lower = stem.lowercase(Locale.ROOT)
        val numberMatch = CHAPTER_NUMBER.find(stem)
        val first = firstLine?.trim()?.takeIf { it.length in 1..160 }

        return when {
            lower.contains("пролог") || lower.contains("prologue") -> ChapterDescriptor(
                displayName = mergeHeading("Пролог", first),
                number = null,
                kindRank = 0,
                numericOrder = 0,
            )
            numberMatch != null -> {
                val number = numberMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
                ChapterDescriptor(
                    displayName = mergeHeading("Глава $number", first),
                    number = number,
                    kindRank = 1,
                    numericOrder = number.toIntOrNull() ?: Int.MAX_VALUE / 4,
                )
            }
            lower.contains("эпилог") || lower.contains("epilogue") -> ChapterDescriptor(
                displayName = mergeHeading("Эпилог", first),
                number = null,
                kindRank = 3,
                numericOrder = Int.MAX_VALUE - 2,
            )
            lower.contains("послеслов") || lower.contains("afterword") -> ChapterDescriptor(
                displayName = mergeHeading("Послесловие", first),
                number = null,
                kindRank = 4,
                numericOrder = Int.MAX_VALUE - 1,
            )
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
        val normalizedPrefix = normalizeKey(prefix)
        val normalizedFirst = normalizeKey(firstLine)
        return if (normalizedFirst.startsWith(normalizedPrefix)) firstLine else "$prefix. $firstLine"
    }

    private fun cleanBookTitle(raw: String): String {
        val cleaned = raw
            .replace('_', ' ')
            .replace(Regex("(?i)\s*дополнительн(?:ый|ого)\s+аудит.*$"), "")
            .replace(Regex("(?i)\s*additional\s+audit.*$"), "")
            .replace(Regex("\s*\(\d+\)\s*$"), "")
            .replace(Regex("\s+"), " ")
            .trim(' ', '-', '—', '_')
        return cleaned.ifBlank { "Новая книга" }
    }

    private fun normalizeArchivePath(raw: String): String =
        raw.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")

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
        return path.split('/').any { segment -> normalizeKey(segment) in ignored }
    }

    private fun commonRootDirectory(paths: List<String>): String? {
        val roots = paths.mapNotNull { path -> path.substringBefore('/', missingDelimiterValue = "").ifBlank { null } }.distinct()
        return roots.singleOrNull()
    }

    private fun stripCommonRoot(path: String, commonRoot: String?): String =
        if (commonRoot != null && path.startsWith("$commonRoot/")) path.removePrefix("$commonRoot/") else path

    private fun detectVolumeName(path: String, commonRoot: String?): String {
        val relative = stripCommonRoot(path, commonRoot)
        val folders = relative.substringBeforeLast('/', missingDelimiterValue = "").split('/').filter(String::isNotBlank)
        val explicit = folders.firstOrNull { VOLUME_PATTERN.matches(it.trim()) }
        return explicit?.replace('_', ' ')?.trim() ?: "Основное"
    }

    private fun volumeNumber(name: String): String? = VOLUME_NUMBER.find(name)?.groupValues?.getOrNull(1)

    private fun volumeSortOrder(name: String): Int = volumeNumber(name)?.toIntOrNull() ?: if (name == "Основное") 0 else Int.MAX_VALUE

    private fun naturalSortKey(value: String): String =
        NATURAL_NUMBER.replace(value.lowercase(Locale.ROOT)) { match -> match.value.padStart(12, '0') }

    private fun normalizeKey(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("[^\p{L}\p{N}]+"), " ")
            .trim()

    private fun stableId(namespace: String, value: String): String =
        "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(20)}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private data class ZipCandidate(
        val relativePath: String,
        @Suppress("unused") val sourceFile: File,
        val text: String,
        val descriptor: ChapterDescriptor,
    )

    private data class ChapterDescriptor(
        val displayName: String,
        val number: String?,
        val kindRank: Int,
        val numericOrder: Int,
    )

    private companion object {
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_SINGLE_CHAPTER_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_IMPORT_BYTES = 192L * 1024L * 1024L
        val CHAPTER_NUMBER = Regex("(?i)(?:глава|chapter|chap|ch)[ _.-]*(\d+)")
        val VOLUME_PATTERN = Regex("(?i)(?:том|volume|vol)[ _.-]*\d+")
        val VOLUME_NUMBER = Regex("(\d+)")
        val NATURAL_NUMBER = Regex("\d+")
        val WORD_PATTERN = Regex("[\p{L}\p{N}]+")
    }
}
