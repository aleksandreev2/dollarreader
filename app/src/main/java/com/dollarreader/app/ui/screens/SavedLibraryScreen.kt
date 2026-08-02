package com.dollarreader.app.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dollarreader.app.data.AnnotationRepository
import com.dollarreader.app.data.decodeBookmarkLabel
import com.dollarreader.app.data.isBookmarkNote
import com.dollarreader.app.data.local.DollarReaderDatabase
import com.dollarreader.app.model.Book
import com.dollarreader.app.model.LibrarySearchIndexStatus
import com.dollarreader.app.model.LibrarySearchResult
import com.dollarreader.app.model.ReadingAnnotationType
import com.dollarreader.app.model.SavedLibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val database = remember(context) {
        DollarReaderDatabase.getInstance(context.applicationContext)
    }
    val repository = remember(database) { AnnotationRepository(database) }
    val indexStatus by repository.searchIndexStatus.collectAsState(
        initial = LibrarySearchIndexStatus(),
    )
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf(SavedSection.SAVED) }
    var filter by remember { mutableStateOf(SavedFilter.ALL) }
    var selectedTitleId by remember { mutableStateOf<String?>(null) }
    var titleMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LibrarySearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedAnnotationIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingEdit by remember { mutableStateOf<SavedLibraryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkLabel by remember { mutableStateOf("") }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    val sortedBooks = remember(books) { books.sortedBy { it.title.lowercase() } }
    val selectedBook = sortedBooks.firstOrNull { it.id == selectedTitleId }
    val filteredFavorites = books.filter { book ->
        book.isFavorite && (selectedTitleId == null || book.id == selectedTitleId)
    }
    val filteredSavedItems = savedItems.filter { item ->
        val titleMatches = selectedTitleId == null || item.titleId == selectedTitleId
        val bookmark = isBookmarkNote(item.noteText)
        val typeMatches = when (filter) {
            SavedFilter.ALL -> true
            SavedFilter.NOTES -> item.type == ReadingAnnotationType.NOTE && !bookmark
            SavedFilter.HIGHLIGHTS -> item.type == ReadingAnnotationType.HIGHLIGHT
            SavedFilter.BOOKMARKS -> bookmark
            SavedFilter.FAVORITES -> false
        }
        titleMatches && typeMatches
    }
    val showFavorites = filter == SavedFilter.ALL || filter == SavedFilter.FAVORITES

    LaunchedEffect(Unit) {
        runCatching { repository.refreshSearchIndex(force = false) }
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

    pendingEdit?.let { item ->
        val bookmark = isBookmarkNote(item.noteText)
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = {
                Text(if (bookmark) "Название закладки" else "Редактировать заметку")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        item.selectedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (bookmark) "Название — необязательно" else "Текст заметки")
                        },
                        minLines = if (bookmark) 1 else 3,
                        maxLines = 7,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                if (bookmark) {
                                    repository.updateBookmark(item.id, editText)
                                } else {
                                    repository.updateNote(item.id, editText)
                                }
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "Не удалось сохранить изменения",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            pendingEdit = null
                        }
                    },
                    enabled = bookmark || editText.isNotBlank(),
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEdit = null }) {
                    Text("Отмена")
                }
            },
        )
    }

    if (showBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarkDialog = false },
            title = { Text("Закладка текущего места") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        selectedBook?.let { book ->
                            "Будет сохранена текущая позиция в «${book.title}»."
                        } ?: "Сначала выберите тайтл.",
                    )
                    OutlinedTextField(
                        value = bookmarkLabel,
                        onValueChange = { bookmarkLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название — необязательно") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val book = selectedBook ?: return@Button
                        scope.launch {
                            runCatching {
                                repository.addCurrentBookmark(book.id, bookmarkLabel)
                            }.onSuccess {
                                Toast.makeText(context, "Закладка сохранена", Toast.LENGTH_SHORT).show()
                                showBookmarkDialog = false
                                bookmarkLabel = ""
                                filter = SavedFilter.BOOKMARKS
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "Не удалось создать закладку",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    enabled = selectedBook != null,
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Удалить выбранное?") },
            text = {
                Text("Будет удалено элементов: ${selectedAnnotationIds.size}. Отменить это действие нельзя.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedAnnotationIds
                        scope.launch {
                            repository.deleteAnnotations(ids)
                            selectedAnnotationIds = emptySet()
                            isSelectionMode = false
                            showBatchDeleteDialog = false
                        }
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isSelectionMode) {
                            "Выбрано: ${selectedAnnotationIds.size}"
                        } else {
                            "Сохранённое"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!isSelectionMode) {
                        Text(
                            "Закладки, заметки, подсветки и быстрый поиск",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (section == SavedSection.SAVED && savedItems.isNotEmpty()) {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = {
                                if (selectedAnnotationIds.isNotEmpty()) {
                                    showBatchDeleteDialog = true
                                }
                            },
                            enabled = selectedAnnotationIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Удалить выбранное")
                        }
                        TextButton(
                            onClick = {
                                isSelectionMode = false
                                selectedAnnotationIds = emptySet()
                            },
                        ) {
                            Text("Готово")
                        }
                    } else {
                        TextButton(onClick = { isSelectionMode = true }) {
                            Text("Выбрать")
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = section.ordinal) {
                SavedSection.entries.forEach { item ->
                    Tab(
                        selected = section == item,
                        onClick = {
                            section = item
                            isSelectionMode = false
                            selectedAnnotationIds = emptySet()
                        },
                        text = { Text(item.label) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            .padding(top = 6.dp),
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    "Закладка текущего места",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    selectedBook?.let { "Тайтл: ${it.title}" }
                                        ?: "Выберите конкретный тайтл выше",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { showBookmarkDialog = true },
                                enabled = selectedBook != null,
                            ) {
                                Text("Добавить")
                            }
                        }
                    }
                } else {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("Текст в локальных главах") },
                        singleLine = true,
                    )
                    SearchIndexCard(
                        status = indexStatus,
                        onRefresh = {
                            scope.launch {
                                runCatching { repository.refreshSearchIndex(force = true) }
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: "Не удалось перестроить индекс",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                            }
                        },
                    )
                }
            }

            HorizontalDivider()

            if (section == SavedSection.SAVED) {
                SavedContent(
                    savedItems = filteredSavedItems,
                    favorites = if (showFavorites) filteredFavorites else emptyList(),
                    filter = filter,
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedAnnotationIds,
                    onToggleSelection = { id ->
                        selectedAnnotationIds = if (id in selectedAnnotationIds) {
                            selectedAnnotationIds - id
                        } else {
                            selectedAnnotationIds + id
                        }
                    },
                    onOpenSaved = onOpenSaved,
                    onOpenFavorite = onOpenFavorite,
                    onEdit = { item ->
                        pendingEdit = item
                        editText = if (isBookmarkNote(item.noteText)) {
                            decodeBookmarkLabel(item.noteText).orEmpty()
                        } else {
                            item.noteText.orEmpty()
                        }
                    },
                    onDelete = onDeleteSaved,
                )
            } else {
                SearchContent(
                    query = searchQuery,
                    results = searchResults,
                    isSearching = isSearching,
                    error = searchError,
                    onOpen = onOpenSearchResult,
                )
            }
        }
    }
}

@Composable
private fun SearchIndexCard(
    status: LibrarySearchIndexStatus,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.isRebuilding) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Search, contentDescription = null)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    if (status.isRebuilding) "Обновление поискового индекса" else "Поисковый индекс",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Глав: ${status.indexedChapters} из ${status.expectedChapters} · " +
                        "абзацев: ${status.indexedParagraphs}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.lastError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !status.isRebuilding) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Перестроить индекс")
            }
        }
    }
}

@Composable
private fun SavedContent(
    savedItems: List<SavedLibraryItem>,
    favorites: List<Book>,
    filter: SavedFilter,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onOpenSaved: (SavedLibraryItem) -> Unit,
    onOpenFavorite: (Book) -> Unit,
    onEdit: (SavedLibraryItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (savedItems.isEmpty() && favorites.isEmpty()) {
        EmptyState(
            title = when (filter) {
                SavedFilter.NOTES -> "Заметок пока нет"
                SavedFilter.HIGHLIGHTS -> "Подсветок пока нет"
                SavedFilter.BOOKMARKS -> "Закладок пока нет"
                SavedFilter.FAVORITES -> "Избранных тайтлов пока нет"
                SavedFilter.ALL -> "Ничего не сохранено"
            },
            description = "Сохраняйте фрагменты и текущие места во время чтения.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (favorites.isNotEmpty()) {
            item {
                Text(
                    "Избранные тайтлы",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            items(favorites, key = { "favorite-${it.id}" }) { book ->
                FavoriteCard(book = book, onClick = { onOpenFavorite(book) })
            }
            if (savedItems.isNotEmpty()) {
                item { Spacer(Modifier.height(6.dp)) }
            }
        }

        if (savedItems.isNotEmpty()) {
            item {
                Text(
                    "Метки в тексте",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            items(savedItems, key = { "saved-${it.id}" }) { item ->
                SavedItemCard(
                    item = item,
                    isSelectionMode = isSelectionMode,
                    isSelected = item.id in selectedIds,
                    onClick = {
                        if (isSelectionMode) onToggleSelection(item.id) else onOpenSaved(item)
                    },
                    onToggleSelection = { onToggleSelection(item.id) },
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
    }
}

@Composable
private fun SavedItemCard(
    item: SavedLibraryItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val bookmark = isBookmarkNote(item.noteText)
    val icon = when {
        bookmark -> Icons.Outlined.Bookmark
        item.type == ReadingAnnotationType.NOTE -> Icons.Outlined.StickyNote2
        else -> Icons.Outlined.Highlight
    }
    val label = when {
        bookmark -> "Закладка"
        item.type == ReadingAnnotationType.NOTE -> "Заметка"
        else -> "Подсветка"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (isSelectionMode) {
                IconButton(onClick = onToggleSelection, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isSelected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        contentDescription = if (isSelected) "Снять выбор" else "Выбрать",
                    )
                }
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    "$label · ${item.titleName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.chapterName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bookmark) {
                    decodeBookmarkLabel(item.noteText)?.let { bookmarkLabel ->
                        Text(
                            bookmarkLabel,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "Абзац ${item.paragraphIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        item.selectedText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    item.noteText?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
            }
            if (!isSelectionMode) {
                Column {
                    if (bookmark || item.type == ReadingAnnotationType.NOTE) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Редактировать")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Удалить")
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(book: Book, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Favorite,
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
                    "Глава ${book.currentChapter} из ${book.totalChapters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Открыть", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    results: List<LibrarySearchResult>,
    isSearching: Boolean,
    error: String?,
    onOpen: (LibrarySearchResult) -> Unit,
) {
    when {
        query.trim().length < 2 -> EmptyState(
            title = "Введите минимум два символа",
            description = "Поиск выполняется по постоянному индексу локальных TXT-глав.",
        )
        isSearching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> EmptyState(
            title = "Поиск не выполнен",
            description = error,
        )
        results.isEmpty() -> EmptyState(
            title = "Совпадений нет",
            description = "Проверьте запрос или выберите другой тайтл.",
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Найдено: ${results.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                results,
                key = { result ->
                    "${result.chapterId}:${result.paragraphIndex}:${result.excerpt.hashCode()}"
                },
            ) { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(result) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            result.titleName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            result.chapterName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            result.excerpt,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

private enum class SavedSection(val label: String) {
    SAVED("Метки"),
    SEARCH("Поиск"),
}

private enum class SavedFilter(val label: String) {
    ALL("Все"),
    NOTES("Заметки"),
    HIGHLIGHTS("Подсветки"),
    BOOKMARKS("Закладки"),
    FAVORITES("Избранное"),
}

private const val SEARCH_DEBOUNCE_MS = 350L
