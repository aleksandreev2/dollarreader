package com.dollarreader.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.ChapterReadingPosition
import com.dollarreader.app.model.ReaderChapterContent
import com.dollarreader.app.model.ReaderColorTheme
import com.dollarreader.app.model.ReaderFontOption
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.ui.components.ReaderSettingsControls
import com.dollarreader.app.ui.reader.VolumeChapterDirection
import com.dollarreader.app.ui.reader.VolumeKeyChapterNavigator
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichReaderScreen(
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
    val palette = richPalette(preferences.colorTheme)
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedChapterId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var controlsVisible by remember(chapter?.id) { mutableStateOf(true) }
    var navigationRequestedForChapter by remember(chapter?.id) { mutableStateOf<String?>(null) }
    var progress by remember(chapter?.id, initialPosition?.progress) {
        mutableFloatStateOf(initialPosition?.progress ?: 0f)
    }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val latestChapter by rememberUpdatedState(chapter)
    val latestPreferences by rememberUpdatedState(preferences)
    val latestPalette by rememberUpdatedState(palette)
    val latestInitialPosition by rememberUpdatedState(initialPosition)
    val latestKeepControlsVisible by rememberUpdatedState(preferences.keepControlsVisible)
    val latestOnPositionChange by rememberUpdatedState(onPositionChange)

    fun currentPosition(): ChapterReadingPosition? {
        val currentChapter = chapter ?: return null
        val view = webView
        val offset = view?.scrollY?.coerceAtLeast(0)
            ?: initialPosition?.firstVisibleItemScrollOffset
            ?: 0
        val computedProgress = view?.let(::webProgress) ?: progress
        return ChapterReadingPosition(
            chapterId = currentChapter.id,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = offset,
            progress = computedProgress.coerceIn(0f, 1f),
        )
    }

    fun navigate(forward: Boolean) {
        if (showSettings) return
        val currentChapter = chapter ?: return
        if (navigationRequestedForChapter == currentChapter.id) return
        if (forward && !canGoNext) return
        if (!forward && !canGoPrevious) return

        navigationRequestedForChapter = currentChapter.id
        val position = currentPosition()
        position?.let(onPositionChange)
        if (forward) {
            onNextChapter(position)
        } else {
            onPreviousChapter(position)
        }
    }

    val latestVolumeNavigation by rememberUpdatedState<(VolumeChapterDirection) -> Unit> { direction ->
        navigate(direction == VolumeChapterDirection.NEXT)
    }
    DisposableEffect(Unit) {
        val unregister = VolumeKeyChapterNavigator.register { direction ->
            latestVolumeNavigation(direction)
        }
        onDispose { unregister() }
    }

    LaunchedEffect(chapter?.id) {
        navigationRequestedForChapter = null
        controlsVisible = true
    }

    LaunchedEffect(preferences.keepControlsVisible) {
        if (preferences.keepControlsVisible) controlsVisible = true
    }

    LaunchedEffect(preferences, chapter?.id, loadedChapterId) {
        val view = webView ?: return@LaunchedEffect
        if (loadedChapterId != chapter?.id) return@LaunchedEffect
        view.evaluateJavascript(buildStyleScript(preferences, palette), null)
    }

    DisposableEffect(Unit) {
        onDispose {
            saveJob?.cancel()
            currentPosition()?.let(onPositionChange)
            webView?.apply {
                stopLoading()
                destroy()
            }
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

    val chromeVisible = preferences.keepControlsVisible || controlsVisible

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
                            chapter?.title ?: "Глава ${book.currentChapter}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryText,
                            maxLines = 1,
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
            }

            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(palette.background.toArgb())
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.blockNetworkLoads = true
                        settings.domStorageEnabled = false
                        settings.setSupportZoom(false)
                        settings.forceDark = WebSettings.FORCE_DARK_OFF
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = true

                            override fun onPageFinished(view: WebView, url: String?) {
                                val currentChapter = latestChapter
                                loadedChapterId = currentChapter?.id
                                view.evaluateJavascript(
                                    buildStyleScript(latestPreferences, latestPalette),
                                    null,
                                )
                                val offset = latestInitialPosition
                                    ?.takeIf { it.chapterId == currentChapter?.id }
                                    ?.firstVisibleItemScrollOffset
                                    ?.coerceAtLeast(0)
                                    ?: 0
                                view.post { view.scrollTo(0, offset) }
                            }
                        }
                        setOnScrollChangeListener { current, _, scrollY, _, oldScrollY ->
                            progress = webProgress(current)
                            if (!latestKeepControlsVisible) {
                                when {
                                    scrollY <= RICH_TOP_REVEAL_PX -> controlsVisible = true
                                    scrollY > oldScrollY + RICH_SCROLL_SLOP_PX -> controlsVisible = false
                                    scrollY + RICH_SCROLL_SLOP_PX < oldScrollY -> controlsVisible = true
                                }
                            }
                            saveJob?.cancel()
                            saveJob = scope.launch {
                                delay(RICH_POSITION_SAVE_DELAY_MS)
                                latestChapter?.let { currentChapter ->
                                    latestOnPositionChange(
                                        ChapterReadingPosition(
                                            chapterId = currentChapter.id,
                                            firstVisibleItemIndex = 0,
                                            firstVisibleItemScrollOffset = scrollY.coerceAtLeast(0),
                                            progress = webProgress(current),
                                        ),
                                    )
                                }
                            }
                        }
                        webView = this
                    }
                },
                update = { view ->
                    view.setBackgroundColor(palette.background.toArgb())
                    val path = chapter?.localPath
                    if (!path.isNullOrBlank() && loadedChapterId != chapter.id) {
                        val file = File(path)
                        if (file.isFile) {
                            view.loadUrl(Uri.fromFile(file).toString())
                        } else {
                            view.loadDataWithBaseURL(
                                null,
                                "<html><body><p>Файл главы недоступен.</p></body></html>",
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    }
                },
            )

            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
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
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.accent,
                            trackColor = palette.track,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                onClick = { navigate(forward = false) },
                                enabled = canGoPrevious &&
                                    navigationRequestedForChapter != chapter?.id,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.ChevronLeft,
                                    contentDescription = "Предыдущая глава",
                                    tint = if (canGoPrevious) palette.accent else palette.secondaryText,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${book.currentChapter} из ${book.totalChapters}",
                                    color = palette.text,
                                )
                                Text(
                                    "${(progress * 100).toInt()}% главы",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.secondaryText,
                                )
                            }
                            IconButton(
                                onClick = { navigate(forward = true) },
                                enabled = canGoNext &&
                                    navigationRequestedForChapter != chapter?.id,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = "Следующая глава",
                                    tint = if (canGoNext) palette.accent else palette.secondaryText,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun webProgress(view: WebView): Float {
    val contentHeight = (view.contentHeight * view.scale).toInt()
    val maximum = (contentHeight - view.height).coerceAtLeast(1)
    return (view.scrollY.toFloat() / maximum.toFloat()).coerceIn(0f, 1f)
}

private fun buildStyleScript(
    preferences: ReaderPreferences,
    palette: RichReaderPalette,
): String {
    val family = when (preferences.font) {
        ReaderFontOption.DEFAULT -> "system-ui, sans-serif"
        ReaderFontOption.SERIF -> "Georgia, 'Times New Roman', serif"
        ReaderFontOption.SANS_SERIF -> "Arial, sans-serif"
        ReaderFontOption.MONOSPACE -> "monospace"
    }
    val css = """
        :root { color-scheme: light; }
        html, body {
            background: ${palette.background.toCss()} !important;
            color: ${palette.text.toCss()} !important;
        }
        body {
            font-family: $family !important;
            font-size: ${preferences.fontSizeSp}px !important;
            line-height: ${preferences.lineHeightMultiplier} !important;
            max-width: ${preferences.contentWidthDp}px !important;
            padding-left: ${preferences.horizontalPaddingDp}px !important;
            padding-right: ${preferences.horizontalPaddingDp}px !important;
            margin-left: auto !important;
            margin-right: auto !important;
        }
        body, body p, body div, body span, body section, body article,
        body blockquote, body li, body td, body th, body h1, body h2,
        body h3, body h4, body h5, body h6, body em, body strong,
        body small, body code, body pre {
            color: ${palette.text.toCss()} !important;
        }
        body p, body div, body span, body section, body article,
        body blockquote, body li, body td, body th, body h1, body h2,
        body h3, body h4, body h5, body h6 {
            background-color: transparent !important;
        }
        p { margin-top: 0; margin-bottom: ${preferences.paragraphSpacingDp}px !important; }
        p { text-indent: ${preferences.firstLineIndentEm}em; }
        img, svg { max-width: 100% !important; height: auto !important; }
        a, a * { color: ${palette.accent.toCss()} !important; }
        hr { border-color: ${palette.track.toCss()} !important; }
        table, td, th { border-color: ${palette.track.toCss()} !important; }
        ::selection {
            background: ${palette.selection.toCss()} !important;
            color: ${palette.text.toCss()} !important;
        }
    """.trimIndent()
    val quotedCss = JSONObject.quote(css)
    return """
        (function() {
            var style = document.getElementById('dollarreader-style');
            if (!style) {
                style = document.createElement('style');
                style.id = 'dollarreader-style';
                document.head.appendChild(style);
            }
            style.textContent = $quotedCss;
        })();
    """.trimIndent()
}

@Composable
private fun richPalette(theme: ReaderColorTheme): RichReaderPalette = when (theme) {
    ReaderColorTheme.SYSTEM -> RichReaderPalette(
        background = MaterialTheme.colorScheme.background,
        surface = MaterialTheme.colorScheme.surface,
        text = MaterialTheme.colorScheme.onBackground,
        secondaryText = MaterialTheme.colorScheme.onSurfaceVariant,
        accent = MaterialTheme.colorScheme.primary,
        track = MaterialTheme.colorScheme.surfaceVariant,
        selection = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
    )
    ReaderColorTheme.PAPER -> RichReaderPalette(
        Color(0xFFFFFBF4), Color(0xFFF6EFE5), Color(0xFF292521),
        Color(0xFF6D6258), Color(0xFF76558F), Color(0xFFE3D8CC), Color(0xFFD7C2EA),
    )
    ReaderColorTheme.SEPIA -> RichReaderPalette(
        Color(0xFFF1E6CC), Color(0xFFE8D9B9), Color(0xFF3A3027),
        Color(0xFF71614F), Color(0xFF7A536E), Color(0xFFD7C5A2), Color(0xFFC9A8BA),
    )
    ReaderColorTheme.NIGHT -> RichReaderPalette(
        Color(0xFF17181D), Color(0xFF23242B), Color(0xFFE8E5EB),
        Color(0xFFB9B5C0), Color(0xFFCEAAFF), Color(0xFF393A43), Color(0xFF5A4075),
    )
    ReaderColorTheme.BLACK -> RichReaderPalette(
        Color.Black, Color(0xFF111111), Color(0xFFF1F1F1),
        Color(0xFFBDBDBD), Color(0xFFD5B7FF), Color(0xFF2A2A2A), Color(0xFF5A4075),
    )
}

private data class RichReaderPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val track: Color,
    val selection: Color,
)

private fun Color.toArgb(): Int = AndroidColor.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private fun Color.toCss(): String = String.format(
    "#%02X%02X%02X",
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private const val RICH_POSITION_SAVE_DELAY_MS = 650L
private const val RICH_SCROLL_SLOP_PX = 4
private const val RICH_TOP_REVEAL_PX = 2
