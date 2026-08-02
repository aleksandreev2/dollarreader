package com.dollarreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.dollarreader.app.model.TitleHistoryItem
import com.dollarreader.app.model.TitleManagement
import com.dollarreader.app.ui.components.BookCover
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun BookDetailsScreen(
    book: Book,
    contents: BookContents?,
    management: TitleManagement?,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onSaveMetadata: suspend (String, String, String?) -> Unit,
    onToggleFavorite: suspend (Boolean) -> Unit,
    onDelete: suspend () -> Unit,
    onPreviewUpdate: (suspend () -> ImportPreview)? = null,
    onApplyUpdate: (suspend () -> ImportResult)? = null,
) {
    val scope = rememberCoroutineScope()
    var moreMenuExpanded by remember(book.id) { mutableStateOf(false) }
    var showEditDialog by remember(book.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(book.id) { mutableStateOf(false) }
    var isManaging by remember(book.id) { mutableStateOf(false) }
    var isCheckingUpdates by remember(book.id) { mutableStateOf(false) }
    var updatePreview by remember(book.id) { mutableStateOf<ImportPreview?>(null) }
    var notice by remember(book.id) { mutableStateOf<ScreenNotice?>(null) }

    fun checkUpdates() {
        val previewAction = onPreviewUpdate ?: return
        scope.launch {
            isCheckingUpdates = true
            notice = null
            runCatching { previewAction() }
                .onSuccess { preview -> updatePreview = preview }
                .onFailure { error ->
                    notice = ScreenNotice(
                        error.message ?: "Не удалось проверить исходный файл или папку",
                        true,
                    )
                }
            isCheckingUpdates = false
        }
    }

    fun applyUpdate(preview: ImportPreview) {
        if (!preview.changes.hasChanges) {
            updatePreview = null
            notice = ScreenNotice("${preview.title}: библиотека уже актуальна", false)
            return
        }
        val applyAction = onApplyUpdate ?: return
        scope.launch {
            isCheckingUpdates = true
            runCatching { applyAction() }
                .onSuccess { result ->
                    updatePreview = null
                    notice = ScreenNotice(result.updateSuccessMessage(), false)
                }
                .onFailure { error ->
                    notice = ScreenNotice(error.message ?: "Не удалось обновить тайтл", true)
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

    if (showEditDialog && management != null) {
        MetadataEditDialog(
            management = management,
            isSaving = isManaging,
            onDismiss = { if (!isManaging) showEditDialog = false },
            onSave = { title, author, description ->
                scope.launch {
                    isManaging = true
                    runCatching { onSaveMetadata(title, author, description) }
                        .onSuccess {
                            showEditDialog = false
                            notice = ScreenNotice("Данные тайтла сохранены", false)
                        }
                        .onFailure { error ->
                            notice = ScreenNotice(error.message ?: "Не удалось сохранить данные", true)
                        }
                    isManaging = false
                }
            },
        )
    }

    if (showDeleteDialog) {
        DeleteTitleDialog(
            title = book.title,
            isDeleting = isManaging,
            onDismiss = { if (!isManaging) showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    isManaging = true
                    runCatching { onDelete() }
                        .onFailure { error ->
                            notice = ScreenNotice(error.message ?: "Не удалось удалить тайтл", true)
                            isManaging = false
                        }
                }
            },
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
                IconButton(
                    onClick = {
                        val next = !(management?.isFavorite ?: book.isFavorite)
                        scope.launch {
                            runCatching { onToggleFavorite(next) }
                                .onFailure { error ->
                                    notice = ScreenNotice(
                                        error.message ?: "Не удалось изменить избранное",
                                        true,
                                    )
                                }
                        }
                    },
                    enabled = management != null && !isManaging,
                ) {
                    Icon(
                        imageVector = if (management?.isFavorite == true) {
                            Icons.Outlined.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        contentDescription = "Избранное",
                    )
                }
                Box {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        enabled = management != null && !isManaging,
                    ) {
                        Icon(Icons.Outlined.MoreHoriz, "Действия с тайтлом")
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Редактировать данные") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                showEditDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить из библиотеки") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                moreMenuExpanded = false
                                showDeleteDialog = true
                            },
                        )
                    }
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
                management?.description?.takeIf(String::isNotBlank)?.let { description ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Прогресс чтения", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${(book.progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
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
                management?.let {
                    MetadataRow("Источник", sourceLabel(it))
                }
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
                Text("Продолжить чтение", style = MaterialTheme.typography.titleMedium)
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
                        CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(if (isCheckingUpdates) "Проверяем источник…" else "Проверить обновления")
                }
            }
        }

        notice?.let { currentNotice ->
            item {
                NoticeCard(currentNotice, onDismiss = { notice = null })
            }
        }

        if (!management?.history.isNullOrEmpty()) {
            item {
                Text(
                    "Последние изменения",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(
                items = management?.history?.take(6).orEmpty(),
                key = { it.id },
            ) { history ->
                HistoryRow(history)
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
private fun MetadataEditDialog(
    management: TitleManagement,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit,
) {
    var title by remember(management.id, management.updatedAt) { mutableStateOf(management.title) }
    var author by remember(management.id, management.updatedAt) { mutableStateOf(management.author) }
    var description by remember(management.id, management.updatedAt) {
        mutableStateOf(management.description.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать тайтл") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Автор") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Изменение названия не разрывает связь с исходным ZIP или папкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, author, description.takeIf(String::isNotBlank)) },
                enabled = title.isNotBlank() && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Отмена") }
        },
    )
}

@Composable
private fun DeleteTitleDialog(
    title: String,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить из библиотеки?") },
        text = {
            Text(
                "«$title» будет удалён вместе с локальной копией глав, прогрессом и историей. " +
                    "Исходный TXT, ZIP или выбранная папка останутся без изменений.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isDeleting) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text("Отмена") }
        },
    )
}

@Composable
private fun HistoryRow(history: TitleHistoryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = history.details,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = history.eventType.historyLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = buildString {
                    append(formatHistoryTime(history.createdAt))
                    if (history.chapterCount > 0) append(" · глав: ${history.chapterCount}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: BookChapterContents,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val container = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
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
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
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
                )
                if (changedChapters.isNotEmpty()) {
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(
                            items = changedChapters,
                            key = { (volumeName, chapter) -> "$volumeName-${chapter.sourcePath}" },
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
            Button(onClick = onConfirm, enabled = !isWorking) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (preview.changes.hasChanges) "Обновить" else "Готово")
            }
        },
        dismissButton = {
            if (preview.changes.hasChanges) {
                TextButton(onClick = onDismiss, enabled = !isWorking) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun UpdateSummaryCard(preview: ImportPreview) {
    Card(
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
private fun NoticeCard(notice: ScreenNotice, onDismiss: () -> Unit) {
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
                imageVector = if (notice.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text(notice.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
    if (!changes.hasChanges) return "$title: изменений нет"
    return "$title: +${changes.added}, изменено ${changes.changed}, удалено ${changes.removed}"
}

private fun sourceLabel(management: TitleManagement): String = when (management.format) {
    "ZIP/TXT" -> "ZIP-архив"
    "ПАПКА/TXT" -> "Папка с главами"
    "TXT" -> "TXT-файл"
    else -> management.sourceType
}

private fun String.historyLabel(): String = when (this) {
    "IMPORT" -> "Импорт"
    "UPDATE" -> "Обновление"
    "METADATA" -> "Данные"
    else -> this
}

private fun formatHistoryTime(timestamp: Long): String = runCatching {
    HISTORY_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}.getOrDefault("Дата неизвестна")

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.2f))
    }
}

private data class ScreenNotice(val message: String, val isError: Boolean)

private val HISTORY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm")
