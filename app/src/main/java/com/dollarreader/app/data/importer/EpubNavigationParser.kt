package com.dollarreader.app.data.importer

import java.io.StringReader
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class EpubNavigationLabel(
    val title: String,
    val volume: String?,
)

internal object EpubNavigationParser {
    fun parse(
        document: String,
        documentPath: String,
        ncx: Boolean,
    ): Map<String, EpubNavigationLabel> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(document))
        }
        val raw = if (ncx) parseNcx(parser, documentPath) else parseNavigationDocument(parser, documentPath)
        if (raw.isEmpty()) return emptyMap()

        val rootLabels = raw.groupBy(RawEntry::rootId).mapValues { (_, entries) ->
            entries.firstNotNullOfOrNull { it.rootTitle.takeIf(String::isNotBlank) }
                ?: entries.first().title
        }
        val hierarchicalRoots = raw.groupBy(RawEntry::rootId)
            .filterValues { entries -> entries.size > 1 || entries.any { it.depth > 1 } }
            .keys

        return raw.associate { entry ->
            entry.path to EpubNavigationLabel(
                title = entry.title,
                volume = rootLabels[entry.rootId]
                    ?.takeIf { entry.rootId in hierarchicalRoots }
                    ?.take(160),
            )
        }
    }

    private fun parseNavigationDocument(
        parser: XmlPullParser,
        documentPath: String,
    ): List<RawEntry> {
        val entries = mutableListOf<RawEntry>()
        val stack = ArrayDeque<Node>()
        var nextRootId = 0
        var captureKind: CaptureKind? = null
        var captureDepth = -1
        var captureText = StringBuilder()
        var captureHref: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "li" -> {
                        val rootId = stack.firstOrNull()?.rootId ?: nextRootId++
                        stack.addLast(Node(rootId = rootId))
                    }
                    "a", "span" -> if (stack.isNotEmpty()) {
                        captureKind = if (parser.name.equals("a", true)) CaptureKind.ANCHOR else CaptureKind.LABEL
                        captureDepth = parser.depth
                        captureText = StringBuilder()
                        captureHref = if (captureKind == CaptureKind.ANCHOR) attribute(parser, "href") else null
                    }
                }
                XmlPullParser.TEXT -> if (captureKind != null) {
                    captureText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when {
                    captureKind != null && parser.depth == captureDepth -> {
                        val text = captureText.toString().normalizeLabel()
                        val node = stack.lastOrNull()
                        if (node != null && text.isNotBlank()) {
                            node.label = text
                            if (captureKind == CaptureKind.ANCHOR && !captureHref.isNullOrBlank()) {
                                val path = resolvePath(documentPath, captureHref.orEmpty())
                                if (path.isNotBlank()) {
                                    entries += RawEntry(
                                        path = path,
                                        title = text,
                                        depth = stack.size,
                                        rootId = node.rootId,
                                        rootTitle = stack.firstOrNull()?.label.orEmpty(),
                                    )
                                }
                            }
                        }
                        captureKind = null
                        captureDepth = -1
                        captureText = StringBuilder()
                        captureHref = null
                    }
                    parser.name.equals("li", true) && stack.isNotEmpty() -> stack.removeLast()
                }
            }
            parser.next()
        }
        return entries.distinctBy(RawEntry::path)
    }

    private fun parseNcx(
        parser: XmlPullParser,
        documentPath: String,
    ): List<RawEntry> {
        val entries = mutableListOf<RawEntry>()
        val stack = ArrayDeque<Node>()
        var nextRootId = 0
        var captureLabelDepth = -1
        var captureText = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "navpoint" -> {
                        val rootId = stack.firstOrNull()?.rootId ?: nextRootId++
                        stack.addLast(Node(rootId = rootId))
                    }
                    "text" -> if (stack.isNotEmpty()) {
                        captureLabelDepth = parser.depth
                        captureText = StringBuilder()
                    }
                    "content" -> if (stack.isNotEmpty()) {
                        stack.last().href = attribute(parser, "src")
                    }
                }
                XmlPullParser.TEXT -> if (captureLabelDepth >= 0) captureText.append(parser.text)
                XmlPullParser.END_TAG -> when {
                    parser.name.equals("text", true) && parser.depth == captureLabelDepth -> {
                        stack.lastOrNull()?.label = captureText.toString().normalizeLabel()
                        captureLabelDepth = -1
                        captureText = StringBuilder()
                    }
                    parser.name.equals("navpoint", true) && stack.isNotEmpty() -> {
                        val node = stack.removeLast()
                        val title = node.label.normalizeLabel()
                        val href = node.href
                        if (title.isNotBlank() && !href.isNullOrBlank()) {
                            val path = resolvePath(documentPath, href)
                            if (path.isNotBlank()) {
                                entries += RawEntry(
                                    path = path,
                                    title = title,
                                    depth = stack.size + 1,
                                    rootId = node.rootId,
                                    rootTitle = if (stack.isEmpty()) title else stack.first().label,
                                )
                            }
                        }
                    }
                }
            }
            parser.next()
        }
        return entries.distinctBy(RawEntry::path)
    }

    private fun attribute(parser: XmlPullParser, localName: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).equals(localName, ignoreCase = true)) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun resolvePath(baseFile: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?').trim()
        if (clean.isBlank()) return ""
        val base = baseFile.substringBeforeLast('/', "")
        return normalizePath(if (clean.startsWith('/')) clean else if (base.isBlank()) clean else "$base/$clean")
    }

    private fun normalizePath(raw: String): String {
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun String?.normalizeLabel(): String = this.orEmpty()
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(180)

    private data class Node(
        val rootId: Int,
        var label: String = "",
        var href: String? = null,
    )

    private data class RawEntry(
        val path: String,
        val title: String,
        val depth: Int,
        val rootId: Int,
        val rootTitle: String,
    )

    private enum class CaptureKind {
        ANCHOR,
        LABEL,
    }
}
