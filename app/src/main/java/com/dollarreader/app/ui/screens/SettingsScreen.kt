package com.dollarreader.app.ui.screens

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
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dollarreader.app.model.ReaderPreferences
import com.dollarreader.app.ui.components.ReaderSettingsControls

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    readerPreferences: ReaderPreferences,
    onReaderPreferencesChange: (ReaderPreferences) -> Unit,
) {
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
                    "Внешний вид и параметры чтения",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            SettingCard(
                title = "Google Drive",
                subtitle = "Синхронизация появится на этапе 0.8.0",
                icon = { Icon(Icons.Outlined.CloudQueue, contentDescription = null) },
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
