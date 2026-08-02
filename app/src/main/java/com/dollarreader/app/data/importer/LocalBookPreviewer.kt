package com.dollarreader.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dollarreader.app.data.LibraryRepository
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalBookPreviewer(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun previewBook(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "Новая книга"
        when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "txt" -> previewTxt(uri, displayName)
            "zip" -> previewZip(uri, displayName)
            else -> throw ImportException("Пока поддерживается импорт TXT и ZIP")
        }
    }

    private suspend fun previewTxt(uri: Uri, displayName: String): ImportPreview {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_SINGLE_CHAPTER_BYTES)
        } ?: throw ImportException("Не удалось открыть выбранный TXT-файл")
        val text = decodeText(bytes)
        if (text.isBlank()) throw ImportException("TXT-файл не содержит текста")

        val title = cleanBookTitle(displayName.substringBeforeLast('.'))
        val titleId = stableId("title", title)
        val descriptor = describeChapter(displayName, firstMeaningfulLine(text), displayName)
        return ImportPreview(
            titleId = titleId,
            title = title,
            format = "TXT",
            totalChapters = 1,
            filesSkipped = 0,
            updatedExistingTitle = repository.titleExists(titleId),
            volumes = listOf(
                ImportPreviewVolume(
                    name = "Основное",
                    chapters = listOf(
                        ImportPreviewChapter(
                            name = descriptor.displayName,
                            number = descriptor.number,
                            sourcePath = displayName,
                        ),
                    ),
                ),
            ),
        )
    }

    private suspend fun previewZip(uri: Uri, displayName: String): ImportPreview {
        var seenEntries = 0
        var skippedEntries = 0
        var totalBytes = 0L
        val candidates = mutableListOf<PreviewCandidate>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть ZIP-архив")

        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    seenEntries += 1
                    if (seenEntries > MAX_ZIP_ENTRIES) throw ImportException("В архиве слишком много файлов")
                    val path = normalizeArchivePath(entry.name)
                    if (
                        entry.isDirectory || path.isBlank() || isIgnoredPath(path) ||
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
                        candidates += PreviewCandidate(
                            relativePath = path,
                            descriptor = describeChapter(
                                path.substringAfterLast('/'),
                                firstMeaningfulLine(text),
                                path,
                            ),
                        )
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("Не удалось разобрать ZIP-архив: ${error.message ?: "неизвестная ошибка"}", error)
        }

        if (candidates.isEmpty()) throw ImportException("В архиве не найдено подходящих TXT-глав")
        val commonRoot = commonRootDirectory(candidates.map { it.relativePath })
        val archiveTitle = cleanBookTitle(displayName.substringBeforeLast('.'))
        val title = cleanBookTitle(commonRoot ?: archiveTitle)
        val titleId = stableId("title", title)
        val sorted = candidates.sortedWith(
            compareBy<PreviewCandidate>({ it.descriptor.kindRank }, { it.descriptor.numericOrder })
                .thenBy { naturalSortKey(it.relativePath) },
        )
        val grouped = sorted.groupBy { detectVolumeName(it.relativePath, commonRoot) }
        val volumeNames = grouped.keys.sortedWith(
            compareBy<String> { volumeSortOrder(it) }.thenBy { naturalSortKey(it) },
        )
        val volumes = volumeNames.map { volumeName ->
            ImportPreviewVolume(
                name = volumeName,
                chapters = grouped.getValue(volumeName).map { candidate ->
                    ImportPreviewChapter(
                        name = candidate.descriptor.displayName,
                        number = candidate.descriptor.number,
                        sourcePath = stripCommonRoot(candidate.relativePath, commonRoot),
                    )
                },
            )
        }
        return ImportPreview(
            titleId = titleId,
            title = title,
            format = "ZIP/TXT",
            totalChapters = volumes.sumOf { it.chapters.size },
            filesSkipped = skippedEntries,
            updatedExistingTitle = repository.titleExists(titleId),
            volumes = volumes,
        )
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

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2
            else -> null to 0
        }
        if (charset != null) return String(bytes, offset, bytes.size - offset, charset).normalizeNewLines()
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().normalizeNewLines()
        } catch (_: CharacterCodingException) {
            String(bytes, java.nio.charset.Charset.forName("windows-1251")).normalizeNewLines()
        }
    }

    private fun String.normalizeNewLines(): String = replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
    private fun firstMeaningfulLine(text: String): String? = text.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }?.take(180)

    private fun describeChapter(fileName: String, firstLine: String?, relativePath: String): ChapterDescriptor {
        val stem = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val lower = stem.lowercase(Locale.ROOT)
        val numberMatch = CHAPTER_NUMBER.find(stem)
        val first = firstLine?.trim()?.takeIf { it.length in 1..160 }
        return when {
            lower.contains("пролог") || lower.contains("prologue") -> ChapterDescriptor(mergeHeading("Пролог", first), null, 0, 0)
            numberMatch != null -> {
                val number = numberMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
                ChapterDescriptor(mergeHeading("Глава $number", first), number, 1, number.toIntOrNull() ?: Int.MAX_VALUE / 4)
            }
            lower.contains("эпилог") || lower.contains("epilogue") -> ChapterDescriptor(mergeHeading("Эпилог", first), null, 3, Int.MAX_VALUE - 2)
            lower.contains("послеслов") || lower.contains("afterword") -> ChapterDescriptor(mergeHeading("Послесловие", first), null, 4, Int.MAX_VALUE - 1)
            else -> ChapterDescriptor(first ?: stem.ifBlank { relativePath.substringAfterLast('/') }, null, 2, Int.MAX_VALUE / 2)
        }
    }

    private fun mergeHeading(prefix: String, firstLine: String?): String {
        if (firstLine.isNullOrBlank()) return prefix
        return if (normalizeKey(firstLine).startsWith(normalizeKey(prefix))) firstLine else "$prefix. $firstLine"
    }

    private fun cleanBookTitle(raw: String): String = raw
        .replace('_', ' ')
        .replace(Regex("""(?i)\s*дополнительн(?:ый|ого)\s+аудит.*$"""), "")
        .replace(Regex("""(?i)\s*additional\s+audit.*$"""), "")
        .replace(Regex("""\s*\(\d+\)\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '—', '_')
        .ifBlank { "Новая книга" }

    private fun normalizeArchivePath(raw: String): String = raw.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
    private fun isIgnoredPath(path: String): Boolean {
        val ignored = setOf("служебные файлы", "служебное", "service files", "service", "равка", "равки", "raw", "raws", "macosx", "git", "архив устаревших", "metadata")
        return path.split('/').any { normalizeKey(it) in ignored }
    }
    private fun commonRootDirectory(paths: List<String>): String? = paths.mapNotNull { it.substringBefore('/', "").ifBlank { null } }.distinct().singleOrNull()
    private fun stripCommonRoot(path: String, root: String?): String = if (root != null && path.startsWith("$root/")) path.removePrefix("$root/") else path
    private fun detectVolumeName(path: String, root: String?): String {
        val folders = stripCommonRoot(path, root).substringBeforeLast('/', "").split('/').filter(String::isNotBlank)
        return folders.firstOrNull { VOLUME_PATTERN.matches(it.trim()) }?.replace('_', ' ')?.trim() ?: "Основное"
    }
    private fun volumeSortOrder(name: String): Int = VOLUME_NUMBER.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (name == "Основное") 0 else Int.MAX_VALUE
    private fun naturalSortKey(value: String): String = NATURAL_NUMBER.replace(value.lowercase(Locale.ROOT)) { it.value.padStart(12, '0') }
    private fun normalizeKey(value: String): String = value.lowercase(Locale.ROOT).replace('_', ' ').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    private fun stableId(namespace: String, value: String): String = "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(20)}"
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class PreviewCandidate(val relativePath: String, val descriptor: ChapterDescriptor)
    private data class ChapterDescriptor(val displayName: String, val number: String?, val kindRank: Int, val numericOrder: Int)

    private companion object {
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_SINGLE_CHAPTER_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_IMPORT_BYTES = 192L * 1024L * 1024L
        val CHAPTER_NUMBER = Regex("""(?i)(?:глава|chapter|chap|ch)[ _.-]*(\d+)""")
        val VOLUME_PATTERN = Regex("""(?i)(?:том|volume|vol)[ _.-]*\d+""")
        val VOLUME_NUMBER = Regex("""(\d+)""")
        val NATURAL_NUMBER = Regex("""\d+""")
    }
}
