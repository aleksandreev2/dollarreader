package com.dollarreader.app.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.ChapterReadingPosition
import com.dollarreader.app.model.ReaderChapterContent
import com.dollarreader.app.model.ReaderColorTheme
import com.dollarreader.app.model.ReaderFontOption
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.ui.components.ReaderSettingsControls
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val previewParagraphs = listOf(
    "Сон Чину медленно открыл глаза. Перед ним простирался бесконечный лабиринт, окутанный мраком. Система выдала новое задание.",
    "«Испытание Подземелья началось».",
    "Он крепче сжал рукоять кинжала. Впереди чувствовалось присутствие могущественных монстров.",
    "«Победите стража этажа и пройдите испытание».",
    "Чину глубоко вдохнул и шагнул вперёд. Тишину разорвал его решительный шаг.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    chapter: ReaderChapterContent?,
    preferences: ReaderPreferences,
    initialPosition: ChapterReadingPosition?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    onPositionChange: (ChapterReadingPosition) -> Unit,
    onPreviousChapter: (ChapterReadingPosition?) -> Unit,
    onNextChapter: (ChapterReadingPosition?) -> Unit,
) {
    val chapterTitle = chapter?.title ?: "Глава ${book.currentChapter}"
    val paragraphs = remember(chapter?.id, chapter?.text) {
        chapter?.text?.toReaderParagraphs(chapterTitle).orEmpty().ifEmpty { previewParagraphs }
    }
    val listState = rememberLazyListState()
    val palette = readerPalette(preferences.colorTheme)
    val fontFamily = preferences.font.toFontFamily()
    val swipeThresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    var showSettings by remember { mutableStateOf(false) }
    var restoredChapterId by remember { mutableStateOf<String?>(null) }
    var navigationRequestedForChapter by remember { mutableStateOf<String?>(null) }
    var hasUserScrolled by remember(chapter?.id) { mutableStateOf(false) }
    var chapterProgress by remember(chapter?.id, initialPosition?.progress) {
        mutableFloatStateOf(initialPosition?.progress ?: 0f)
    }

    fun currentPosition(): ChapterReadingPosition? {
        val currentChapter = chapter ?: return null
        val layoutInfo = listState.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val itemIndex = listState.firstVisibleItemIndex.coerceAtLeast(0)
        val scrollOffset = listState.firstVisibleItemScrollOffset.coerceAtLeast(0)
        val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        val itemFraction = if (currentItem == null || currentItem.size <= 0) {
            0f
        } else {
            scrollOffset.toFloat() / currentItem.size.toFloat()
        }
        val reachedEnd = totalItems > 1 &&
            !listState.canScrollForward &&
            (hasUserScrolled || itemIndex > 0 || scrollOffset > 0)
        val progress = when {
            reachedEnd -> 1f
            totalItems <= 1 -> 0f
            else -> (
                (itemIndex + itemFraction) /
                    (totalItems - 1).toFloat()
                ).coerceIn(0f, 1f)
        }
        return ChapterReadingPosition(
            chapterId = currentChapter.id,
            firstVisibleItemIndex = itemIndex,
            firstVisibleItemScrollOffset = scrollOffset,
            progress = progress,
        )
    }

    fun requestChapterNavigation(forward: Boolean) {
        val currentChapter = chapter ?: return
        if (navigationRequestedForChapter == currentChapter.id) return
        if (forward && !canGoNext) return
        if (!forward && !canGoPrevious) return

        navigationRequestedForChapter = currentChapter.id
        val position = currentPosition()
        if (forward) {
            onNextChapter(position)
        } else {
            onPreviousChapter(position)
        }
    }

    LaunchedEffect(chapter?.id) {
        navigationRequestedForChapter = null
        hasUserScrolled = false
    }

    LaunchedEffect(
        chapter?.id,
        initialPosition?.updatedAt,
        preferences.showChapterTitle,
        paragraphs.size,
    ) {
        val currentChapter = chapter ?: return@LaunchedEffect
        val position = initialPosition?.takeIf { it.chapterId == currentChapter.id }
        if (position == null) {
            listState.scrollToItem(0)
            chapterProgress = 0f
        } else {
            val expectedItemCount = paragraphs.size +
                if (preferences.showChapterTitle) 3 else 2
            val lastIndex = (expectedItemCount - 1).coerceAtLeast(0)
            listState.scrollToItem(
                index = position.firstVisibleItemIndex.coerceIn(0, lastIndex),
                scrollOffset = position.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
            chapterProgress = position.progress.coerceIn(0f, 1f)
        }
        restoredChapterId = currentChapter.id
    }

    LaunchedEffect(chapter?.id, restoredChapterId, preferences.showChapterTitle) {
        val currentChapter = chapter ?: return@LaunchedEffect
        if (restoredChapterId != currentChapter.id) return@LaunchedEffect

        snapshotFlow {
            ReaderScrollSnapshot(
                itemIndex = listState.firstVisibleItemIndex,
                scrollOffset = listState.firstVisibleItemScrollOffset,
                totalItems = listState.layoutInfo.totalItemsCount,
                isScrolling = listState.isScrollInProgress,
            )
        }.collectLatest { snapshot ->
            if (snapshot.isScrolling) hasUserScrolled = true
            val position = currentPosition() ?: return@collectLatest
            chapterProgress = position.progress
            delay(POSITION_SAVE_DELAY_MS)
            onPositionChange(position)
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 32.dp),
            ) {
                Text(
                    "Настройки чтения",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                ReaderSettingsControls(
                    preferences = preferences,
                    onPreferencesChange = onPreferencesChange,
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background,
        contentColor = palette.text,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        currentPosition()?.let(onPositionChange)
                        onBack()
                    },
                ) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        tint = palette.text,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.text,
                        maxLines = 1,
                    )
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { }) {
                    Icon(
                        Icons.Outlined.BookmarkBorder,
                        contentDescription = "Закладка",
                        tint = palette.text,
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Outlined.TextFields,
                        contentDescription = "Настройки чтения",
                        tint = palette.accent,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(chapter?.id, canGoPrevious, canGoNext) {
                        var horizontalDistance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDistance = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                horizontalDistance += dragAmount
                            },
                            onDragCancel = { horizontalDistance = 0f },
                            onDragEnd = {
                                when {
                                    horizontalDistance <= -swipeThresholdPx ->
                                        requestChapterNavigation(forward = true)
                                    horizontalDistance >= swipeThresholdPx ->
                                        requestChapterNavigation(forward = false)
                                }
                                horizontalDistance = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = preferences.contentWidthDp.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = preferences.horizontalPaddingDp.dp,
                        end = preferences.horizontalPaddingDp.dp,
                        top = 18.dp,
                        bottom = 36.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        preferences.paragraphSpacingDp.dp,
                    ),
                ) {
                    item(key = "top-spacer") {
                        Spacer(Modifier.height(4.dp))
                    }
                    if (preferences.showChapterTitle) {
                        item(key = "chapter-title") {
                            Text(
                                text = chapterTitle,
                                modifier = Modifier.fillMaxWidth(),
                                style = TextStyle(
                                    fontFamily = fontFamily,
                                    fontSize = (preferences.fontSizeSp + 8f).sp,
                                    lineHeight = (
                                        (preferences.fontSizeSp + 8f) *
                                            preferences.lineHeightMultiplier
                                        ).sp,
                                    textAlign = TextAlign.Center,
                                    color = palette.text,
                                ),
                            )
                        }
                    }
                    itemsIndexed(
                        items = paragraphs,
                        key = { index, _ -> "paragraph-$index" },
                    ) { _, paragraph ->
                        Text(
                            text = paragraph,
                            style = TextStyle(
                                fontFamily = fontFamily,
                                fontSize = preferences.fontSizeSp.sp,
                                lineHeight = (
                                    preferences.fontSizeSp *
                                        preferences.lineHeightMultiplier
                                    ).sp,
                                textIndent = TextIndent(
                                    firstLine = (
                                        preferences.fontSizeSp *
                                            preferences.firstLineIndentEm
                                        ).sp,
                                ),
                                color = palette.text,
                            ),
                        )
                    }
                    item(key = "bottom-spacer") {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { chapterProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.track,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                requestChapterNavigation(forward = false)
                            },
                            enabled = canGoPrevious &&
                                navigationRequestedForChapter != chapter?.id,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ChevronLeft,
                                contentDescription = "Предыдущая глава",
                                tint = if (canGoPrevious) {
                                    palette.accent
                                } else {
                                    palette.secondaryText
                                },
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${book.currentChapter} из ${book.totalChapters}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.text,
                            )
                            Text(
                                "${(chapterProgress * 100).toInt()}% главы",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.secondaryText,
                            )
                        }
                        IconButton(
                            onClick = {
                                requestChapterNavigation(forward = true)
                            },
                            enabled = canGoNext &&
                                navigationRequestedForChapter != chapter?.id,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = "Следующая глава",
                                tint = if (canGoNext) {
                                    palette.accent
                                } else {
                                    palette.secondaryText
                                },
                            )
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.Outlined.TextFields,
                                contentDescription = "Настройки чтения",
                                tint = palette.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ReaderFontOption.toFontFamily(): FontFamily = when (this) {
    ReaderFontOption.DEFAULT -> FontFamily.Default
    ReaderFontOption.SERIF -> FontFamily.Serif
    ReaderFontOption.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontOption.MONOSPACE -> FontFamily.Monospace
}

@Composable
private fun readerPalette(theme: ReaderColorTheme): ReaderPalette = when (theme) {
    ReaderColorTheme.SYSTEM -> ReaderPalette(
        background = MaterialTheme.colorScheme.background,
        surface = MaterialTheme.colorScheme.surface,
        text = MaterialTheme.colorScheme.onBackground,
        secondaryText = MaterialTheme.colorScheme.onSurfaceVariant,
        accent = MaterialTheme.colorScheme.primary,
        track = MaterialTheme.colorScheme.surfaceVariant,
    )
    ReaderColorTheme.PAPER -> ReaderPalette(
        background = Color(0xFFFFFBF4),
        surface = Color(0xFFF6EFE5),
        text = Color(0xFF292521),
        secondaryText = Color(0xFF6D6258),
        accent = Color(0xFF76558F),
        track = Color(0xFFE3D8CC),
    )
    ReaderColorTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF1E6CC),
        surface = Color(0xFFE8D9B9),
        text = Color(0xFF3A3027),
        secondaryText = Color(0xFF71614F),
        accent = Color(0xFF7A536E),
        track = Color(0xFFD7C5A2),
    )
    ReaderColorTheme.NIGHT -> ReaderPalette(
        background = Color(0xFF17181D),
        surface = Color(0xFF23242B),
        text = Color(0xFFE8E5EB),
        secondaryText = Color(0xFFB9B5C0),
        accent = Color(0xFFCEAAFF),
        track = Color(0xFF393A43),
    )
    ReaderColorTheme.BLACK -> ReaderPalette(
        background = Color.Black,
        surface = Color(0xFF111111),
        text = Color(0xFFF1F1F1),
        secondaryText = Color(0xFFBDBDBD),
        accent = Color(0xFFD5B7FF),
        track = Color(0xFF2A2A2A),
    )
}

private data class ReaderPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val track: Color,
)

private data class ReaderScrollSnapshot(
    val itemIndex: Int,
    val scrollOffset: Int,
    val totalItems: Int,
    val isScrolling: Boolean,
)

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

private const val POSITION_SAVE_DELAY_MS = 550L
private const val SWIPE_THRESHOLD_DP = 72
