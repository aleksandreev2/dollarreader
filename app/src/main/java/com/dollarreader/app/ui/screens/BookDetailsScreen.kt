package com.dollarreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.BookChapterContents
import com.dollarreader.app.model.BookContents
import com.dollarreader.app.ui.components.BookCover

@Composable
fun BookDetailsScreen(
    book: Book,
    contents: BookContents?,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onChapterClick: (Int) -> Unit,
) {
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
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { }) { Icon(Icons.Outlined.BookmarkBorder, "В избранное") }
                IconButton(onClick = { }) { Icon(Icons.Outlined.MoreHoriz, "Ещё") }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
private fun ChapterRow(
    chapter: BookChapterContents,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val container = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
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
