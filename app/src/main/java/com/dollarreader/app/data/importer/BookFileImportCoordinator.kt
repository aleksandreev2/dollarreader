package com.dollarreader.app.data.importer

import android.net.Uri

class BookFileImportCoordinator(
    private val localBookService: LocalBookService,
    private val structuredBookService: StructuredBookService,
    private val fb2BookService: Fb2BookService,
) {
    suspend fun previewBook(uri: Uri): ImportPreview = when {
        fb2BookService.supports(uri) -> fb2BookService.previewBook(uri)
        structuredBookService.supports(uri) -> structuredBookService.previewBook(uri)
        else -> localBookService.previewBook(uri)
    }

    suspend fun importBook(uri: Uri): ImportResult = when {
        fb2BookService.supports(uri) -> fb2BookService.importBook(uri)
        structuredBookService.supports(uri) -> structuredBookService.importBook(uri)
        else -> localBookService.importBook(uri)
    }
}
