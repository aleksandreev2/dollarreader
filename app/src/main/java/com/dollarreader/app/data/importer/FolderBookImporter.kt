package com.dollarreader.app.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dollarreader.app.data.LibraryRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class FolderBookImporter(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    @Volatile
    private var cachedScan: CachedFolderScan? = null

    suspend fun previewFolder(treeUri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val scan = scanFolder(treeUri)
        cachedScan = CachedFolderScan(treeUri.toString(), System.currentTimeMillis(), scan)
        val title = cleanBookTitle(scan.rootName)
        val titleId = stableId("title", title)
        val plannedVolumes = buildPlannedVolumes(titleId, scan.candidates)
        val existing = loadExistingChapters(titleId)
        val diff = calculateImportDiff(
            existing,
            plannedVolumes.flatMap { volume -> volume.chapters.map { it.toPlannedImportChapter() } },
        )

        ImportPreview(
            titleId = titleId,
            title = title,
            format = "ПАПКА/TXT",
            totalChapters = plannedVolumes.sumOf { it.chapters.size },
            filesSkipped = scan.filesSkipped,
            updatedExistingTitle = existing.isNotEmpty(),
            volumes = plannedVolumes.map { volume ->
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

    suspend fun importFolder(treeUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        persistReadPermission(treeUri)
        val scan = takeCachedScan(treeUri) ?: scanFolder(treeUri)
        val title = cleanBookTitle(scan.rootName)
        val titleId = stableId("title", title)
        val plannedVolumes = buildPlannedVolumes(titleId, scan.candidates)
        val existing = loadExistingChapters(titleId)
        val diff = calculateImportDiff(
            existing,
            plannedVolumes.flatMap { volume -> volume.chapters.map { it.toPlannedImportChapter() } },
        )

        if (!diff.summary.hasChanges && existing.isNotEmpty()) {
            cachedScan = null
            return@withContext ImportResult(
                titleId = titleId,
                title = title,
                chaptersImported = plannedVolumes.sumOf { it.chapters.size },
                filesSkipped = scan.filesSkipped,
                format = "ПАПКА/TXT",
                updatedExistingTitle = true,
                changes = diff.summary,
            )
        }

        val volumes = plannedVolumes.map { volume ->
            LocalVolumeImport(
                id = volume.id,
                name = volume.name,
                number = volume.number,
                sortOrder = volume.sortOrder,
                chapters = volume.chapters.map { chapter ->
                    val diffEntry = diff.entries.getValue(chapter.id)
                    val localPath = if (diffEntry.requiresCopy) {
                        val text = readDocumentText(chapter.candidate.documentUri)
                        val actualHash = sha256(text.toByteArray(StandardCharsets.UTF_8))
                        if (actualHash != chapter.candidate.contentHash) {
                            throw ImportException(
                                "Файлы в папке изменились после предварительного просмотра. Проверьте импорт ещё раз.",
                            )
                        }
                        val finalFile = chapterFile(titleId, chapter.id)
                        writeUtf8(finalFile, text)
                        finalFile.absolutePath
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
                id = titleId,
                title = title,
                author = "Не указан",
                format = "ПАПКА/TXT",
                sourceUri = treeUri.toString(),
                volumes = volumes,
            ),
        )
        cleanupUnusedChapterFiles(
            titleId,
            volumes.flatMap { volume -> volume.chapters }.map { it.localPath }.toSet(),
        )
        cachedScan = null

        ImportResult(
            titleId = titleId,
            title = title,
            chaptersImported = volumes.sumOf { it.chapters.size },
            filesSkipped = scan.filesSkipped,
            format = "ПАПКА/TXT",
            updatedExistingTitle = updated,
            changes = diff.summary,
        )
    }

    private suspend fun loadExistingChapters(titleId: String): Map<String, StoredImportChapter> {
        val title = repository.observeTitle(titleId).first() ?: return emptyMap()
        return title.volumes.flatMap { volume ->
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

    private fun takeCachedScan(treeUri: Uri): FolderScan? {
        val cached = cachedScan ?: return null
        val age = System.currentTimeMillis() - cached.createdAt
        return if (cached.treeUri == treeUri.toString() && age in 0..SCAN_CACHE_TTL_MS) {
            cached.scan
        } else {
            cachedScan = null
            null
        }
    }

    private fun scanFolder(treeUri: Uri): FolderScan {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw ImportException("Не удалось открыть выбранную папку")
        if (!root.isDirectory) throw ImportException("Выбранный объект не является папкой")

        val rootName = root.name?.trim().orEmpty().ifBlank { "Новая книга" }
        val stack = ArrayDeque<PendingDocument>()
        root.listFiles()
            .sortedBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
            .asReversed()
            .forEach { child -> stack.addLast(PendingDocument(child, safeName(child.name), 1)) }

        var seenEntries = 0
        var filesSkipped = 0
        var totalBytes = 0L
        val candidates = mutableListOf<FolderCandidate>()

        while (stack.isNotEmpty()) {
            val pending = stack.removeLast()
            seenEntries += 1
            if (seenEntries > MAX_TREE_ENTRIES) {
                throw ImportException("В папке слишком много файлов и каталогов")
            }

            val document = pending.document
            val relativePath = pending.relativePath
            if (relativePath.isBlank() || isIgnoredPath(relativePath)) {
                filesSkipped += 1
                continue
            }

            if (document.isDirectory) {
                if (pending.depth >= MAX_FOLDER_DEPTH) {
                    filesSkipped += 1
                    continue
                }
                val children = try {
                    document.listFiles()
                } catch (_: Exception) {
                    filesSkipped += 1
                    continue
                }
                children
                    .sortedBy { it.name?.lowercase(Locale.ROOT).orEmpty() }
                    .asReversed()
                    .forEach { child ->
                        stack.addLast(
                            PendingDocument(
                                child,
                                "$relativePath/${safeName(child.name)}",
                                pending.depth + 1,
                            ),
                        )
                    }
                continue
            }

            val name = document.name.orEmpty()
            val isText = name.endsWith(".txt", ignoreCase = true) ||
                document.type.equals("text/plain", ignoreCase = true)
            if (!document.isFile || !isText) {
                filesSkipped += 1
                continue
            }

            val text = try {
                readDocumentText(document.uri)
            } catch (error: ImportException) {
                throw error
            } catch (_: Exception) {
                filesSkipped += 1
                continue
            }
            val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8)
            totalBytes += utf8Bytes.size
            if (totalBytes > MAX_TOTAL_IMPORT_BYTES) {
                throw ImportException("Папка слишком большая для безопасного импорта")
            }
            if (text.isBlank()) {
                filesSkipped += 1
                continue
            }

            candidates += FolderCandidate(
                documentUri = document.uri,
                relativePath = relativePath,
                descriptor = describeChapter(
                    name.ifBlank { relativePath.substringAfterLast('/') },
                    firstMeaningfulLine(text),
                    relativePath,
                ),
                contentHash = sha256(utf8Bytes),
                wordCount = WORD_PATTERN.findAll(text).count(),
            )
        }

        if (candidates.isEmpty()) {
            throw ImportException("В выбранной папке не найдено подходящих TXT-глав")
        }
        return FolderScan(rootName, candidates, filesSkipped)
    }

    private fun buildPlannedVolumes(
        titleId: String,
        candidates: List<FolderCandidate>,
    ): List<PlannedFolderVolume> {
        val sorted = candidates.sortedWith(
            compareBy<FolderCandidate>({ it.descriptor.kindRank }, { it.descriptor.numericOrder })
                .thenBy { naturalSortKey(it.relativePath) },
        )
        val grouped = sorted.groupBy { detectVolumeName(it.relativePath) }
        val volumeNames = grouped.keys.sortedWith(
            compareBy<String> { volumeSortOrder(it) }.thenBy { naturalSortKey(it) },
        )
        var globalOrder = 0
        return volumeNames.mapIndexed { volumeIndex, name ->
            val volumeId = stableId(titleId, "volume:${normalizeKey(name)}")
            PlannedFolderVolume(
                id = volumeId,
                name = name,
                number = volumeNumber(name),
                sortOrder = volumeIndex + 1,
                chapters = grouped.getValue(name).map { candidate ->
                    globalOrder += 1
                    PlannedFolderChapter(
                        id = stableId(titleId, "chapter:${normalizeKey(candidate.relativePath)}"),
                        volumeId = volumeId,
                        sortOrder = globalOrder,
                        candidate = candidate,
                    )
                },
            )
        }
    }

    private fun readDocumentText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readLimited(input, MAX_SINGLE_CHAPTER_BYTES)
        } ?: throw ImportException("Не удалось прочитать одну из глав")
        return decodeText(bytes)
    }

    private fun persistReadPermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
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
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().normalizeNewLines()
        } catch (_: CharacterCodingException) {
            String(bytes, java.nio.charset.Charset.forName("windows-1251")).normalizeNewLines()
        }
    }

    private fun String.normalizeNewLines(): String =
        replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

    private fun firstMeaningfulLine(text: String): String? =
        text.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }?.take(180)

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
                    mergeHeading("Глава $number", first),
                    number,
                    1,
                    number.toIntOrNull() ?: Int.MAX_VALUE / 4,
                )
            }
            lower.contains("эпилог") || lower.contains("epilogue") ->
                ChapterDescriptor(mergeHeading("Эпилог", first), null, 3, Int.MAX_VALUE - 2)
            lower.contains("послеслов") || lower.contains("afterword") ->
                ChapterDescriptor(mergeHeading("Послесловие", first), null, 4, Int.MAX_VALUE - 1)
            else -> ChapterDescriptor(
                first ?: stem.ifBlank { relativePath.substringAfterLast('/') },
                null,
                2,
                Int.MAX_VALUE / 2,
            )
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

    private fun safeName(value: String?): String =
        value.orEmpty().trim().replace('/', '／').ifBlank { "Без названия" }

    private fun isIgnoredPath(path: String): Boolean {
        val ignored = setOf(
            "служебные файлы", "служебное", "service files", "service", "равка", "равки",
            "raw", "raws", "macosx", "git", "архив устаревших", "metadata",
        )
        return path.split('/').any { normalizeKey(it) in ignored }
    }

    private fun detectVolumeName(path: String): String {
        val folders = path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank)
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
        NATURAL_NUMBER.replace(value.lowercase(Locale.ROOT)) { it.value.padStart(12, '0') }

    private fun normalizeKey(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun stableId(namespace: String, value: String): String =
        "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(20)}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun PlannedFolderChapter.toPlannedImportChapter(): PlannedImportChapter =
        PlannedImportChapter(
            id = id,
            volumeId = volumeId,
            name = candidate.descriptor.displayName,
            number = candidate.descriptor.number,
            sortOrder = sortOrder,
            contentHash = candidate.contentHash,
        )

    private data class PendingDocument(
        val document: DocumentFile,
        val relativePath: String,
        val depth: Int,
    )

    private data class FolderCandidate(
        val documentUri: Uri,
        val relativePath: String,
        val descriptor: ChapterDescriptor,
        val contentHash: String,
        val wordCount: Int,
    )

    private data class FolderScan(
        val rootName: String,
        val candidates: List<FolderCandidate>,
        val filesSkipped: Int,
    )

    private data class CachedFolderScan(
        val treeUri: String,
        val createdAt: Long,
        val scan: FolderScan,
    )

    private data class PlannedFolderVolume(
        val id: String,
        val name: String,
        val number: String?,
        val sortOrder: Int,
        val chapters: List<PlannedFolderChapter>,
    )

    private data class PlannedFolderChapter(
        val id: String,
        val volumeId: String,
        val sortOrder: Int,
        val candidate: FolderCandidate,
    )

    private data class ChapterDescriptor(
        val displayName: String,
        val number: String?,
        val kindRank: Int,
        val numericOrder: Int,
    )

    private companion object {
        const val MAX_TREE_ENTRIES = 10_000
        const val MAX_FOLDER_DEPTH = 24
        const val MAX_SINGLE_CHAPTER_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_IMPORT_BYTES = 192L * 1024L * 1024L
        const val SCAN_CACHE_TTL_MS = 5L * 60L * 1000L
        val CHAPTER_NUMBER = Regex("""(?i)(?:глава|chapter|chap|ch)[ _.-]*(\d+)""")
        val VOLUME_PATTERN = Regex("""(?i)(?:том|volume|vol)[ _.-]*\d+""")
        val VOLUME_NUMBER = Regex("""(\d+)""")
        val NATURAL_NUMBER = Regex("""\d+""")
        val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
    }
}
