package com.dollarreader.app

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dollarreader.app.data.LibraryRepository
import com.dollarreader.app.data.ReaderSettingsRepository
import com.dollarreader.app.data.importer.FolderBookImporter
import com.dollarreader.app.data.importer.ImportPreview
import com.dollarreader.app.data.importer.ImportResult
import com.dollarreader.app.data.importer.LocalBookService
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.model.ChapterReadingPosition
import com.dollarreader.app.model.ReaderChapterContent
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.ui.screens.BookDetailsScreen
import com.dollarreader.app.ui.screens.HomeScreen
import com.dollarreader.app.ui.screens.LibraryScreen
import com.dollarreader.app.ui.screens.ReaderScreen
import com.dollarreader.app.ui.screens.SettingsScreen
import com.dollarreader.app.ui.screens.WelcomeScreen
import com.dollarreader.app.ui.theme.DollarReaderTheme
import kotlinx.coroutines.launch

private object Routes {
    const val Welcome = "welcome"
    const val Home = "home"
    const val Library = "library"
    const val Downloads = "downloads"
    const val Settings = "settings"
    const val Book = "book"
    const val Reader = "reader"
    const val BookId = "bookId"

    const val BookPattern = "$Book/{$BookId}"
    const val ReaderPattern = "$Reader/{$BookId}"

    fun book(bookId: String): String = "$Book/$bookId"
    fun reader(bookId: String): String = "$Reader/$bookId"
}

@Composable
fun DollarReaderApp() {
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    val database = remember(context) {
        DollarReaderDatabase.getInstance(context)
    }
    val repository = remember(database) {
        LibraryRepository(database)
    }
    val readerSettingsRepository = remember(database) {
        ReaderSettingsRepository(database)
    }
    val localBookService = remember(context, repository) {
        LocalBookService(context, repository)
    }
    val folderImporter = remember(context, repository) {
        FolderBookImporter(context, repository)
    }
    val books by repository.books.collectAsState(initial = emptyList())
    val readerPreferences by readerSettingsRepository.preferences.collectAsState(
        initial = ReaderPreferences.Default,
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository) {
        repository.seedDemoLibraryIfEmpty()
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(
        Routes.Home,
        Routes.Library,
        Routes.Downloads,
        Routes.Settings,
    )

    DollarReaderTheme(darkTheme = darkTheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val items = listOf(
                            Triple(Routes.Home, "Главная", Icons.Outlined.Home),
                            Triple(Routes.Library, "Библиотека", Icons.Outlined.AutoStories),
                            Triple(Routes.Downloads, "Загрузки", Icons.Outlined.Download),
                            Triple(Routes.Settings, "Настройки", Icons.Outlined.Settings),
                        )
                        items.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = currentRoute == route,
                                onClick = {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(Routes.Home) { saveState = true }
                                    }
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Welcome,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.Welcome) {
                    WelcomeScreen(
                        onStart = {
                            navController.navigate(Routes.Home) {
                                popUpTo(Routes.Welcome) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.Home) {
                    HomeScreen(
                        books = books,
                        onBookClick = { book ->
                            navController.navigate(Routes.book(book.id))
                        },
                    )
                }
                composable(Routes.Library) {
                    LibraryScreen(
                        books = books,
                        onBookClick = { book ->
                            navController.navigate(Routes.book(book.id))
                        },
                        onPreviewImport = localBookService::previewBook,
                        onImport = localBookService::importBook,
                        onPreviewFolder = folderImporter::previewFolder,
                        onImportFolder = folderImporter::importFolder,
                    )
                }
                composable(Routes.Downloads) {
                    LibraryScreen(
                        books = books.filter { it.totalChapters > 0 },
                        onBookClick = { book ->
                            navController.navigate(Routes.book(book.id))
                        },
                        onPreviewImport = localBookService::previewBook,
                        onImport = localBookService::importBook,
                        onPreviewFolder = folderImporter::previewFolder,
                        onImportFolder = folderImporter::importFolder,
                    )
                }
                composable(Routes.Settings) {
                    SettingsScreen(
                        darkTheme = darkTheme,
                        onDarkThemeChange = { darkTheme = it },
                        readerPreferences = readerPreferences,
                        onReaderPreferencesChange = { preferences ->
                            scope.launch {
                                readerSettingsRepository.savePreferences(preferences)
                            }
                        },
                    )
                }
                composable(
                    route = Routes.BookPattern,
                    arguments = listOf(
                        navArgument(Routes.BookId) {
                            type = NavType.StringType
                        },
                    ),
                ) { entry ->
                    val bookId = entry.arguments?.getString(Routes.BookId)
                    val book = books.firstOrNull { it.id == bookId }
                    if (book == null) {
                        LoadingScreen()
                    } else {
                        val contents by repository.observeBookContents(book.id)
                            .collectAsState(initial = null)
                        val management by repository.observeTitleManagement(book.id)
                            .collectAsState(initial = null)
                        val sourceUri = management?.sourceUri?.let { raw ->
                            runCatching { Uri.parse(raw) }.getOrNull()
                        }
                        val sourceFormat = management?.format

                        val previewUpdateAction: (suspend () -> ImportPreview)? =
                            sourceUri?.let { uri ->
                                when (sourceFormat) {
                                    "ZIP/TXT" -> suspend {
                                        localBookService.previewBook(uri)
                                    }
                                    "ПАПКА/TXT" -> suspend {
                                        folderImporter.previewFolder(uri)
                                    }
                                    else -> null
                                }
                            }
                        val applyUpdateAction: (suspend () -> ImportResult)? =
                            sourceUri?.let { uri ->
                                when (sourceFormat) {
                                    "ZIP/TXT" -> suspend {
                                        localBookService.importBook(uri)
                                    }
                                    "ПАПКА/TXT" -> suspend {
                                        folderImporter.importFolder(uri)
                                    }
                                    else -> null
                                }
                            }

                        BookDetailsScreen(
                            book = book,
                            contents = contents,
                            management = management,
                            onBack = { navController.popBackStack() },
                            onRead = {
                                navController.navigate(Routes.reader(book.id))
                            },
                            onChapterClick = { chapterOrder ->
                                scope.launch {
                                    repository.openChapter(book.id, chapterOrder)
                                    navController.navigate(Routes.reader(book.id))
                                }
                            },
                            onSaveMetadata = { title, author, description ->
                                repository.updateTitleMetadata(
                                    titleId = book.id,
                                    title = title,
                                    author = author,
                                    description = description,
                                )
                            },
                            onToggleFavorite = { isFavorite ->
                                repository.setFavorite(book.id, isFavorite)
                            },
                            onDelete = {
                                check(repository.deleteTitle(book.id)) {
                                    "Тайтл уже удалён или недоступен"
                                }
                                navController.popBackStack()
                            },
                            onPreviewUpdate = previewUpdateAction,
                            onApplyUpdate = applyUpdateAction,
                        )
                    }
                }
                composable(
                    route = Routes.ReaderPattern,
                    arguments = listOf(
                        navArgument(Routes.BookId) {
                            type = NavType.StringType
                        },
                    ),
                ) { entry ->
                    val bookId = entry.arguments?.getString(Routes.BookId)
                    val book = books.firstOrNull { it.id == bookId }
                    if (book == null) {
                        LoadingScreen()
                    } else {
                        val chapter by produceState<ReaderChapterContent?>(
                            initialValue = null,
                            key1 = book.id,
                            key2 = book.currentChapter,
                        ) {
                            value = repository.loadChapter(
                                book.id,
                                book.currentChapter,
                            )
                        }
                        val initialPosition by produceState<ChapterReadingPosition?>(
                            initialValue = null,
                            key1 = chapter?.id,
                        ) {
                            value = chapter?.id?.let {
                                readerSettingsRepository.loadPosition(it)
                            }
                        }

                        ReaderScreen(
                            book = book,
                            chapter = chapter,
                            preferences = readerPreferences,
                            initialPosition = initialPosition,
                            onBack = { navController.popBackStack() },
                            onPreferencesChange = { preferences ->
                                scope.launch {
                                    readerSettingsRepository.savePreferences(preferences)
                                }
                            },
                            onPositionChange = { position ->
                                val chapterNumber = chapter?.sortOrder
                                    ?: book.currentChapter
                                scope.launch {
                                    readerSettingsRepository.savePosition(
                                        titleId = book.id,
                                        chapterNumber = chapterNumber,
                                        position = position,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
