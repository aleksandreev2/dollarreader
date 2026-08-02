package com.dollarreader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.ReaderChapterContent
import java.util.Locale

private val previewParagraphs = listOf(
    "Сон Чину медленно открыл глаза. Перед ним простирался бесконечный лабиринт, окутанный мраком. Система выдала новое задание.",
    "«Испытание Подземелья началось».",
    "Он крепче сжал рукоять кинжала. Впереди чувствовалось присутствие могущественных монстров.",
    "«Победите стража этажа и пройдите испытание».",
    "Чину глубоко вдохнул и шагнул вперёд. Тишину разорвал его решительный шаг.",
)

@Composable
fun ReaderScreen(
    book: Book,
    chapter: ReaderChapterContent?,
    onBack: () -> Unit,
    onProgressChangeFinished: (Float) -> Unit,
) {
    var progress by remember(book.id, book.progress) { mutableFloatStateOf(book.progress) }
    val chapterTitle = chapter?.title ?: "Глава ${book.currentChapter}"
    val paragraphs = remember(chapter?.id, chapter?.text) {
        chapter?.text?.toReaderParagraphs(chapterTitle).orEmpty().ifEmpty { previewParagraphs }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { }) { Icon(Icons.Outlined.BookmarkBorder, "Закладка") }
            IconButton(onClick = { }) { Icon(Icons.Outlined.MoreHoriz, "Ещё") }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { Spacer(Modifier.height(18.dp)) }
            item {
                Text(
                    text = chapterTitle,
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = FontFamily.Serif,
                    fontSize = 27.sp,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                )
            }
            items(paragraphs) { paragraph ->
                Text(
                    text = paragraph,
                    fontFamily = FontFamily.Serif,
                    fontSize = 18.sp,
                    lineHeight = 29.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("A−", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "A+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = progress,
                    onValueChange = { progress = it },
                    onValueChangeFinished = { onProgressChangeFinished(progress) },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${book.currentChapter} из ${book.totalChapters}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(book.format, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun String.toReaderParagraphs(chapterTitle: String): List<String> {
    val paragraphs = trim()
        .split(Regex("""\n[\t ]*\n+"""))
        .map { paragraph -> paragraph.trim() }
        .filter(String::isNotBlank)
        .toMutableList()

    val first = paragraphs.firstOrNull()
    if (first != null && isHeadingDuplicate(first, chapterTitle)) {
        paragraphs.removeAt(0)
    }
    return paragraphs
}

private fun isHeadingDuplicate(firstParagraph: String, chapterTitle: String): Boolean {
    val first = normalizeHeading(firstParagraph.lineSequence().firstOrNull().orEmpty())
    val title = normalizeHeading(chapterTitle)
    return first == title || title.endsWith(first) || first.endsWith(title)
}

private fun normalizeHeading(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
