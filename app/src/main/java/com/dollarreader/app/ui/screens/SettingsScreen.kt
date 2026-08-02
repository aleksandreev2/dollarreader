package com.dollarreader.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.TextFields
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

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Внешний вид и параметры чтения",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 8.dp))
        SettingCard(
            title = "Тёмная тема",
            subtitle = "Переключает оформление всего приложения",
            icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
            control = { Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange) },
        )
        SettingCard(
            title = "Настройки читалки",
            subtitle = "Шрифт, размер, интервалы, цвета и режим глав",
            icon = { Icon(Icons.Outlined.TextFields, contentDescription = null) },
        )
        SettingCard(
            title = "Google Drive",
            subtitle = "Синхронизация появится на этапе 0.8.0",
            icon = { Icon(Icons.Outlined.CloudQueue, contentDescription = null) },
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            control?.invoke()
        }
    }
}
