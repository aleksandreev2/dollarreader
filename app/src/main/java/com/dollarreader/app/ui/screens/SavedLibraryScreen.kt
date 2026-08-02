package com.dollarreader.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.LibrarySearchResult
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.model.SavedLibraryItem
import kotlinx.coroutines.delay

@Composable
fun SavedLibraryScreen(
    books: List<Book>,
    savedItems: List<SavedLibraryItem>,
    onSearch: suspend (query: String, titleId: String?) -> List<LibrarySearchResult>,
    onOpenSaved: (SavedLibraryItem) -> Unit,
    onOpenFavorite: (Book) -> Unit,
    onOpenSearchResult: (LibrarySearchResult) -> Unit,
    onDeleteSaved: (Long) -> Unit,
) {
    var section by remember { mutableStateOf(SavedSection.SAVED) }
    var filter by remember { mutableStateOf(SavedFilter.ALL) }
    var selectedTitleId by remember { mutableStateOf<String?>(null) }
    var titleMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LibrarySearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    val sortedBooks = remember(books) { books.sortedBy { it.title.lowercase() } }
    val selectedBook = sortedBooks.firstOrNull { it.id == selectedTitleId }
    val filteredFavorites = books.filter { book ->
        book.isFavorite && (selectedTitleId == null || book.id == selectedTitleId)
    }
    val filteredSavedItems = savedItems.filter { item ->
        val titleMatches = selectedTitleId == null || item.titleId == selectedTitleId
        val typeMatches = when (filter) {
            SavedFilter.ALL -> true
            SavedFilter.NOTES -> item.type == ReadingAnnotationType.NOTE
            SavedFilter.HIGHLIGHTS -> item.type == ReadingAnnotationType.HIGHLIGHT
            SavedFilter.BOOKMARKS -> false
        }
        titleMatches && typeMatches
    }

    LaunchedEffect(searchQuery, selectedTitleId, section) {
        if (section != SavedSection.SEARCH || searchQuery.trim().length < 2) {
            searchResults = emptyList()
            isSearching = false
            searchError = null
            return@LaunchedEffect
        }

        delay(SEARCH_DEBOUNCE_MS)
        isSearching = true
        searchError = null
        try {
            searchResults = onSearch(searchQuery.trim(), selectedTitleId)
        } catch (error: Throwable) {
            searchResults = emptyList()
            searchError = error.message ?: "Не удалось выполнить поиск"
        } finally {
            isSearching = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    "Сохранённое",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Заметки, выделения, избранные тайтлы и поиск по локальным главам",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            TabRow(selectedTabIndex = section.ordinal) {
                SavedSection.entries.forEach { item ->
                    Tab(
                        selected = section == item,
                        onClick = { section = item },
                        text = { Text(item.label) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box {
                    TextButton(
                        onClick = { titleMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            selectedBook?.title ?: "Все тайтлы",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Выбрать тайтл",
                        )
                    }
                    DropdownMenu(
                        expanded = titleMenuExpanded,
                        onDismissRequest = { titleMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Все тайтлы") },
                            onClick = {
                                selectedTitleId = null
                                titleMenuExpanded = false
                            },
                        )
                        sortedBooks.forEach { book ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        book.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    selectedTitleId = book.id
                                    titleMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                if (section == SavedSection.SAVED) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SavedFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                } else {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        label = { Text("Поиск в тексте глав") },
                        placeholder = { Text("Введите минимум два символа") },
                    )
                }
            }

            HorizontalDivider()

            if (section == SavedSection.SAVED) {
                SavedItemsContent(
                    filter = filter,
                    favoriteBooks = filteredFavorites,
                    savedItems = filteredSavedItems,
                    onOpenFavorite = onOpenFavorite,
                    onOpenSaved = onOpenSaved,
                    onDeleteSaved = onDeleteSaved,
                )
            } else {
                SearchContent(
                    query = searchQuery,
                    isSearching = isSearching,
                    error = searchError,
                    results = searchResults,
                    onOpenResult = onOpenSearchResult,
                )
            }
        }
    }
}

@Composable
private fun SavedItemsContent(
    filter: SavedFilter,
    favoriteBooks: List<Book>,
    savedItems: List<SavedLibraryItem>,
    onOpenFavorite: (Book) -> Unit,
    onOpenSaved: (SavedLibraryItem) -> Unit,
    onDeleteSaved: (Long) -> Unit,
) {
    val showFavorites = filter == SavedFilter.ALL || filter == SavedFilter.BOOKMARKS
    val showFragments = filter != SavedFilter.BOOKMARKS
    val isEmpty = (!showFavorites || favoriteBooks.isEmpty()) &&
        (!showFragments || savedItems.isEmpty())

    if (isEmpty) {
        EmptySavedState(
            if (filter == SavedFilter.BOOKMARKS) {
                "Добавьте тайтл в избранное на его странице — он появится здесь."
            } else {
                "Сохранённых элементов с выбранными фильтрами пока нет."
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showFavorites && favoriteBooks.isNotEmpty()) {
            item(key = "favorite-header") {
                SectionLabel("Закладки тайтлов")
            }
            items(favoriteBooks, key = { "favorite-${it.id}" }) { book ->
                FavoriteBookCard(book = book, onClick = { onOpenFavorite(book) })
            }
        }

        if (showFragments && savedItems.isNotEmpty()) {
            item(key = "fragment-header") {
                SectionLabel("Фрагменты")
            }
            items(savedItems, key = { "saved-${it.id}" }) { item ->
                SavedFragmentCard(
                    item = item,
                    onClick = { onOpenSaved(item) },
                    onDelete = { onDeleteSaved(item.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    isSearching: Boolean,
    error: String?,
    results: List<LibrarySearchResult>,
    onOpenResult: (LibrarySearchResult) -> Unit,
) {
    when {
        query.trim().length < 2 -> EmptySavedState(
            "Поиск выполняется по тексту импортированных локальных TXT-глав.",
        )
        isSearching -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Text(
                    "Ищу по локальной библиотеке…",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error != null -> EmptySavedState(error)
        results.isEmpty() -> EmptySavedState("Совпадений не найдено.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "search-count") {
                SectionLabel("Найдено: ${results.size}")
            }
            items(
                results,
                key = { result ->
                    "${result.chapterId}:${result.paragraphIndex}:${result.excerpt.hashCode()}"
                },
            ) { result ->
                SearchResultCard(
                    result = result,
                    onClick = { onOpenResult(result) },
                )
            }
        }
    }
}

@Composable
private fun FavoriteBookCard(
    book: Book,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${book.author} · глава ${book.currentChapter} из ${book.totalChapters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SavedFragmentCard(
    item: SavedLibraryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (item.type == ReadingAnnotationType.NOTE) {
                    Icons.Outlined.StickyNote2
                } else {
                    Icons.Outlined.Highlight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    item.titleName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.chapterName} · абзац ${item.paragraphIndex + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    item.selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                item.noteText?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: LibrarySearchResult,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                result.titleName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${result.chapterName} · абзац ${result.paragraphIndex + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                result.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptySavedState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class SavedSection(val label: String) {
    SAVED("Сохранённое"),
    SEARCH("Поиск"),
}

private enum class SavedFilter(val label: String) {
    ALL("Все"),
    NOTES("Заметки"),
    HIGHLIGHTS("Подсветки"),
    BOOKMARKS("Закладки"),
}

private const val SEARCH_DEBOUNCE_MS = 350L
