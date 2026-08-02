package com.dollarreader.app.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Stores the application appearance outside of a composable lifecycle.
 *
 * The value is read synchronously before the first themed frame, so the app
 * does not briefly return to the light theme after a process restart.
 */
object AppAppearancePreferences {
    private const val FILE_NAME = "dollarreader-appearance"
    private const val KEY_DARK_THEME = "dark-theme"

    private val mutableDarkTheme = mutableStateOf<Boolean?>(null)

    val darkTheme: State<Boolean?>
        get() = mutableDarkTheme

    @Synchronized
    fun initialize(context: Context, fallback: Boolean = false) {
        if (mutableDarkTheme.value != null) return
        mutableDarkTheme.value = preferences(context).getBoolean(KEY_DARK_THEME, fallback)
    }

    fun setDarkTheme(context: Context, enabled: Boolean) {
        mutableDarkTheme.value = enabled
        preferences(context)
            .edit()
            .putBoolean(KEY_DARK_THEME, enabled)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
