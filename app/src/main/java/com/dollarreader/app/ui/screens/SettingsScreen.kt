package com.dollarreader.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.dollarreader.app.data.LibraryExportService
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.ui.components.ReaderSettingsControls
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    readerPreferences: ReaderPreferences,
    onReaderPreferencesChange: (ReaderPreferences) -> Unit,
    onExportNotes: suspend (Uri) -> LibraryExportService.ExportSummary,
    onCreateBackup: suspend (Uri) -> LibraryExportService.BackupSummary,
) {
    val scope = rememberCoroutineScope()
    var isWorking by rememberSaveable { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<OperationMessage?>(null) }

    val notesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            operationMessage = null
            runCatching { onExportNotes(uri) }
                .onSuccess { summary ->
                    operationMessage = OperationMessage(
                        text = "Экспортировано элементов: ${summary.itemCount}",
                        isError = false,
                    )
                }
                .onFailure { error ->
                    operationMessage = OperationMessage(
                        text = error.message ?: "Не удалось экспортировать заметки",
                        isError = true,
                    )
                }
            isWorking = false
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            operationMessage = null
            runCatching { onCreateBackup(uri) }
                .onSuccess { summary ->
                    operationMessage = OperationMessage(
                        text = "Резервная копия создана: ${summary.titleCount} тайтлов, ${summary.fileCount} файлов",
                        isError = false,
                    )
                }
                .onFailure { error ->
                    operationMessage = OperationMessage(
                        text = error.message ?: "Не удалось создать резервную копию",
                        isError = true,
                    )
                }
            isWorking = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 20.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Настройки", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Внешний вид, чтение и сохранность библиотеки",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        operationMessage?.let { message ->
            item {
                OperationMessageCard(message)
            }
        }
        if (isWorking) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Подготавливаю файл…",
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
            }
        }
        item {
            SettingCard(
                title = "Тёмная тема",
                subtitle = "Переключает оформление всего приложения",
                icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
                control = {
                    Switch(
                        checked = darkTheme,
                        onCheckedChange = onDarkThemeChange,
                    )
                },
            )
        }
        item {
            Text(
                "Читалка",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            ReaderSettingsControls(
                preferences = readerPreferences,
                onPreferencesChange = onReaderPreferencesChange,
            )
        }
        item {
            Text(
                "Экспорт и резервные копии",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            SettingCard(
                title = "Экспортировать заметки",
                subtitle = "Создаёт Markdown со всеми заметками, подсветками и закладками",
                icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                enabled = !isWorking,
                onClick = { notesLauncher.launch("DollarReader-notes.md") },
            )
        }
        item {
            SettingCard(
                title = "Создать резервную копию",
                subtitle = "Сохраняет базу, внутренние книги, иллюстрации и экспорт заметок в ZIP",
                icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                enabled = !isWorking,
                onClick = { backupLauncher.launch("DollarReader-backup.zip") },
            )
        }
        item {
            SettingCard(
                title = "Google Drive",
                subtitle = "Облачная синхронизация будет добавлена отдельным крупным этапом",
                icon = { Icon(Icons.Outlined.CloudQueue, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun OperationMessageCard(message: OperationMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (message.isError) {
                    Icons.Outlined.ErrorOutline
                } else {
                    Icons.Outlined.CheckCircle
                },
                contentDescription = null,
            )
            Text(
                message.text,
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    control: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(start = 8.dp))
            control?.invoke()
        }
    }
}

private data class OperationMessage(
    val text: String,
    val isError: Boolean,
)
