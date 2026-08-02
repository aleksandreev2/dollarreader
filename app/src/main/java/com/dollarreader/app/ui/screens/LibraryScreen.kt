package com.dollarreader.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollarreader.app.data.importer.ImportResult
import com.dollarreader.app.model.Book
import com.dollarreader.app.ui.components.BookRow
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onImport: suspend (Uri) -> ImportResult,
) {
    val scope = rememberCoroutineScope()
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ImportNotice?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            notice = null
            runCatching { onImport(uri) }
                .onSuccess { result ->
                    val action = if (result.updatedExistingTitle) "обновлён" else "добавлен"
                    notice = ImportNotice(
                        message = "${result.title}: $action, глав — ${result.chaptersImported}",
                        isError = false,
                    )
                }
                .onFailure { error ->
                    notice = ImportNotice(
                        message = error.message ?: "Не удалось импортировать файл",
                        isError = true,
                    )
                }
            isImporting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Библиотека", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${books.size} тайтлов и документов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { }, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.FilterList, contentDescription = "Фильтры")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    filePicker.launch(
                        arrayOf(
                            "text/plain",
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !isImporting,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Add, contentDescription = "Добавить TXT или ZIP")
                }
            }
        }

        notice?.let { currentNotice ->
            ImportNoticeCard(
                notice = currentNotice,
                onDismiss = { notice = null },
            )
        }

        if (books.isEmpty()) {
            Text(
                text = "Нажмите «Добавить», чтобы выбрать TXT или ZIP с главами",
                modifier = Modifier.padding(top = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(book = book, onClick = { onBookClick(book) })
                }
            }
        }
    }
}

@Composable
private fun ImportNoticeCard(
    notice: ImportNotice,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
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
            Text(
                text = notice.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Закрыть сообщение")
            }
        }
    }
}

private data class ImportNotice(
    val message: String,
    val isError: Boolean,
)
