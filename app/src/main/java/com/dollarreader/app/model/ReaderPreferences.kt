package com.dollarreader.app.model

enum class ReaderFontOption(val label: String) {
    DEFAULT("Системный"),
    SERIF("С засечками"),
    SANS_SERIF("Без засечек"),
    MONOSPACE("Моноширинный"),
}

enum class ReaderColorTheme(val label: String) {
    SYSTEM("Как в приложении"),
    PAPER("Бумага"),
    SEPIA("Сепия"),
    NIGHT("Ночная"),
    BLACK("Чёрная"),
}

data class ReaderPreferences(
    val font: ReaderFontOption = ReaderFontOption.SERIF,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphSpacingDp: Float = 16f,
    val firstLineIndentEm: Float = 1.25f,
    val contentWidthDp: Int = 720,
    val horizontalPaddingDp: Int = 24,
    val colorTheme: ReaderColorTheme = ReaderColorTheme.SYSTEM,
    val showChapterTitle: Boolean = true,
    val keepControlsVisible: Boolean = false,
) {
    companion object {
        val Default = ReaderPreferences()
    }
}

data class ChapterReadingPosition(
    val chapterId: String,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val progress: Float,
    val updatedAt: Long = 0L,
)
