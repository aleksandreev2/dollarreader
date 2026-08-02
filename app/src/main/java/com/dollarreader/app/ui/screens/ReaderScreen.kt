package com.dollarreader.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.ChapterReadingPosition
import com.dollarreader.app.model.ReaderChapterContent
import com.dollarreader.app.model.ReaderColorTheme
import com.dollarreader.app.model.ReaderFontOption
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.model.ReaderTextSelection
import com.dollarreader.app.model.ReadingAnnotation
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.ui.components.ReaderSettingsControls
import com.dollarreader.app.ui.reader.VolumeChapterDirection
import com.dollarreader.app.ui.reader.VolumeKeyChapterNavigator
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
    annotations: List<ReadingAnnotation>,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    onPositionChange: (ChapterReadingPosition) -> Unit,
    onPreviousChapter: (ChapterReadingPosition?) -> Unit,
    onNextChapter: (ChapterReadingPosition?) -> Unit,
    onAddHighlight: (ReaderTextSelection) -> Unit,
    onAddNote: (ReaderTextSelection, String) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
) {
    val context = LocalContext.current
    val chapterTitle = chapter?.title ?: "Глава ${book.currentChapter}"
    val paragraphs = remember(chapter?.id, chapter?.text) {
        chapter?.text?.toReaderParagraphs(chapterTitle).orEmpty().ifEmpty { previewParagraphs }
    }
    val annotationsByParagraph = remember(annotations) {
        annotations.groupBy { it.paragraphIndex }
    }
    val listState = rememberLazyListState()
    val palette = readerPalette(preferences.colorTheme)
    val fontFamily = preferences.font.toFontFamily()
    var showSettings by remember { mutableStateOf(false) }
    var showAnnotations by remember(chapter?.id) { mutableStateOf(false) }
    var controlsVisible by remember(chapter?.id) { mutableStateOf(true) }
    var restoredChapterId by remember { mutableStateOf<String?>(null) }
    var navigationRequestedForChapter by remember { mutableStateOf<String?>(null) }
    var hasUserScrolled by remember(chapter?.id) { mutableStateOf(false) }
    var selectedText by remember(chapter?.id) {
        mutableStateOf<ReaderTextSelection?>(null)
    }
    var pendingNoteSelection by remember(chapter?.id) {
        mutableStateOf<ReaderTextSelection?>(null)
    }
    var noteDraft by remember(chapter?.id) { mutableStateOf("") }
    var chapterProgress by remember(chapter?.id, initialPosition?.progress) {
        mutableFloatStateOf(initialPosition?.progress ?: 0f)
    }
    var previousScrollPoint by remember(chapter?.id) {
        mutableStateOf(ReaderScrollPoint(0, 0))
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
        if (showSettings || showAnnotations || pendingNoteSelection != null) return
        val currentChapter = chapter ?: return
        if (navigationRequestedForChapter == currentChapter.id) return
        if (forward && !canGoNext) return
        if (!forward && !canGoPrevious) return

        navigationRequestedForChapter = currentChapter.id
        selectedText = null
        val position = currentPosition()
        if (forward) {
            onNextChapter(position)
        } else {
            onPreviousChapter(position)
        }
    }

    val latestVolumeNavigation by rememberUpdatedState<(VolumeChapterDirection) -> Unit> { direction ->
        requestChapterNavigation(direction == VolumeChapterDirection.NEXT)
    }
    DisposableEffect(Unit) {
        val unregister = VolumeKeyChapterNavigator.register { direction ->
            latestVolumeNavigation(direction)
        }
        onDispose { unregister() }
    }

    LaunchedEffect(chapter?.id) {
        navigationRequestedForChapter = null
        hasUserScrolled = false
        selectedText = null
        pendingNoteSelection = null
        showAnnotations = false
        controlsVisible = true
        previousScrollPoint = ReaderScrollPoint(0, 0)
    }

    LaunchedEffect(preferences.keepControlsVisible) {
        if (preferences.keepControlsVisible) controlsVisible = true
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
            previousScrollPoint = ReaderScrollPoint(
                position.firstVisibleItemIndex.coerceAtLeast(0),
                position.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
        restoredChapterId = currentChapter.id
    }

    LaunchedEffect(
        chapter?.id,
        restoredChapterId,
        preferences.showChapterTitle,
        preferences.keepControlsVisible,
    ) {
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
            if (snapshot.isScrolling) {
                hasUserScrolled = true
                if (!preferences.keepControlsVisible) {
                    val current = ReaderScrollPoint(snapshot.itemIndex, snapshot.scrollOffset)
                    when {
                        current.isAfter(previousScrollPoint) -> controlsVisible = false
                        current.isBefore(previousScrollPoint) -> controlsVisible = true
                    }
                    if (snapshot.itemIndex == 0 && snapshot.scrollOffset <= 2) {
                        controlsVisible = true
                    }
                    previousScrollPoint = current
                }
            } else {
                previousScrollPoint = ReaderScrollPoint(snapshot.itemIndex, snapshot.scrollOffset)
            }

            val position = currentPosition() ?: return@collectLatest
            chapterProgress = position.progress
            delay(POSITION_SAVE_DELAY_MS)
            onPositionChange(position)
        }
    }

    pendingNoteSelection?.let { selection ->
        AlertDialog(
            onDismissRequest = {
                pendingNoteSelection = null
                noteDraft = ""
            },
            title = { Text("Новая заметка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        selection.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Текст заметки") },
                        minLines = 3,
                        maxLines = 7,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddNote(selection, noteDraft.trim())
                        pendingNoteSelection = null
                        selectedText = null
                        noteDraft = ""
                        Toast.makeText(context, "Заметка сохранена", Toast.LENGTH_SHORT).show()
                    },
                    enabled = noteDraft.isNotBlank(),
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingNoteSelection = null
                        noteDraft = ""
                    },
                ) {
                    Text("Отмена")
                }
            },
        )
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

    if (showAnnotations) {
        ModalBottomSheet(
            onDismissRequest = { showAnnotations = false },
            containerColor = palette.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
            ) {
                Text(
                    "Заметки и выделения",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.text,
                )
                Text(
                    chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondaryText,
                    modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
                )
                if (annotations.isEmpty()) {
                    Text(
                        "В этой главе пока ничего не сохранено.",
                        color = palette.secondaryText,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(annotations, key = { it.id }) { annotation ->
                            AnnotationCard(
                                annotation = annotation,
                                palette = palette,
                                onDelete = { onDeleteAnnotation(annotation.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    val chromeVisible = preferences.keepControlsVisible || controlsVisible || selectedText != null

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background,
        contentColor = palette.text,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                ReaderTopBar(
                    book = book,
                    chapterTitle = chapterTitle,
                    annotationsCount = annotations.size,
                    palette = palette,
                    onBack = {
                        currentPosition()?.let(onPositionChange)
                        onBack()
                    },
                    onAnnotations = { showAnnotations = true },
                    onSettings = { showSettings = true },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                    ) { index, paragraph ->
                        SelectableParagraph(
                            paragraph = paragraph,
                            paragraphIndex = index,
                            annotations = annotationsByParagraph[index].orEmpty(),
                            activeSelection = selectedText,
                            fontFamily = fontFamily,
                            preferences = preferences,
                            palette = palette,
                            onSelectionChange = { selectedText = it },
                        )
                    }
                    item(key = "bottom-spacer") {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            selectedText?.let { selection ->
                SelectionActionBar(
                    selection = selection,
                    palette = palette,
                    onCopy = {
                        copyText(context, selection.text)
                        selectedText = null
                    },
                    onHighlight = {
                        onAddHighlight(selection)
                        selectedText = null
                        Toast.makeText(context, "Текст подсвечен", Toast.LENGTH_SHORT).show()
                    },
                    onNote = {
                        noteDraft = ""
                        pendingNoteSelection = selection
                    },
                    onDictionary = {
                        openExternalUrl(context, dictionaryUrl(selection.text))
                    },
                    onSearch = {
                        openExternalUrl(context, searchUrl(selection.text))
                    },
                    onTranslate = {
                        openExternalUrl(context, translateUrl(selection.text))
                    },
                    onClose = { selectedText = null },
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                ReaderBottomBar(
                    currentChapter = book.currentChapter,
                    totalChapters = book.totalChapters,
                    chapterProgress = chapterProgress,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    navigationLocked = navigationRequestedForChapter == chapter?.id,
                    palette = palette,
                    onPrevious = { requestChapterNavigation(forward = false) },
                    onNext = { requestChapterNavigation(forward = true) },
                    onSettings = { showSettings = true },
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    book: Book,
    chapterTitle: String,
    annotationsCount: Int,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onAnnotations: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
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
        IconButton(onClick = onAnnotations) {
            Icon(
                Icons.Outlined.BookmarkBorder,
                contentDescription = "Заметки и выделения: $annotationsCount",
                tint = if (annotationsCount == 0) palette.text else palette.accent,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Outlined.TextFields,
                contentDescription = "Настройки чтения",
                tint = palette.accent,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    currentChapter: Int,
    totalChapters: Int,
    chapterProgress: Float,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    navigationLocked: Boolean,
    palette: ReaderPalette,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
) {
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
                    onClick = onPrevious,
                    enabled = canGoPrevious && !navigationLocked,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = "Предыдущая глава",
                        tint = if (canGoPrevious) palette.accent else palette.secondaryText,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$currentChapter из $totalChapters",
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
                    onClick = onNext,
                    enabled = canGoNext && !navigationLocked,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "Следующая глава",
                        tint = if (canGoNext) palette.accent else palette.secondaryText,
                    )
                }
                IconButton(
                    onClick = onSettings,
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

@Composable
private fun SelectableParagraph(
    paragraph: String,
    paragraphIndex: Int,
    annotations: List<ReadingAnnotation>,
    activeSelection: ReaderTextSelection?,
    fontFamily: FontFamily,
    preferences: ReaderPreferences,
    palette: ReaderPalette,
    onSelectionChange: (ReaderTextSelection?) -> Unit,
) {
    val annotatedText = remember(paragraph, annotations, palette.highlight, palette.noteHighlight) {
        paragraph.withAnnotations(annotations, palette)
    }
    val selectedRange = activeSelection
        ?.takeIf { it.paragraphIndex == paragraphIndex }
        ?.let { selection ->
            TextRange(
                start = selection.startOffset.coerceIn(0, paragraph.length),
                end = selection.endOffset.coerceIn(0, paragraph.length),
            )
        }
        ?: TextRange.Zero
    val value = TextFieldValue(
        annotatedString = annotatedText,
        selection = selectedRange,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = { updated ->
                val start = minOf(updated.selection.start, updated.selection.end)
                    .coerceIn(0, paragraph.length)
                val end = maxOf(updated.selection.start, updated.selection.end)
                    .coerceIn(0, paragraph.length)
                if (end > start) {
                    onSelectionChange(
                        ReaderTextSelection(
                            paragraphIndex = paragraphIndex,
                            startOffset = start,
                            endOffset = end,
                            text = paragraph.substring(start, end),
                        ),
                    )
                } else if (activeSelection?.paragraphIndex == paragraphIndex) {
                    onSelectionChange(null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            textStyle = TextStyle(
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
            cursorBrush = SolidColor(palette.accent),
        )
        val noteCount = annotations.count { it.type == ReadingAnnotationType.NOTE }
        if (noteCount > 0) {
            Text(
                text = if (noteCount == 1) "Заметка" else "Заметок: $noteCount",
                style = MaterialTheme.typography.labelSmall,
                color = palette.accent,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun SelectionActionBar(
    selection: ReaderTextSelection,
    palette: ReaderPalette,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onDictionary: () -> Unit,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                text = selection.text.replace('\n', ' '),
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCopy) { Text("Копировать", color = palette.accent) }
                TextButton(onClick = onHighlight) { Text("Подсветить", color = palette.accent) }
                TextButton(onClick = onNote) { Text("Заметка", color = palette.accent) }
                TextButton(onClick = onDictionary) { Text("Словарь", color = palette.accent) }
                TextButton(onClick = onSearch) { Text("Google", color = palette.accent) }
                TextButton(onClick = onTranslate) { Text("Перевести", color = palette.accent) }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Снять выделение",
                        tint = palette.secondaryText,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnotationCard(
    annotation: ReadingAnnotation,
    palette: ReaderPalette,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (annotation.type == ReadingAnnotationType.NOTE) {
                        "Заметка · абзац ${annotation.paragraphIndex + 1}"
                    } else {
                        "Выделение · абзац ${annotation.paragraphIndex + 1}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                )
                Text(
                    annotation.selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    modifier = Modifier.padding(top = 4.dp),
                )
                annotation.noteText?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondaryText,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Удалить",
                    tint = palette.secondaryText,
                )
            }
        }
    }
}

private fun String.withAnnotations(
    annotations: List<ReadingAnnotation>,
    palette: ReaderPalette,
): AnnotatedString = buildAnnotatedString {
    append(this@withAnnotations)
    annotations.forEach { annotation ->
        val start = annotation.startOffset.coerceIn(0, length)
        val end = annotation.endOffset.coerceIn(0, length)
        if (end > start && substring(start, end) == annotation.selectedText) {
            val style = when (annotation.type) {
                ReadingAnnotationType.HIGHLIGHT -> SpanStyle(background = palette.highlight)
                ReadingAnnotationType.NOTE -> SpanStyle(
                    background = palette.noteHighlight,
                    textDecoration = TextDecoration.Underline,
                )
            }
            addStyle(style, start, end)
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("DollarReader", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(
                context,
                "Не найдено приложение для открытия ссылки",
                Toast.LENGTH_SHORT,
            ).show()
        }
}

private fun dictionaryUrl(text: String): String =
    "https://www.google.com/search?q=${Uri.encode("define:$text")}" 

private fun searchUrl(text: String): String =
    "https://www.google.com/search?q=${Uri.encode(text)}"

private fun translateUrl(text: String): String =
    "https://translate.google.com/?sl=auto&tl=ru&text=${Uri.encode(text)}&op=translate"

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
        highlight = Color(0x66FFD54F),
        noteHighlight = Color(0x557E57C2),
    )
    ReaderColorTheme.PAPER -> ReaderPalette(
        background = Color(0xFFFFFBF4),
        surface = Color(0xFFF6EFE5),
        text = Color(0xFF292521),
        secondaryText = Color(0xFF6D6258),
        accent = Color(0xFF76558F),
        track = Color(0xFFE3D8CC),
        highlight = Color(0x88FFD54F),
        noteHighlight = Color(0x667E57C2),
    )
    ReaderColorTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF1E6CC),
        surface = Color(0xFFE8D9B9),
        text = Color(0xFF3A3027),
        secondaryText = Color(0xFF71614F),
        accent = Color(0xFF7A536E),
        track = Color(0xFFD7C5A2),
        highlight = Color(0x88E3B341),
        noteHighlight = Color(0x667A536E),
    )
    ReaderColorTheme.NIGHT -> ReaderPalette(
        background = Color(0xFF17181D),
        surface = Color(0xFF23242B),
        text = Color(0xFFE8E5EB),
        secondaryText = Color(0xFFB9B5C0),
        accent = Color(0xFFCEAAFF),
        track = Color(0xFF393A43),
        highlight = Color(0x665D9CEC),
        noteHighlight = Color(0x667F5AAF),
    )
    ReaderColorTheme.BLACK -> ReaderPalette(
        background = Color.Black,
        surface = Color(0xFF111111),
        text = Color(0xFFF1F1F1),
        secondaryText = Color(0xFFBDBDBD),
        accent = Color(0xFFD5B7FF),
        track = Color(0xFF2A2A2A),
        highlight = Color(0x666DA8FF),
        noteHighlight = Color(0x667A58A6),
    )
}

private data class ReaderPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val track: Color,
    val highlight: Color,
    val noteHighlight: Color,
)

private data class ReaderScrollSnapshot(
    val itemIndex: Int,
    val scrollOffset: Int,
    val totalItems: Int,
    val isScrolling: Boolean,
)

private data class ReaderScrollPoint(
    val itemIndex: Int,
    val scrollOffset: Int,
) {
    fun isAfter(other: ReaderScrollPoint): Boolean =
        itemIndex > other.itemIndex ||
            (itemIndex == other.itemIndex && scrollOffset > other.scrollOffset + SCROLL_DIRECTION_SLOP_PX)

    fun isBefore(other: ReaderScrollPoint): Boolean =
        itemIndex < other.itemIndex ||
            (itemIndex == other.itemIndex && scrollOffset + SCROLL_DIRECTION_SLOP_PX < other.scrollOffset)
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

private const val POSITION_SAVE_DELAY_MS = 550L
private const val SCROLL_DIRECTION_SLOP_PX = 4
