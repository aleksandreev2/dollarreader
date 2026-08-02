package com.dollarreader.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalTitleDeletionService(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun deleteTitle(titleId: String): Boolean {
        val deleted = repository.deleteTitle(titleId)
        if (!deleted) return false

        withContext(Dispatchers.IO) {
            val libraryRoot = File(context.filesDir, "library").canonicalFile
            val titleRoot = File(libraryRoot, titleId).canonicalFile
            val safePrefix = libraryRoot.path + File.separator
            if (titleRoot.path.startsWith(safePrefix)) {
                titleRoot.deleteRecursively()
            }
        }
        return true
    }
}
