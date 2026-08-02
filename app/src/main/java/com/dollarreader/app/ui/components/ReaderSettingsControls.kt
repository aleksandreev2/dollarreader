package com.dollarreader.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dollarreader.app.model.ReaderColorTheme
import com.dollarreader.app.model.ReaderFontOption
import com.dollarreader.app.model.ReaderPreferences
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReaderSettingsControls(
    preferences: ReaderPreferences,
    onPreferencesChange: (ReaderPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSection(title = "Управление") {
            ToggleSetting(
                title = "Закрепить панели читалки",
                subtitle = if (preferences.keepControlsVisible) {
                    "Верхняя и нижняя панели всегда остаются на экране"
                } else {
                    "При прокрутке вниз панели скрываются, при прокрутке вверх появляются"
                },
                checked = preferences.keepControlsVisible,
                onCheckedChange = { checked ->
                    onPreferencesChange(preferences.copy(keepControlsVisible = checked))
                },
            )
            Text(
                "Кнопка громкости + открывает предыдущую главу, кнопка громкости − — следующую. " +
                    "Горизонтальный свайп между главами отключён.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(title = "Шрифт") {
            ChoiceRow {
                ReaderFontOption.entries.forEach { option ->
                    FilterChip(
                        selected = preferences.font == option,
                        onClick = {
                            onPreferencesChange(preferences.copy(font = option))
                        },
                        label = { Text(option.label) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            FloatSetting(
                title = "Размер текста",
                value = preferences.fontSizeSp,
                range = 14f..30f,
                valueLabel = "${preferences.fontSizeSp.roundToInt()} sp",
                steps = 15,
                onCommit = { value ->
                    onPreferencesChange(preferences.copy(fontSizeSp = value.roundToInt().toFloat()))
                },
            )
            FloatSetting(
                title = "Межстрочный интервал",
                value = preferences.lineHeightMultiplier,
                range = 1.1f..2.2f,
                valueLabel = formatDecimal(preferences.lineHeightMultiplier),
                onCommit = { value ->
                    onPreferencesChange(
                        preferences.copy(
                            lineHeightMultiplier = (value * 10f).roundToInt() / 10f,
                        ),
                    )
                },
            )
        }

        SettingsSection(title = "Абзацы") {
            FloatSetting(
                title = "Расстояние между абзацами",
                value = preferences.paragraphSpacingDp,
                range = 0f..36f,
                valueLabel = "${preferences.paragraphSpacingDp.roundToInt()} dp",
                steps = 17,
                onCommit = { value ->
                    onPreferencesChange(
                        preferences.copy(paragraphSpacingDp = value.roundToInt().toFloat()),
                    )
                },
            )
            FloatSetting(
                title = "Красная строка",
                value = preferences.firstLineIndentEm,
                range = 0f..2.5f,
                valueLabel = "${formatDecimal(preferences.firstLineIndentEm)} em",
                onCommit = { value ->
                    onPreferencesChange(
                        preferences.copy(
                            firstLineIndentEm = (value * 10f).roundToInt() / 10f,
                        ),
                    )
                },
            )
            ToggleSetting(
                title = "Показывать заголовок главы",
                checked = preferences.showChapterTitle,
                onCheckedChange = { checked ->
                    onPreferencesChange(preferences.copy(showChapterTitle = checked))
                },
            )
        }

        SettingsSection(title = "Ширина текста") {
            FloatSetting(
                title = "Максимальная ширина",
                value = preferences.contentWidthDp.toFloat(),
                range = 320f..1000f,
                valueLabel = "${preferences.contentWidthDp} dp",
                onCommit = { value ->
                    onPreferencesChange(
                        preferences.copy(contentWidthDp = value.roundToInt()),
                    )
                },
            )
            FloatSetting(
                title = "Боковые поля",
                value = preferences.horizontalPaddingDp.toFloat(),
                range = 8f..56f,
                valueLabel = "${preferences.horizontalPaddingDp} dp",
                steps = 11,
                onCommit = { value ->
                    onPreferencesChange(
                        preferences.copy(horizontalPaddingDp = value.roundToInt()),
                    )
                },
            )
        }

        SettingsSection(title = "Цветовая тема читалки") {
            ChoiceRow {
                ReaderColorTheme.entries.forEach { option ->
                    FilterChip(
                        selected = preferences.colorTheme == option,
                        onClick = {
                            onPreferencesChange(preferences.copy(colorTheme = option))
                        },
                        label = { Text(option.label) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        ReaderPreview(preferences)

        OutlinedButton(
            onClick = { onPreferencesChange(ReaderPreferences.Default) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Сбросить настройки чтения")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        content()
    }
}

@Composable
private fun FloatSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(title)
            subtitle?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ReaderPreview(preferences: ReaderPreferences) {
    val fontFamily = when (preferences.font) {
        ReaderFontOption.DEFAULT -> FontFamily.Default
        ReaderFontOption.SERIF -> FontFamily.Serif
        ReaderFontOption.SANS_SERIF -> FontFamily.SansSerif
        ReaderFontOption.MONOSPACE -> FontFamily.Monospace
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(
                preferences.paragraphSpacingDp.coerceIn(4f, 24f).dp,
            ),
        ) {
            Text(
                "Предпросмотр",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Книга должна оставаться удобной и вечером, и при долгом чтении. Здесь сразу видно размер букв и выбранную гарнитуру.",
                fontFamily = fontFamily,
                fontSize = preferences.fontSizeSp.sp,
                lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
            )
        }
    }
}

private fun formatDecimal(value: Float): String =
    String.format(Locale.US, "%.1f", value)
