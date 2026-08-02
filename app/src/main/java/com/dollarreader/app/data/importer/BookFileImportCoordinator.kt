package com.dollarreader.app.data.importer

import android.net.Uri

class BookFileImportCoordinator(
    private val localBookService: LocalBookService,
    private val structuredBookService: StructuredBookService,
) {
    suspend fun previewBook(uri: Uri): ImportPreview =
        if (structuredBookService.supports(uri)) {
            structuredBookService.previewBook(uri)
        } else {
            localBookService.previewBook(uri)
        }

    suspend fun importBook(uri: Uri): ImportResult =
        if (structuredBookService.supports(uri)) {
            structuredBookService.importBook(uri)
        } else {
            localBookService.importBook(uri)
        }
}
