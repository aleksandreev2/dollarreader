package com.dollarreader.app.ui.screens

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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollarreader.app.model.Book
import com.dollarreader.app.ui.components.BookRow

@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
) {
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
            Button(onClick = { }, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "Добавить")
            }
        }
        if (books.isEmpty()) {
            Text(
                text = "Добавленные книги появятся здесь",
                modifier = Modifier.padding(top = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books, key = { it.id }) { book ->
                    BookRow(book = book, onClick = { onBookClick(book) })
                }
            }
        }
    }
}
