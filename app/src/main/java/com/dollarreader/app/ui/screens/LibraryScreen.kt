package com.dollarreader.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dollarreader.app.data.importer.ImportChapterChange
import com.dollarreader.app.data.importer.ImportPreview
import com.dollarreader.app.data.importer.ImportResult
import com.dollarreader.app.model.Book
import com.dollarreader.app.ui.components.BookRow
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onPreviewImport: suspend (Uri) -> ImportPreview,
    onImport: suspend (Uri) -> ImportResult,
    onPreviewFolder: suspend (Uri) -> ImportPreview,
    onImportFolder: suspend (Uri) -> ImportResult,
) {
    val scope = rememberCoroutineScope()
    var isWorking by rememberSaveable { mutableStateOf(false) }
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ImportNotice?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    fun startPreview(uri: Uri, source: ImportSource) {
        scope.launch {
            isWorking = true
            notice = null
            runCatching {
                when (source) {
                    ImportSource.File -> onPreviewImport(uri)
                    ImportSource.Folder -> onPreviewFolder(uri)
                }
            }.onSuccess { preview ->
                pendingImport = PendingImport(uri, preview, source)
            }.onFailure { error ->
                notice = ImportNotice(
                    error.message ?: "Не удалось проверить выбранный источник",
                    true,
                )
            }
            isWorking = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) startPreview(uri, ImportSource.File)
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) startPreview(uri, ImportSource.Folder)
    }

    pendingImport?.let { pending ->
        ImportPreviewDialog(
            preview = pending.preview,
            isImporting = isWorking,
            onDismiss = { if (!isWorking) pendingImport = null },
            onConfirm = {
                scope.launch {
                    isWorking = true
                    runCatching {
                        when (pending.source) {
                            ImportSource.File -> onImport(pending.uri)
                            ImportSource.Folder -> onImportFolder(pending.uri)
                        }
                    }.onSuccess { result ->
                        notice = ImportNotice(result.successMessage(), false)
                        pendingImport = null
                    }.onFailure { error ->
                        notice = ImportNotice(
                            error.message ?: "Не удалось импортировать источник",
                            true,
                        )
                    }
                    isWorking = false
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Библиотека", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${books.size} тайтлов и документов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { },
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.FilterList, contentDescription = "Фильтры")
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Button(
                    onClick = { addMenuExpanded = true },
                    enabled = !isWorking,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (isWorking && pendingImport == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Add, contentDescription = "Добавить книгу")
                    }
                }
                DropdownMenu(
                    expanded = addMenuExpanded,
                    onDismissRequest = { addMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Файл TXT, ZIP, EPUB, FB2 или HTML") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                        },
                        onClick = {
                            addMenuExpanded = false
                            filePicker.launch(
                                arrayOf(
                                    "text/plain",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/epub+zip",
                                    "application/x-fictionbook+xml",
                                    "text/html",
                                    "application/xhtml+xml",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Папка с главами") },
                        leadingIcon = {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        },
                        onClick = {
                            addMenuExpanded = false
                            folderPicker.launch(null)
                        },
                    )
                }
            }
        }

        notice?.let { currentNotice ->
            ImportNoticeCard(
                notice = currentNotice,
                onDismiss = { notice = null },
            )
        }

        if (books.isEmpty()) {
            Text(
                text = "Нажмите «Добавить», чтобы выбрать TXT, ZIP, EPUB, FB2, HTML или папку с главами",
                modifier = Modifier.padding(top = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        onClick = { onBookClick(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val detailedDiff = preview.format in DETAILED_IMPORT_FORMATS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    if (preview.updatedExistingTitle) {
                        "Обновление тайтла"
                    } else {
                        "Импорт тайтла"
                    },
                )
                Text(
                    preview.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${preview.format} · томов: ${preview.volumes.size} · глав: ${preview.totalChapters}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (preview.filesSkipped > 0) {
                    Text(
                        "Пропущено файлов и папок: ${preview.filesSkipped}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.updatedExistingTitle && detailedDiff) {
                    ChangeSummaryCard(preview)
                    Text(
                        if (preview.changes.hasChanges) {
                            "Будут записаны только новые или изменённые главы. Прогресс чтения сохранится."
                        } else {
                            "Изменений не найдено. Повторное копирование глав не требуется."
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (preview.updatedExistingTitle) {
                    Text(
                        "Существующий тайтл будет обновлён. Прогресс и прочитанные главы сохранятся.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    preview.volumes.forEach { volume ->
                        item(key = "volume-${volume.name}") {
                            Text(
                                text = "${volume.name} · ${volume.chapters.size}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(
                            items = volume.chapters,
                            key = { "${volume.name}-${it.sourcePath}" },
                        ) { chapter ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = chapter.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (detailedDiff && preview.updatedExistingTitle) {
                                    Text(
                                        text = chapter.change.label(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = chapter.change.statusColor(),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        preview.updatedExistingTitle &&
                            detailedDiff &&
                            !preview.changes.hasChanges -> "Готово"
                        preview.updatedExistingTitle -> "Обновить"
                        else -> "Импортировать"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting,
            ) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun ChangeSummaryCard(preview: ImportPreview) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("Изменения", style = MaterialTheme.typography.titleSmall)
            Text(
                "Добавлено: ${preview.changes.added} · изменено: ${preview.changes.changed}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Удалено: ${preview.changes.removed} · без изменений: ${preview.changes.unchanged}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportChapterChange.statusColor(): Color = when (this) {
    ImportChapterChange.ADDED -> MaterialTheme.colorScheme.primary
    ImportChapterChange.CHANGED -> MaterialTheme.colorScheme.tertiary
    ImportChapterChange.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun ImportChapterChange.label(): String = when (this) {
    ImportChapterChange.ADDED -> "Новая"
    ImportChapterChange.CHANGED -> "Изменена"
    ImportChapterChange.UNCHANGED -> "Без изменений"
}

private fun ImportResult.successMessage(): String {
    val hasDetailedDiff = format in DETAILED_IMPORT_FORMATS
    if (!hasDetailedDiff) {
        val action = if (updatedExistingTitle) "обновлён" else "добавлен"
        return "$title: $action, глав — $chaptersImported"
    }
    if (!changes.hasChanges) {
        return "$title: изменений нет, глав — $chaptersImported"
    }
    val action = if (updatedExistingTitle) "обновлён" else "добавлен"
    return "$title: $action · +${changes.added}, изменено ${changes.changed}, удалено ${changes.removed}"
}

@Composable
private fun ImportNoticeCard(
    notice: ImportNotice,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notice.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (notice.isError) {
                    Icons.Outlined.ErrorOutline
                } else {
                    Icons.Outlined.CheckCircle
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                notice.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Закрыть сообщение")
            }
        }
    }
}

private enum class ImportSource {
    File,
    Folder,
}

private data class PendingImport(
    val uri: Uri,
    val preview: ImportPreview,
    val source: ImportSource,
)

private data class ImportNotice(
    val message: String,
    val isError: Boolean,
)

private val DETAILED_IMPORT_FORMATS = setOf(
    "ПАПКА/TXT",
    "ZIP/TXT",
    "EPUB",
    "FB2",
    "HTML",
)
