package com.dollarreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollarreader.app.data.importer.ImportChapterChange
import com.dollarreader.app.data.importer.ImportPreview
import com.dollarreader.app.data.importer.ImportResult
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.BookChapterContents
import com.dollarreader.app.model.BookContents
import com.dollarreader.app.ui.components.BookCover
import kotlinx.coroutines.launch

@Composable
fun BookDetailsScreen(
    book: Book,
    contents: BookContents?,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onPreviewUpdate: (suspend () -> ImportPreview)? = null,
    onApplyUpdate: (suspend () -> ImportResult)? = null,
) {
    val scope = rememberCoroutineScope()
    var isCheckingUpdates by remember(book.id) { mutableStateOf(false) }
    var updatePreview by remember(book.id) { mutableStateOf<ImportPreview?>(null) }
    var updateNotice by remember(book.id) { mutableStateOf<UpdateNotice?>(null) }

    fun checkUpdates() {
        val previewAction = onPreviewUpdate ?: return
        scope.launch {
            isCheckingUpdates = true
            updateNotice = null
            runCatching { previewAction() }
                .onSuccess { preview -> updatePreview = preview }
                .onFailure { error ->
                    updateNotice = UpdateNotice(
                        message = error.message
                            ?: "Не удалось проверить исходный файл или папку",
                        isError = true,
                    )
                }
            isCheckingUpdates = false
        }
    }

    fun applyUpdate(preview: ImportPreview) {
        if (!preview.changes.hasChanges) {
            updatePreview = null
            updateNotice = UpdateNotice(
                message = "${preview.title}: библиотека уже актуальна",
                isError = false,
            )
            return
        }
        val applyAction = onApplyUpdate ?: return
        scope.launch {
            isCheckingUpdates = true
            runCatching { applyAction() }
                .onSuccess { result ->
                    updatePreview = null
                    updateNotice = UpdateNotice(
                        message = result.updateSuccessMessage(),
                        isError = false,
                    )
                }
                .onFailure { error ->
                    updateNotice = UpdateNotice(
                        message = error.message ?: "Не удалось обновить тайтл",
                        isError = true,
                    )
                }
            isCheckingUpdates = false
        }
    }

    updatePreview?.let { preview ->
        TitleUpdateDialog(
            preview = preview,
            isWorking = isCheckingUpdates,
            onDismiss = { if (!isCheckingUpdates) updatePreview = null },
            onConfirm = { applyUpdate(preview) },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, "Назад")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { }) {
                    Icon(Icons.Outlined.BookmarkBorder, "В избранное")
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Outlined.MoreHoriz, "Ещё")
                }
            }
        }
        item {
            BookCover(
                title = book.title,
                seed = book.accentSeed,
                modifier = Modifier
                    .size(width = 210.dp, height = 300.dp)
                    .fillMaxWidth(),
            )
        }
        item {
            Column {
                Text(
                    text = book.format,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(text = book.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Прогресс чтения",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(book.progress * 100).toInt()}%",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Глава ${book.currentChapter} из ${book.totalChapters}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Column {
                MetadataRow("Формат", book.format)
                MetadataRow("Глав", book.totalChapters.toString())
                MetadataRow("Текущая глава", book.currentChapter.toString())
            }
        }
        item {
            Button(
                onClick = onRead,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Rounded.AutoStories, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Продолжить чтение",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (onPreviewUpdate != null && onApplyUpdate != null) {
            item {
                OutlinedButton(
                    onClick = { checkUpdates() },
                    enabled = !isCheckingUpdates,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (isCheckingUpdates) {
                            "Проверяем источник…"
                        } else {
                            "Проверить обновления"
                        },
                    )
                }
            }
        }

        updateNotice?.let { notice ->
            item {
                UpdateNoticeCard(
                    notice = notice,
                    onDismiss = { updateNotice = null },
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Оглавление", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${book.totalChapters} глав",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (contents == null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            contents.volumes.forEach { volume ->
                item(key = "volume-${volume.id}") {
                    Text(
                        text = volume.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(volume.chapters, key = { it.id }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        isCurrent = chapter.sortOrder == book.currentChapter,
                        onClick = { onChapterClick(chapter.sortOrder) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleUpdateDialog(
    preview: ImportPreview,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val changedChapters = preview.volumes.flatMap { volume ->
        volume.chapters
            .filter { it.change != ImportChapterChange.UNCHANGED }
            .map { chapter -> volume.name to chapter }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Проверка обновлений")
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
                    "${preview.format} · глав в источнике: ${preview.totalChapters}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateSummaryCard(preview)
                Text(
                    if (preview.changes.hasChanges) {
                        "Прогресс чтения и статусы сохранившихся глав будут сохранены."
                    } else {
                        "Сохранённая библиотека совпадает с выбранным источником."
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (changedChapters.isNotEmpty()) {
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(
                            items = changedChapters,
                            key = { (volumeName, chapter) ->
                                "$volumeName-${chapter.sourcePath}"
                            },
                        ) { (volumeName, chapter) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        chapter.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        volumeName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    chapter.change.updateLabel(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chapter.change.updateColor(),
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isWorking,
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (preview.changes.hasChanges) {
                        "Обновить"
                    } else {
                        "Готово"
                    },
                )
            }
        },
        dismissButton = {
            if (preview.changes.hasChanges) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                ) {
                    Text("Отмена")
                }
            }
        },
    )
}

@Composable
private fun UpdateSummaryCard(preview: ImportPreview) {
    Card(
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
private fun UpdateNoticeCard(
    notice: UpdateNotice,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun ImportChapterChange.updateColor(): Color = when (this) {
    ImportChapterChange.ADDED -> MaterialTheme.colorScheme.primary
    ImportChapterChange.CHANGED -> MaterialTheme.colorScheme.tertiary
    ImportChapterChange.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun ImportChapterChange.updateLabel(): String = when (this) {
    ImportChapterChange.ADDED -> "Новая"
    ImportChapterChange.CHANGED -> "Изменена"
    ImportChapterChange.UNCHANGED -> "Без изменений"
}

private fun ImportResult.updateSuccessMessage(): String {
    if (!changes.hasChanges) return "$title: изменений не найдено"
    return "$title: +${changes.added}, изменено ${changes.changed}, удалено ${changes.removed}"
}

@Composable
private fun ChapterRow(
    chapter: BookChapterContents,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    isCurrent -> Icons.Outlined.PlayArrow
                    chapter.isRead -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    chapter.isRead -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                )
                val status = when {
                    isCurrent -> "Текущая глава"
                    chapter.isRead -> "Прочитано"
                    chapter.progress > 0f -> "Прочитано ${(chapter.progress * 100).toInt()}%"
                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            chapter.number?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, modifier = Modifier.weight(1.2f))
    }
}

private data class UpdateNotice(
    val message: String,
    val isError: Boolean,
)
