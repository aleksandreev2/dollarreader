package com.dollarreader.app.data

import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.model.ReaderChapterContent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChapterContentLoader(
    private val database: DollarReaderDatabase,
) {
    private val dao = database.libraryDao()

    suspend fun loadChapter(titleId: String, sortOrder: Int): ReaderChapterContent? =
        withContext(Dispatchers.IO) {
            val chapter = dao.chapterByOrder(titleId, sortOrder) ?: return@withContext null
            val localPath = chapter.localUri
            val text = localPath?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
            }
            ReaderChapterContent(
                id = chapter.id,
                title = chapter.name,
                sortOrder = chapter.sortOrder,
                text = text,
                localPath = localPath,
            )
        }
}
