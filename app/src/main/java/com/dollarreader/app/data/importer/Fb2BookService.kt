package com.dollarreader.app.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Base64
import com.dollarreader.app.data.LibraryRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class Fb2BookService(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun supports(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        queryDisplayName(uri).orEmpty().substringAfterLast('.', "")
            .equals("fb2", ignoreCase = true)
    }

    suspend fun previewBook(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        persistReadPermission(uri)
        val parsed = parse(uri)
        val existing = loadExistingChapters(parsed.titleId)
        val planned = parsed.volumes.flatMap { volume ->
            volume.chapters.map { chapter -> chapter.toDiffChapter(volume.id) }
        }
        val diff = calculateImportDiff(existing, planned)

        ImportPreview(
            titleId = parsed.titleId,
            title = parsed.title,
            format = FORMAT,
            totalChapters = planned.size,
            filesSkipped = parsed.filesSkipped,
            updatedExistingTitle = existing.isNotEmpty(),
            volumes = parsed.volumes.map { volume ->
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
        val planned = parsed.volumes.flatMap { volume ->
            volume.chapters.map { chapter -> chapter.toDiffChapter(volume.id) }
        }
        val diff = calculateImportDiff(existing, planned)

        if (!diff.summary.hasChanges && existing.isNotEmpty()) {
            return@withContext ImportResult(
                titleId = parsed.titleId,
                title = parsed.title,
                chaptersImported = planned.size,
                filesSkipped = parsed.filesSkipped,
                format = FORMAT,
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
                throw ImportException("Не удалось подготовить обновление FB2")
            }
            if (!stagingRoot.renameTo(finalRoot)) {
                if (backupRoot.exists()) backupRoot.renameTo(finalRoot)
                throw ImportException("Не удалось сохранить подготовленную FB2-книгу")
            }

            val plan = LocalTitleImport(
                id = parsed.titleId,
                title = parsed.title,
                author = parsed.author,
                format = FORMAT,
                sourceUri = uri.toString(),
                volumes = parsed.volumes.map { volume ->
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
                format = FORMAT,
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
                "Не удалось импортировать FB2: ${error.message ?: "неизвестная ошибка"}",
                error,
            )
        }
    }

    private fun parse(uri: Uri): ParsedFb2Book {
        val bytes = openLimited(uri, MAX_FB2_BYTES)
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(ByteArrayInputStream(bytes), null)
        }

        var bookTitle = ""
        val authors = mutableListOf<String>()
        var currentAuthor = mutableListOf<String>()
        var insideTitleInfo = false
        var insideAuthor = false
        var insideCoverPage = false
        var metadataCapture: MetadataCapture? = null
        var metadataDepth = -1
        var metadataText = StringBuilder()
        var coverBinaryId: String? = null

        var bodyIndex = 0
        var bodyName = "Основное"
        var sectionSequence = 0
        var sectionTitleDepth = -1
        val sections = mutableListOf<SectionBuilder>()
        val sectionStack = ArrayDeque<SectionBuilder>()
        val binaries = linkedMapOf<String, DecodedBinary>()
        var currentBinary: BinaryBuilder? = null
        var filesSkipped = 0

        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.lowercase(Locale.ROOT)
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (name) {
                        "title-info" -> insideTitleInfo = true
                        "author" -> if (insideTitleInfo) {
                            insideAuthor = true
                            currentAuthor = mutableListOf()
                        }
                        "book-title" -> if (insideTitleInfo) {
                            metadataCapture = MetadataCapture.BOOK_TITLE
                            metadataDepth = parser.depth
                            metadataText = StringBuilder()
                        }
                        "first-name", "middle-name", "last-name" -> if (insideTitleInfo && insideAuthor) {
                            metadataCapture = MetadataCapture.AUTHOR_PART
                            metadataDepth = parser.depth
                            metadataText = StringBuilder()
                        }
                        "coverpage" -> if (insideTitleInfo) insideCoverPage = true
                        "body" -> {
                            bodyIndex += 1
                            val rawName = attribute(parser, "name").orEmpty().trim()
                            bodyName = when {
                                rawName.equals("notes", true) -> "Примечания"
                                rawName.equals("comments", true) -> "Комментарии"
                                rawName.isNotBlank() -> rawName.replaceFirstChar { it.titlecase() }
                                bodyIndex == 1 -> "Основное"
                                else -> "Часть $bodyIndex"
                            }
                        }
                        "section" -> {
                            val parent = sectionStack.lastOrNull()
                            parent?.hasChildren = true
                            sectionSequence += 1
                            val section = SectionBuilder(
                                sequence = sectionSequence,
                                rootSequence = parent?.rootSequence ?: sectionSequence,
                                bodyName = bodyName,
                            )
                            sectionStack.addLast(section)
                        }
                        "title" -> if (sectionStack.isNotEmpty()) {
                            sectionTitleDepth = parser.depth
                        }
                        "p" -> sectionStack.lastOrNull()?.appendOpen("p")
                        "subtitle" -> sectionStack.lastOrNull()?.appendOpen("h2")
                        "emphasis" -> sectionStack.lastOrNull()?.appendOpen("em")
                        "strong" -> sectionStack.lastOrNull()?.appendOpen("strong")
                        "strikethrough" -> sectionStack.lastOrNull()?.appendOpen("s")
                        "sub" -> sectionStack.lastOrNull()?.appendOpen("sub")
                        "sup" -> sectionStack.lastOrNull()?.appendOpen("sup")
                        "code" -> sectionStack.lastOrNull()?.appendOpen("code")
                        "empty-line" -> sectionStack.lastOrNull()?.apply {
                            html.append("<br/>")
                            directContent = true
                        }
                        "image" -> {
                            val href = hrefAttribute(parser)?.removePrefix("#").orEmpty()
                            if (insideCoverPage && href.isNotBlank()) coverBinaryId = href
                            if (sectionStack.isNotEmpty() && href.isNotBlank()) {
                                val safeId = safeResourceId(href)
                                sectionStack.last().apply {
                                    html.append("<figure><img src=\"../images/")
                                        .append(escapeHtmlAttribute(safeId))
                                        .append("\"/></figure>")
                                    directContent = true
                                }
                            }
                        }
                        "binary" -> {
                            currentBinary = BinaryBuilder(
                                id = attribute(parser, "id").orEmpty(),
                                contentType = attribute(parser, "content-type").orEmpty(),
                            )
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.orEmpty()
                        val binary = currentBinary
                        when {
                            binary != null -> {
                                if (binary.base64.length + text.length > MAX_BASE64_CHARS) {
                                    throw ImportException("В FB2 обнаружено слишком большое вложение")
                                }
                                binary.base64.append(text)
                            }
                            metadataCapture != null -> metadataText.append(text)
                            sectionStack.isNotEmpty() && sectionTitleDepth >= 0 -> {
                                sectionStack.last().title.append(text)
                            }
                            sectionStack.isNotEmpty() -> {
                                sectionStack.last().apply {
                                    html.append(escapeHtml(text))
                                    if (text.isNotBlank()) directContent = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when {
                        metadataCapture != null && parser.depth == metadataDepth -> {
                            val value = metadataText.toString().normalizeText()
                            when (metadataCapture) {
                                MetadataCapture.BOOK_TITLE -> bookTitle = value
                                MetadataCapture.AUTHOR_PART -> if (value.isNotBlank()) currentAuthor += value
                                null -> Unit
                            }
                            metadataCapture = null
                            metadataDepth = -1
                            metadataText = StringBuilder()
                        }
                        name == "p" -> sectionStack.lastOrNull()?.appendClose("p")
                        name == "subtitle" -> sectionStack.lastOrNull()?.appendClose("h2")
                        name == "emphasis" -> sectionStack.lastOrNull()?.appendClose("em")
                        name == "strong" -> sectionStack.lastOrNull()?.appendClose("strong")
                        name == "strikethrough" -> sectionStack.lastOrNull()?.appendClose("s")
                        name == "sub" -> sectionStack.lastOrNull()?.appendClose("sub")
                        name == "sup" -> sectionStack.lastOrNull()?.appendClose("sup")
                        name == "code" -> sectionStack.lastOrNull()?.appendClose("code")
                        name == "title" && sectionTitleDepth == parser.depth -> sectionTitleDepth = -1
                        name == "section" && sectionStack.isNotEmpty() -> {
                            val section = sectionStack.removeLast()
                            if (section.directContent || !section.hasChildren) sections += section
                        }
                        name == "author" && insideAuthor -> {
                            val author = currentAuthor.joinToString(" ").normalizeText()
                            if (author.isNotBlank()) authors += author
                            insideAuthor = false
                        }
                        name == "coverpage" -> insideCoverPage = false
                        name == "title-info" -> insideTitleInfo = false
                        name == "binary" -> {
                            val binary = currentBinary
                            currentBinary = null
                            if (binary == null || binary.id.isBlank()) {
                                filesSkipped += 1
                            } else {
                                val decoded = runCatching {
                                    Base64.decode(binary.base64.toString(), Base64.DEFAULT)
                                }.getOrNull()
                                if (decoded == null || decoded.isEmpty() || decoded.size > MAX_BINARY_BYTES) {
                                    filesSkipped += 1
                                } else {
                                    binaries[binary.id] = DecodedBinary(
                                        id = binary.id,
                                        contentType = binary.contentType,
                                        bytes = decoded,
                                    )
                                }
                            }
                        }
                    }
                }
                parser.next()
            }
        } catch (error: ImportException) {
            throw error
        } catch (error: Throwable) {
            throw ImportException(
                "Не удалось разобрать FB2: ${error.message ?: "повреждённый XML"}",
                error,
            )
        }

        val title = bookTitle.ifBlank {
            queryDisplayName(uri).orEmpty().substringBeforeLast('.').ifBlank { "Книга FB2" }
        }
        val author = authors.distinct().joinToString(", ").ifBlank { "Не указан" }
        val titleId = stableId("title", title)
        val bySequence = sections.associateBy(SectionBuilder::sequence)
        val selectedSections = sections.sortedBy(SectionBuilder::sequence)
        if (selectedSections.isEmpty()) throw ImportException("В FB2 не найдено читаемых разделов")

        val files = linkedMapOf<String, ByteArray>()
        binaries.values.forEach { binary ->
            files["images/${safeResourceId(binary.id)}"] = binary.bytes
        }

        val rawChapters = selectedSections.mapIndexed { index, section ->
            val root = bySequence[section.rootSequence]
            val volumeName = root?.title?.toString().normalizeText()
                ?.takeIf { root.hasChildren && it.isNotBlank() }
                ?: section.bodyName
            val chapterTitle = section.title.toString().normalizeText()
                .ifBlank { "Раздел ${index + 1}" }
            val relativePath = "chapters/${index + 1}.html"
            val document = wrapHtml(chapterTitle, section.html.toString())
            val chapterBytes = document.toByteArray(StandardCharsets.UTF_8)
            files[relativePath] = chapterBytes
            RawChapter(
                id = stableId(titleId, "fb2:${section.sequence}:$chapterTitle"),
                title = chapterTitle,
                number = (index + 1).toString(),
                volumeName = volumeName.take(160),
                relativePath = relativePath,
                contentHash = sha256(chapterBytes),
                wordCount = WORD_PATTERN.findAll(plainText(document)).count(),
                sortOrder = index + 1,
            )
        }

        val grouped = linkedMapOf<String, MutableList<RawChapter>>()
        rawChapters.forEach { chapter -> grouped.getOrPut(chapter.volumeName) { mutableListOf() } += chapter }
        val volumes = grouped.entries.mapIndexed { index, (name, chapters) ->
            ParsedVolume(
                id = stableId(titleId, "volume:$name"),
                name = name,
                number = (index + 1).toString(),
                sortOrder = index + 1,
                chapters = chapters,
            )
        }
        val coverRelativePath = coverBinaryId
            ?.takeIf(binaries::containsKey)
            ?.let { "images/${safeResourceId(it)}" }

        return ParsedFb2Book(
            titleId = titleId,
            title = title,
            author = author,
            volumes = volumes,
            files = files,
            coverRelativePath = coverRelativePath,
            filesSkipped = filesSkipped,
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
        }.associateBy(StoredImportChapter::id)
    }

    private fun RawChapter.toDiffChapter(volumeId: String): PlannedImportChapter =
        PlannedImportChapter(
            id = id,
            volumeId = volumeId,
            name = title,
            number = number,
            sortOrder = sortOrder,
            contentHash = contentHash,
        )

    private fun openLimited(uri: Uri, limit: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть FB2")
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
            if (total > limit) throw ImportException("FB2 превышает безопасный размер")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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

    private fun hrefAttribute(parser: XmlPullParser): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).equals("href", ignoreCase = true)) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun safeChild(root: File, relativePath: String): File {
        val child = File(root, relativePath.replace('\\', '/').trimStart('/'))
        val rootPath = root.canonicalPath + File.separator
        if (!child.canonicalPath.startsWith(rootPath)) throw ImportException("FB2 содержит небезопасный путь")
        return child
    }

    private fun safeResourceId(value: String): String = value
        .removePrefix("#")
        .replace(Regex("""[^\p{L}\p{N}._-]+"""), "_")
        .trim('_')
        .ifBlank { sha256(value.toByteArray()).take(16) }

    private fun stableId(namespace: String, value: String): String =
        "$namespace-${sha256(value.toByteArray(StandardCharsets.UTF_8)).take(24)}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun wrapHtml(title: String, body: String): String = """
        <!doctype html>
        <html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline';">
        <title>${escapeHtml(title)}</title>
        <style>body{margin:0 auto;padding:20px;box-sizing:border-box}img{max-width:100%;height:auto}figure{text-align:center;margin:1em 0}p{overflow-wrap:anywhere}</style>
        </head><body>$body</body></html>
    """.trimIndent()

    private fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().normalizeText()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeHtmlAttribute(value: String): String = escapeHtml(value)
        .replace("\"", "&quot;")

    private fun String.normalizeText(): String =
        replace(Regex("""\s+"""), " ").trim()

    private data class ParsedFb2Book(
        val titleId: String,
        val title: String,
        val author: String,
        val volumes: List<ParsedVolume>,
        val files: Map<String, ByteArray>,
        val coverRelativePath: String?,
        val filesSkipped: Int,
    )

    private data class ParsedVolume(
        val id: String,
        val name: String,
        val number: String?,
        val sortOrder: Int,
        val chapters: List<RawChapter>,
    )

    private data class RawChapter(
        val id: String,
        val title: String,
        val number: String?,
        val volumeName: String,
        val relativePath: String,
        val contentHash: String,
        val wordCount: Int,
        val sortOrder: Int,
    )

    private data class SectionBuilder(
        val sequence: Int,
        val rootSequence: Int,
        val bodyName: String,
        val title: StringBuilder = StringBuilder(),
        val html: StringBuilder = StringBuilder(),
        var directContent: Boolean = false,
        var hasChildren: Boolean = false,
    ) {
        fun appendOpen(tag: String) {
            html.append('<').append(tag).append('>')
        }

        fun appendClose(tag: String) {
            html.append("</").append(tag).append('>')
        }
    }

    private data class BinaryBuilder(
        val id: String,
        val contentType: String,
        val base64: StringBuilder = StringBuilder(),
    )

    private data class DecodedBinary(
        val id: String,
        val contentType: String,
        val bytes: ByteArray,
    )

    private enum class MetadataCapture {
        BOOK_TITLE,
        AUTHOR_PART,
    }

    private companion object {
        const val FORMAT = "FB2"
        const val MAX_FB2_BYTES = 96L * 1024L * 1024L
        const val MAX_BINARY_BYTES = 24 * 1024 * 1024
        const val MAX_BASE64_CHARS = 40 * 1024 * 1024
        val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
    }
}
