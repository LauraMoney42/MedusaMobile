package com.medusa.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.medusa.mobile.ui.ChatViewModel
import com.medusa.mobile.ui.screens.ChatScreen
import com.medusa.mobile.ui.screens.SettingsScreen
import com.medusa.mobile.ui.theme.MedusaMobileTheme
import com.medusa.mobile.ui.theme.MedusaColors

/**
 * Single-activity host for Medusa Mobile.
 * Edge-to-edge display with the dark green Medusa theme.
 * Compose Navigation: ChatScreen ↔ SettingsScreen.
 *
 * mm-011: added NavHost + ChatViewModel scoping.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MedusaMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MedusaColors.background
                ) {
                    val navController = rememberNavController()
                    // Scope ViewModel to activity so it survives navigation
                    val chatViewModel: ChatViewModel = viewModel()

                    NavHost(
                        navController = navController,
                        startDestination = "chat"
                    ) {
                        composable("chat") {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = {
                                    navController.popBackStack()
                                    // Refresh API key state when coming back from settings
                                    chatViewModel.refreshApiKeyState()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
