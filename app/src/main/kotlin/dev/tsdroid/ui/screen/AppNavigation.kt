package dev.tsdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = { navController.navigate("server") {
                    popUpTo("connection") { inclusive = true }
                }},
            )
        }
        composable("server") {
            ServerScreen(
                onDisconnected = { navController.navigate("connection") {
                    popUpTo("server") { inclusive = true }
                }},
            )
        }
    }
}
