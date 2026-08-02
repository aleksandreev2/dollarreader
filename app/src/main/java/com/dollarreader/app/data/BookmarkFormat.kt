package com.dollarreader.app.data

const val BOOKMARK_NOTE_PREFIX = "[[DOLLARREADER_BOOKMARK]]"

fun encodeBookmarkLabel(label: String?): String =
    BOOKMARK_NOTE_PREFIX + label.orEmpty().trim()

fun decodeBookmarkLabel(noteText: String?): String? = noteText
    ?.takeIf { it.startsWith(BOOKMARK_NOTE_PREFIX) }
    ?.removePrefix(BOOKMARK_NOTE_PREFIX)
    ?.trim()
    ?.ifEmpty { null }

fun isBookmarkNote(noteText: String?): Boolean =
    noteText?.startsWith(BOOKMARK_NOTE_PREFIX) == true
