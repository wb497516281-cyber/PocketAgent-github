package com.pocket.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocket.agent.ui.chat.ChatScreen
import com.pocket.agent.ui.chat.ChatViewModel
import com.pocket.agent.ui.files.FilesScreen
import com.pocket.agent.ui.files.FilesViewModel
import com.pocket.agent.ui.settings.SettingsScreen
import com.pocket.agent.ui.settings.SettingsViewModel
import com.pocket.agent.util.AppContainer

/** The three top level destinations. */
private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "对话", Icons.AutoMirrored.Filled.Chat),
    FILES("files", "文件", Icons.Filled.Folder),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

/**
 * App shell.
 *
 * View models are created here rather than inside each destination so a single
 * instance survives tab switches: the files list and the chat screen's
 * directory hint have to agree on the same directory.
 */
@Composable
fun AgentApp(container: AppContainer) {
    val navController = rememberNavController()

    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val filesViewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(container))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))

    val filesState by filesViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.navigate(destination.route) {
                                    popUpTo(Destination.CHAT.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.CHAT.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.CHAT.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    currentDirName = filesState.dirName,
                    onOpenSettings = { navController.navigateTo(Destination.SETTINGS) },
                    onOpenFiles = { navController.navigateTo(Destination.FILES) },
                )
            }
            composable(Destination.FILES.route) {
                FilesScreen(viewModel = filesViewModel)
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}

private fun NavHostController.navigateTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(Destination.CHAT.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
