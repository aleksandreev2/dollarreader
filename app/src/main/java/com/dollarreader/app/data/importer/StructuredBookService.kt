package com.dollarreader.app.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
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

class StructuredBookService(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun previewBook(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val parsed = parse(uri)
        val existing = loadExistingChapters(parsed.titleId)
        val volumes = parsed.groupedVolumes()
        val planned = volumes.flatMap { volume ->
            volume.chapters.map { chapter -> chapter.toDiffChapter(volume.id) }
        }
        val diff = calculateImportDiff(existing, planned)

        ImportPreview(
            titleId = parsed.titleId,
            title = parsed.title,
            format = parsed.format,
            totalChapters = planned.size,
            filesSkipped = parsed.filesSkipped,
            updatedExistingTitle = existing.isNotEmpty(),
            volumes = volumes.map { volume ->
                ImportPreviewVolume(
                    name = volume.name,
                    chapters = volume.chapters.map { chapter ->
                        ImportPreviewChapter(
                            name = chapter.title,
                            number = chapter.number,
                            sourcePath = chapter.relativePath,
                            change = diff.entries.getValue(chapter.id).change,
                        )
                    },
                )
            },
            changes = diff.summary,
        )
    }

    suspend fun importBook(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val parsed = parse(uri)
        val existing = loadExistingChapters(parsed.titleId)
        val groupedVolumes = parsed.groupedVolumes()
        val planned = groupedVolumes.flatMap { volume ->
            volume.chapters.map { chapter -> chapter.toDiffChapter(volume.id) }
        }
        val diff = calculateImportDiff(existing, planned)

        if (!diff.summary.hasChanges && existing.isNotEmpty()) {
            return@withContext ImportResult(
                titleId = parsed.titleId,
                title = parsed.title,
                chaptersImported = planned.size,
                filesSkipped = parsed.filesSkipped,
                format = parsed.format,
                updatedExistingTitle = true,
                changes = diff.summary,
            )
        }

        val titleRoot = File(context.filesDir, "library/${parsed.titleId}")
        val finalRoot = File(titleRoot, "structured")
        val stagingRoot = File(titleRoot, "structured.tmp-${System.currentTimeMillis()}")
        val backupRoot = File(titleRoot, "structured.backup-${System.currentTimeMillis()}")
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()

        try {
            parsed.files.forEach { (relativePath, bytes) ->
                val target = safeChild(stagingRoot, relativePath)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }

            if (finalRoot.exists() && !finalRoot.renameTo(backupRoot)) {
                throw ImportException("Не удалось подготовить обновление структурированной книги")
            }
            if (!stagingRoot.renameTo(finalRoot)) {
                if (backupRoot.exists()) backupRoot.renameTo(finalRoot)
                throw ImportException("Не удалось сохранить подготовленную книгу")
            }

            val plan = LocalTitleImport(
                id = parsed.titleId,
                title = parsed.title,
                author = parsed.author,
                format = parsed.format,
                sourceUri = uri.toString(),
                volumes = groupedVolumes.map { volume ->
                    LocalVolumeImport(
                        id = volume.id,
                        name = volume.name,
                        number = volume.number,
                        sortOrder = volume.sortOrder,
                        chapters = volume.chapters.map { chapter ->
                            LocalChapterImport(
                                id = chapter.id,
                                name = chapter.title,
                                number = chapter.number,
                                sortOrder = chapter.sortOrder,
                                localPath = safeChild(finalRoot, chapter.relativePath).absolutePath,
                                contentHash = chapter.contentHash,
                                wordCount = chapter.wordCount,
                            )
                        },
                    )
                },
                coverPath = parsed.coverRelativePath?.let { path ->
                    safeChild(finalRoot, path).absolutePath
                },
            )
            val updated = repository.importLocalTitle(plan)
            backupRoot.deleteRecursively()

            ImportResult(
                titleId = parsed.titleId,
                title = parsed.title,
                chaptersImported = planned.size,
                filesSkipped = parsed.filesSkipped,
                format = parsed.format,
                updatedExistingTitle = updated,
                changes = diff.summary,
            )
        } catch (error: Throwable) {
            stagingRoot.deleteRecursively()
            if (backupRoot.exists()) {
                finalRoot.deleteRecursively()
                backupRoot.renameTo(finalRoot)
            }
            if (error is ImportException) throw error
            throw ImportException(
                "Не удалось импортировать ${parsed.format}: ${error.message ?: "неизвестная ошибка"}",
                error,
            )
        }
    }

    suspend fun supports(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        extension(queryDisplayName(uri).orEmpty()) in STRUCTURED_EXTENSIONS
    }

    private fun parse(uri: Uri): ParsedStructuredBook {
        val displayName = queryDisplayName(uri) ?: "Книга"
        return when (extension(displayName)) {
            "epub" -> parseEpub(uri, displayName)
            "html", "htm", "xhtml" -> parseHtml(uri, displayName)
            else -> throw ImportException("Ожидался файл EPUB, HTML или XHTML")
        }
    }

    private fun parseEpub(uri: Uri, displayName: String): ParsedStructuredBook {
        val entries = readZipEntries(uri)
        val container = entries["META-INF/container.xml"]
            ?.let(::decodeStructuredText)
            ?: throw ImportException("В EPUB отсутствует META-INF/container.xml")
        val opfPath = ROOTFILE_PATH.find(container)?.groupValues?.get(1)
            ?.let(::normalizeZipPath)
            ?: throw ImportException("Не удалось найти пакет EPUB")
        val opf = entries[opfPath]?.let(::decodeStructuredText)
            ?: throw ImportException("В EPUB отсутствует файл пакета $opfPath")

        val title = decodeHtml(
            metadataValue(opf, "title") ?: displayName.substringBeforeLast('.'),
        ).ifBlank { "Книга EPUB" }
        val author = decodeHtml(metadataValue(opf, "creator").orEmpty())
            .ifBlank { "Не указан" }
        val titleId = stableId("title", title)

        val manifest = ITEM_TAG.findAll(opf).mapNotNull { match ->
            val attributes = parseAttributes(match.groupValues[1])
            val id = attributes["id"] ?: return@mapNotNull null
            val href = attributes["href"] ?: return@mapNotNull null
            ManifestItem(
                id = id,
                path = resolveZipPath(opfPath, href),
                mediaType = attributes["media-type"].orEmpty().lowercase(Locale.ROOT),
                properties = attributes["properties"].orEmpty()
                    .lowercase(Locale.ROOT)
                    .split(Regex("""\s+"""))
                    .filter(String::isNotBlank)
                    .toSet(),
            )
        }.associateBy(ManifestItem::id)

        val spineAttributes = SPINE_TAG.find(opf)
            ?.groupValues?.get(1)
            ?.let(::parseAttributes)
            .orEmpty()
        val spineIds = ITEMREF_TAG.findAll(opf)
            .mapNotNull { parseAttributes(it.groupValues[1])["idref"] }
            .toList()
        val contentItems = (if (spineIds.isNotEmpty()) {
            spineIds.mapNotNull(manifest::get)
        } else {
            manifest.values.filter { it.mediaType.contains("html") }
                .sortedBy(ManifestItem::path)
        }).distinctBy(ManifestItem::path)
        if (contentItems.isEmpty()) throw ImportException("В EPUB не найдено читаемых глав")

        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        val ncxItem = spineAttributes["toc"]?.let(manifest::get)
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val navigation = when {
            navItem != null && entries[navItem.path] != null -> runCatching {
                EpubNavigationParser.parse(
                    document = decodeStructuredText(entries.getValue(navItem.path)),
                    documentPath = navItem.path,
                    ncx = false,
                )
            }.getOrDefault(emptyMap())
            ncxItem != null && entries[ncxItem.path] != null -> runCatching {
                EpubNavigationParser.parse(
                    document = decodeStructuredText(entries.getValue(ncxItem.path)),
                    documentPath = ncxItem.path,
                    ncx = true,
                )
            }.getOrDefault(emptyMap())
            else -> emptyMap()
        }

        val coverId = META_COVER.find(opf)?.groupValues?.get(2)
        val coverItem = manifest.values.firstOrNull { "cover-image" in it.properties }
            ?: coverId?.let(manifest::get)
        val files = linkedMapOf<String, ByteArray>()
        var skipped = 0
        manifest.values.forEach { item ->
            val bytes = entries[item.path]
            if (bytes == null) {
                skipped += 1
            } else if (item.mediaType.isAssetType()) {
                files[item.path] = bytes
            }
        }

        val chapters = contentItems.mapIndexedNotNull { index, item ->
            val rawBytes = entries[item.path]
            if (rawBytes == null) {
                skipped += 1
                return@mapIndexedNotNull null
            }
            val rawHtml = decodeStructuredText(rawBytes)
            val nav = navigation[item.path]
            val chapterTitle = nav?.title
                ?.takeIf(String::isNotBlank)
                ?: extractHeading(rawHtml).ifBlank { "Глава ${index + 1}" }
            val volumeName = nav?.volume
                ?.takeIf(String::isNotBlank)
                ?: inferVolumeName(item.path)
                ?: "Основное"
            val sanitized = sanitizeHtml(rawHtml, chapterTitle)
            val chapterBytes = sanitized.toByteArray(StandardCharsets.UTF_8)
            files[item.path] = chapterBytes
            ParsedChapter(
                id = stableId(titleId, "chapter:${item.path}"),
                title = chapterTitle,
                number = (index + 1).toString(),
                volumeName = volumeName.take(160),
                relativePath = item.path,
                contentHash = sha256(chapterBytes),
                wordCount = countWords(plainText(sanitized)),
                sortOrder = index + 1,
            )
        }
        if (chapters.isEmpty()) throw ImportException("Все главы EPUB оказались повреждены")

        return ParsedStructuredBook(
            titleId = titleId,
            title = title,
            author = author,
            format = "EPUB",
            chapters = chapters,
            files = files,
            coverRelativePath = coverItem?.path?.takeIf(files::containsKey),
            filesSkipped = skipped,
        )
    }

    private fun parseHtml(uri: Uri, displayName: String): ParsedStructuredBook {
        val bytes = openLimited(uri, MAX_HTML_BYTES)
        val raw = decodeStructuredText(bytes)
        val title = extractDocumentTitle(raw)
            .ifBlank { displayName.substringBeforeLast('.') }
            .ifBlank { "HTML-документ" }
        val author = extractAuthor(raw).ifBlank { "Не указан" }
        val titleId = stableId("title", title)
        val head = HEAD_BLOCK.find(raw)?.value.orEmpty()
        val articles = ARTICLE_BLOCK.findAll(raw).map { it.value }.toList()
        val sections = if (articles.size >= 2) articles else listOf(raw)
        val files = linkedMapOf<String, ByteArray>()
        val chapters = sections.mapIndexed { index, section ->
            val chapterTitle = extractHeading(section)
                .ifBlank { if (sections.size == 1) title else "Раздел ${index + 1}" }
            val document = if (sections.size == 1) {
                sanitizeHtml(section, chapterTitle)
            } else {
                sanitizeHtml("<html>$head<body>$section</body></html>", chapterTitle)
            }
            val relativePath = "chapters/${index + 1}.html"
            val chapterBytes = document.toByteArray(StandardCharsets.UTF_8)
            files[relativePath] = chapterBytes
            ParsedChapter(
                id = stableId(titleId, "chapter:$index:$chapterTitle"),
                title = chapterTitle,
                number = (index + 1).toString(),
                volumeName = "Документ",
                relativePath = relativePath,
                contentHash = sha256(chapterBytes),
                wordCount = countWords(plainText(document)),
                sortOrder = index + 1,
            )
        }
        return ParsedStructuredBook(
            titleId = titleId,
            title = title,
            author = author,
            format = "HTML",
            chapters = chapters,
            files = files,
            coverRelativePath = null,
            filesSkipped = 0,
        )
    }

    private fun readZipEntries(uri: Uri): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var count = 0
        var total = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть EPUB")
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count += 1
                    if (count > MAX_EPUB_ENTRIES) throw ImportException("В EPUB слишком много файлов")
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val path = normalizeZipPath(entry.name)
                    val bytes = readLimited(zip, MAX_EPUB_ENTRY_BYTES)
                    total += bytes.size
                    if (total > MAX_EPUB_TOTAL_BYTES) throw ImportException("EPUB слишком большой")
                    entries[path] = bytes
                    zip.closeEntry()
                }
            }
        } catch (error: ImportException) {
            throw error
        } catch (error: Throwable) {
            throw ImportException("Не удалось открыть EPUB: ${error.message ?: "повреждённый архив"}", error)
        }
        return entries
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
        }.associateBy(StoredImportChapter::id)
    }

    private fun ParsedStructuredBook.groupedVolumes(): List<ParsedVolume> {
        val groups = linkedMapOf<String, MutableList<ParsedChapter>>()
        chapters.sortedBy(ParsedChapter::sortOrder).forEach { chapter ->
            groups.getOrPut(chapter.volumeName) { mutableListOf() } += chapter
        }
        return groups.entries.mapIndexed { index, (name, volumeChapters) ->
            ParsedVolume(
                id = stableId(titleId, "volume:$name"),
                name = name,
                number = (index + 1).toString(),
                sortOrder = index + 1,
                chapters = volumeChapters,
            )
        }
    }

    private fun ParsedChapter.toDiffChapter(volumeId: String): PlannedImportChapter =
        PlannedImportChapter(
            id = id,
            volumeId = volumeId,
            name = title,
            number = number,
            sortOrder = sortOrder,
            contentHash = contentHash,
        )

    private fun sanitizeHtml(source: String, title: String): String {
        var html = source
            .replace(XML_DECLARATION, "")
            .replace(DANGEROUS_BLOCKS, "")
            .replace(EVENT_ATTRIBUTE, "")
            .replace(JAVASCRIPT_URL, "")
        val injectedHead = """
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:;">
            <style>
                html, body { max-width: 100%; overflow-x: hidden; }
                body { margin: 0 auto; padding: 20px; box-sizing: border-box; }
                img, svg { max-width: 100%; height: auto; }
                table { max-width: 100%; overflow-x: auto; display: block; }
                pre { white-space: pre-wrap; overflow-wrap: anywhere; }
            </style>
        """.trimIndent()
        html = when {
            HEAD_OPEN.containsMatchIn(html) -> html.replaceFirst(HEAD_OPEN, "$0$injectedHead")
            HTML_OPEN.containsMatchIn(html) -> html.replaceFirst(
                HTML_OPEN,
                "$0<head><title>${escapeHtml(title)}</title>$injectedHead</head>",
            )
            else -> "<html><head><title>${escapeHtml(title)}</title>$injectedHead</head><body>$html</body></html>"
        }
        return html
    }

    private fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00A0', ' ')
            .trim()

    private fun extractDocumentTitle(html: String): String =
        TITLE_TAG.find(html)?.groupValues?.get(1)?.let(::decodeHtml).orEmpty()
            .ifBlank { extractHeading(html) }

    private fun extractHeading(html: String): String =
        HEADING_TAG.find(html)?.groupValues?.get(2)?.let(::decodeHtml).orEmpty()
            .lineSequence().firstOrNull().orEmpty().trim().take(180)

    private fun extractAuthor(html: String): String {
        val meta = META_TAG.findAll(html).map { parseAttributes(it.groupValues[1]) }
            .firstOrNull { attributes ->
                attributes["name"].equals("author", true) ||
                    attributes["property"].equals("author", true)
            }
        return meta?.get("content")?.let(::decodeHtml).orEmpty()
    }

    private fun inferVolumeName(path: String): String? {
        val segments = path.substringBeforeLast('/', "").split('/')
        val segment = segments.lastOrNull { VOLUME_DIRECTORY.containsMatchIn(it) } ?: return null
        return segment.replace('_', ' ').replace('-', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun metadataValue(opf: String, localName: String): String? {
        val regex = Regex(
            """(?is)<(?:[\w.-]+:)?${Regex.escape(localName)}\b[^>]*>(.*?)</(?:[\w.-]+:)?${Regex.escape(localName)}\s*>""",
        )
        return regex.find(opf)?.groupValues?.get(1)
    }

    private fun parseAttributes(source: String): Map<String, String> =
        ATTRIBUTE.findAll(source).associate { match ->
            val name = match.groupValues[1].ifBlank { match.groupValues[4] }
                .lowercase(Locale.ROOT)
            val value = match.groupValues[3].ifBlank { match.groupValues[5] }
            name to value
        }

    private fun resolveZipPath(baseFile: String, relative: String): String {
        val withoutFragment = relative.substringBefore('#').substringBefore('?')
        if (withoutFragment.startsWith('/')) return normalizeZipPath(withoutFragment)
        val base = baseFile.substringBeforeLast('/', "")
        return normalizeZipPath(if (base.isBlank()) withoutFragment else "$base/$withoutFragment")
    }

    private fun normalizeZipPath(raw: String): String {
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> {
                    if (stack.isEmpty()) throw ImportException("EPUB содержит небезопасный путь")
                    stack.removeLast()
                }
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun safeChild(root: File, relativePath: String): File {
        val child = File(root, normalizeZipPath(relativePath))
        val rootPath = root.canonicalPath + File.separator
        if (!child.canonicalPath.startsWith(rootPath)) {
            throw ImportException("Обнаружен небезопасный путь файла")
        }
        return child
    }

    private fun openLimited(uri: Uri, limit: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть выбранный файл")
        return input.use { readLimited(it, limit) }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ImportException("Файл превышает безопасный размер")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeStructuredText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2
            else -> null to 0
        }
        if (charset != null) return String(bytes, offset, bytes.size - offset, charset)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, java.nio.charset.Charset.forName("windows-1251"))
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

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun stableId(namespace: String, value: String): String =
        "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(24)}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun countWords(text: String): Int = WORD_PATTERN.findAll(text).count()

    private fun String.isAssetType(): Boolean =
        startsWith("image/") || startsWith("text/css") || startsWith("font/") ||
            contains("font") || contains("svg")

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private data class ManifestItem(
        val id: String,
        val path: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private data class ParsedChapter(
        val id: String,
        val title: String,
        val number: String?,
        val volumeName: String,
        val relativePath: String,
        val contentHash: String,
        val wordCount: Int,
        val sortOrder: Int,
    )

    private data class ParsedVolume(
        val id: String,
        val name: String,
        val number: String?,
        val sortOrder: Int,
        val chapters: List<ParsedChapter>,
    )

    private data class ParsedStructuredBook(
        val titleId: String,
        val title: String,
        val author: String,
        val format: String,
        val chapters: List<ParsedChapter>,
        val files: Map<String, ByteArray>,
        val coverRelativePath: String?,
        val filesSkipped: Int,
    )

    private companion object {
        val STRUCTURED_EXTENSIONS = setOf("epub", "html", "htm", "xhtml")
        val ROOTFILE_PATH = Regex("""(?is)full-path\s*=\s*[\"']([^\"']+)[\"']""")
        val ITEM_TAG = Regex("""(?is)<item\b([^>]*)/?>""")
        val SPINE_TAG = Regex("""(?is)<spine\b([^>]*)>""")
        val ITEMREF_TAG = Regex("""(?is)<itemref\b([^>]*)/?>""")
        val ATTRIBUTE = Regex("""([\w:.-]+)\s*=\s*([\"'])(.*?)\2|([\w:.-]+)\s*=\s*([^\s>]+)""")
        val META_COVER = Regex("""(?is)<meta\b[^>]*name\s*=\s*([\"'])cover\1[^>]*content\s*=\s*([\"'])(.*?)\2[^>]*>""")
        val META_TAG = Regex("""(?is)<meta\b([^>]*)/?>""")
        val TITLE_TAG = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
        val HEADING_TAG = Regex("""(?is)<(h1|h2|h3)\b[^>]*>(.*?)</\1\s*>""")
        val HEAD_BLOCK = Regex("""(?is)<head\b[^>]*>.*?</head\s*>""")
        val ARTICLE_BLOCK = Regex("""(?is)<article\b[^>]*>.*?</article\s*>""")
        val XML_DECLARATION = Regex("""(?is)<\?xml.*?\?>""")
        val DANGEROUS_BLOCKS = Regex("""(?is)<(script|iframe|object|embed|form|input|button)\b.*?</\1\s*>|<(script|iframe|object|embed|form|input|button)\b[^>]*/?>""")
        val EVENT_ATTRIBUTE = Regex("""(?is)\s+on[a-z]+\s*=\s*([\"']).*?\1""")
        val JAVASCRIPT_URL = Regex("""(?is)javascript\s*:""")
        val HEAD_OPEN = Regex("""(?is)<head\b[^>]*>""")
        val HTML_OPEN = Regex("""(?is)<html\b[^>]*>""")
        val VOLUME_DIRECTORY = Regex("""(?i)^(том|volume|vol\.?|часть|part)\s*[-_. ]*\d+.*$""")
        val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
        const val MAX_EPUB_ENTRIES = 20_000
        const val MAX_EPUB_ENTRY_BYTES = 24L * 1024L * 1024L
        const val MAX_EPUB_TOTAL_BYTES = 256L * 1024L * 1024L
        const val MAX_HTML_BYTES = 48L * 1024L * 1024L
    }
}
