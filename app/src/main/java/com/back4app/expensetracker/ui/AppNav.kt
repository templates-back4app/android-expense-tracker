package com.back4app.expensetracker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.backend.Auth
import com.back4app.expensetracker.ui.screens.CategoriesScreen
import com.back4app.expensetracker.ui.screens.EntriesScreen
import com.back4app.expensetracker.ui.screens.EntryFormScreen
import com.back4app.expensetracker.ui.screens.LoginScreen
import com.back4app.expensetracker.ui.screens.SettingsScreen

object Routes {
    const val LOGIN = "login"
    const val ENTRIES = "entries"
    const val ENTRY_NEW = "entry-new"
    const val ENTRY_EDIT = "entry/{entryKey}"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    fun edit(entryKey: String) = "entry/$entryKey"
}

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val start = if (container.backendConfigured && Auth.currentUser() != null) Routes.ENTRIES else Routes.LOGIN
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(container, onSignedIn = {
                nav.navigate(Routes.ENTRIES) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.ENTRIES) {
            EntriesScreen(
                container,
                onAdd = { nav.navigate(Routes.ENTRY_NEW) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onCategories = { nav.navigate(Routes.CATEGORIES) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ENTRY_NEW) {
            EntryFormScreen(container, entryKey = null, onDone = { nav.popBackStack() })
        }
        composable(Routes.ENTRY_EDIT, arguments = listOf(navArgument("entryKey") { type = NavType.StringType })) { backStack ->
            EntryFormScreen(container, entryKey = backStack.arguments?.getString("entryKey"), onDone = { nav.popBackStack() })
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(container, onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(container, onBack = { nav.popBackStack() }, onSignedOut = {
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            })
        }
    }
}
