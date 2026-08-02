package com.dollarreader.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dollarreader.app.model.sampleBooks
import com.dollarreader.app.ui.screens.BookDetailsScreen
import com.dollarreader.app.ui.screens.HomeScreen
import com.dollarreader.app.ui.screens.LibraryScreen
import com.dollarreader.app.ui.screens.ReaderScreen
import com.dollarreader.app.ui.screens.SettingsScreen
import com.dollarreader.app.ui.screens.WelcomeScreen
import com.dollarreader.app.ui.theme.DollarReaderTheme

private object Routes {
    const val Welcome = "welcome"
    const val Home = "home"
    const val Library = "library"
    const val Downloads = "downloads"
    const val Settings = "settings"
    const val Book = "book"
    const val Reader = "reader"
}

@Composable
fun DollarReaderApp() {
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.Home, Routes.Library, Routes.Downloads, Routes.Settings)

    DollarReaderTheme(darkTheme = darkTheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val items = listOf(
                            Triple(Routes.Home, "Главная", Icons.Outlined.Home),
                            Triple(Routes.Library, "Библиотека", Icons.Outlined.AutoStories),
                            Triple(Routes.Downloads, "Загрузки", Icons.Outlined.Download),
                            Triple(Routes.Settings, "Настройки", Icons.Outlined.Settings),
                        )
                        items.forEach { (route, label, icon) ->
                            NavigationBarItem(
                                selected = currentRoute == route,
                                onClick = {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(Routes.Home) { saveState = true }
                                    }
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Welcome,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.Welcome) {
                    WelcomeScreen(
                        onStart = {
                            navController.navigate(Routes.Home) {
                                popUpTo(Routes.Welcome) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.Home) {
                    HomeScreen(onBookClick = { navController.navigate(Routes.Book) })
                }
                composable(Routes.Library) {
                    LibraryScreen(onBookClick = { navController.navigate(Routes.Book) })
                }
                composable(Routes.Downloads) {
                    LibraryScreen(onBookClick = { navController.navigate(Routes.Book) })
                }
                composable(Routes.Settings) {
                    SettingsScreen(
                        darkTheme = darkTheme,
                        onDarkThemeChange = { darkTheme = it },
                    )
                }
                composable(Routes.Book) {
                    BookDetailsScreen(
                        book = sampleBooks.first(),
                        onBack = { navController.popBackStack() },
                        onRead = { navController.navigate(Routes.Reader) },
                    )
                }
                composable(Routes.Reader) {
                    ReaderScreen(
                        book = sampleBooks.first(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
