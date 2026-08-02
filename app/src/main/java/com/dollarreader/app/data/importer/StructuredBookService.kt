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
        val existing = existingChapterHashes(parsed.titleId)
        val changes = parsed.changeSummary(existing)
        ImportPreview(
            titleId = parsed.titleId,
            title = parsed.title,
            format = parsed.format,
            totalChapters = parsed.chapters.size,
            filesSkipped = parsed.filesSkipped,
            updatedExistingTitle = existing.isNotEmpty(),
            volumes = listOf(
                ImportPreviewVolume(
                    name = parsed.volumeName,
                    chapters = parsed.chapters.map { chapter ->
                        ImportPreviewChapter(
                            name = chapter.title,
                            number = chapter.number,
                            sourcePath = chapter.relativePath,
                            change = when {
                                chapter.id !in existing -> ImportChapterChange.ADDED
                                existing[chapter.id] != chapter.contentHash -> ImportChapterChange.CHANGED
                                else -> ImportChapterChange.UNCHANGED
                            },
                        )
                    },
                ),
            ),
            changes = changes,
        )
    }

    suspend fun importBook(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val parsed = parse(uri)
        val existing = existingChapterHashes(parsed.titleId)
        val changes = parsed.changeSummary(existing)
        val titleRoot = File(context.filesDir, "library/${parsed.titleId}")
        val finalRoot = File(titleRoot, "structured")
        val stagingRoot = File(titleRoot, "structured.tmp-${System.currentTimeMillis()}")
        val backupRoot = File(titleRoot, "structured.backup-${System.currentTimeMillis()}")

        runCatching { stagingRoot.deleteRecursively() }
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

            val volumeId = stableId(parsed.titleId, "volume:${parsed.volumeName}")
            val plan = LocalTitleImport(
                id = parsed.titleId,
                title = parsed.title,
                author = parsed.author,
                format = parsed.format,
                sourceUri = uri.toString(),
                volumes = listOf(
                    LocalVolumeImport(
                        id = volumeId,
                        name = parsed.volumeName,
                        number = "1",
                        sortOrder = 1,
                        chapters = parsed.chapters.mapIndexed { index, chapter ->
                            LocalChapterImport(
                                id = chapter.id,
                                name = chapter.title,
                                number = chapter.number,
                                sortOrder = index + 1,
                                localPath = safeChild(finalRoot, chapter.relativePath).absolutePath,
                                contentHash = chapter.contentHash,
                                wordCount = chapter.wordCount,
                            )
                        },
                    ),
                ),
            )
            val updated = repository.importLocalTitle(plan)
            backupRoot.deleteRecursively()

            ImportResult(
                titleId = parsed.titleId,
                title = parsed.title,
                chaptersImported = parsed.chapters.size,
                filesSkipped = parsed.filesSkipped,
                format = parsed.format,
                updatedExistingTitle = updated,
                changes = changes,
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
            ?.toString(StandardCharsets.UTF_8)
            ?: throw ImportException("В EPUB отсутствует META-INF/container.xml")
        val opfPath = ROOTFILE_PATH.find(container)?.groupValues?.get(1)
            ?.let(::normalizeZipPath)
            ?: throw ImportException("Не удалось найти пакет EPUB")
        val opf = entries[opfPath]?.toString(StandardCharsets.UTF_8)
            ?: throw ImportException("В EPUB отсутствует файл пакета $opfPath")
        val opfDirectory = opfPath.substringBeforeLast('/', "")

        val title = decodeHtml(
            metadataValue(opf, "title")
                ?: displayName.substringBeforeLast('.'),
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
            )
        }.associateBy { it.id }
        val spineIds = ITEMREF_TAG.findAll(opf)
            .mapNotNull { parseAttributes(it.groupValues[1])["idref"] }
            .toList()
        val contentItems = (if (spineIds.isNotEmpty()) {
            spineIds.mapNotNull(manifest::get)
        } else {
            manifest.values.filter { it.mediaType.contains("html") }
                .sortedBy { it.path }
        }).distinctBy { it.path }
        if (contentItems.isEmpty()) {
            throw ImportException("В EPUB не найдено читаемых глав")
        }

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
            val chapterTitle = extractHeading(rawHtml)
                .ifBlank { "Глава ${index + 1}" }
            val sanitized = sanitizeHtml(rawHtml, chapterTitle)
            val bytes = sanitized.toByteArray(StandardCharsets.UTF_8)
            files[item.path] = bytes
            ParsedChapter(
                id = stableId(titleId, "chapter:${item.path}"),
                title = chapterTitle,
                number = (index + 1).toString(),
                relativePath = item.path,
                contentHash = sha256(bytes),
                wordCount = countWords(plainText(sanitized)),
            )
        }
        if (chapters.isEmpty()) throw ImportException("Все главы EPUB оказались повреждены")

        return ParsedStructuredBook(
            titleId = titleId,
            title = title,
            author = author,
            format = "EPUB",
            volumeName = "Основное",
            chapters = chapters,
            files = files,
            filesSkipped = skipped,
        )
    }

    private fun parseHtml(uri: Uri, displayName: String): ParsedStructuredBook {
        val bytes = openLimited(uri, MAX_HTML_BYTES)
        val raw = decodeStructuredText(bytes)
        val title = extractDocumentTitle(raw)
            .ifBlank { displayName.substringBeforeLast('.') }
            .ifBlank { "HTML-документ" }
        val author = AUTHOR_META.find(raw)?.groupValues?.get(2)
            ?.let(::decodeHtml)
            ?.ifBlank { null }
            ?: "Не указан"
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
                relativePath = relativePath,
                contentHash = sha256(chapterBytes),
                wordCount = countWords(plainText(document)),
            )
        }
        return ParsedStructuredBook(
            titleId = titleId,
            title = title,
            author = author,
            format = "HTML",
            volumeName = "Документ",
            chapters = chapters,
            files = files,
            filesSkipped = 0,
        )
    }

    private fun readZipEntries(uri: Uri): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var count = 0
        var total = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть EPUB")
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
        return entries
    }

    private suspend fun existingChapterHashes(titleId: String): Map<String, String?> {
        val title = repository.observeTitle(titleId).first() ?: return emptyMap()
        return title.volumes.flatMap { it.chapters }
            .associate { chapter -> chapter.id to chapter.contentHash }
    }

    private fun ParsedStructuredBook.changeSummary(existing: Map<String, String?>): ImportChangeSummary {
        val current = chapters.associateBy { it.id }
        val added = current.keys.count { it !in existing }
        val changed = current.values.count { chapter ->
            chapter.id in existing && existing[chapter.id] != chapter.contentHash
        }
        val unchanged = current.size - added - changed
        val removed = existing.keys.count { it !in current }
        return ImportChangeSummary(added, changed, removed, unchanged)
    }

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
            HTML_OPEN.containsMatchIn(html) -> html.replaceFirst(HTML_OPEN, "$0<head><title>${escapeHtml(title)}</title>$injectedHead</head>")
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
            match.groupValues[1].lowercase(Locale.ROOT) to
                (match.groupValues[3].ifEmpty { match.groupValues[4] })
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
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        return String(bytes, StandardCharsets.UTF_8)
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
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
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
    )

    private data class ParsedChapter(
        val id: String,
        val title: String,
        val number: String?,
        val relativePath: String,
        val contentHash: String,
        val wordCount: Int,
    )

    private data class ParsedStructuredBook(
        val titleId: String,
        val title: String,
        val author: String,
        val format: String,
        val volumeName: String,
        val chapters: List<ParsedChapter>,
        val files: Map<String, ByteArray>,
        val filesSkipped: Int,
    )

    private companion object {
        val STRUCTURED_EXTENSIONS = setOf("epub", "html", "htm", "xhtml")
        val ROOTFILE_PATH = Regex("""(?is)full-path\s*=\s*[\"']([^\"']+)[\"']""")
        val ITEM_TAG = Regex("""(?is)<item\b([^>]*)/?>""")
        val ITEMREF_TAG = Regex("""(?is)<itemref\b([^>]*)/?>""")
        val ATTRIBUTE = Regex("""([\w:.-]+)\s*=\s*([\"'])(.*?)\2|([\w:.-]+)\s*=\s*([^\s>]+)""")
        val TITLE_TAG = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
        val HEADING_TAG = Regex("""(?is)<(h1|h2|h3)\b[^>]*>(.*?)</\1\s*>""")
        val AUTHOR_META = Regex("""(?is)<meta\b[^>]*name\s*=\s*([\"'])author\1[^>]*content\s*=\s*([\"'])(.*?)\2[^>]*>""")
        val HEAD_BLOCK = Regex("""(?is)<head\b[^>]*>.*?</head\s*>""")
        val ARTICLE_BLOCK = Regex("""(?is)<article\b[^>]*>.*?</article\s*>""")
        val XML_DECLARATION = Regex("""(?is)<\?xml.*?\?>""")
        val DANGEROUS_BLOCKS = Regex("""(?is)<(script|iframe|object|embed|form|input|button)\b.*?</\1\s*>|<(script|iframe|object|embed|form|input|button)\b[^>]*/?>""")
        val EVENT_ATTRIBUTE = Regex("""(?is)\s+on[a-z]+\s*=\s*([\"']).*?\1""")
        val JAVASCRIPT_URL = Regex("""(?is)javascript\s*:""")
        val HEAD_OPEN = Regex("""(?is)<head\b[^>]*>""")
        val HTML_OPEN = Regex("""(?is)<html\b[^>]*>""")
        val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
        const val MAX_EPUB_ENTRIES = 20_000
        const val MAX_EPUB_ENTRY_BYTES = 24L * 1024L * 1024L
        const val MAX_EPUB_TOTAL_BYTES = 256L * 1024L * 1024L
        const val MAX_HTML_BYTES = 48L * 1024L * 1024L
    }
}
