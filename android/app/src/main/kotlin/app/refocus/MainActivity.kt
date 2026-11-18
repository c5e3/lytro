package app.refocus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.refocus.ui.gallery.GalleryScreen
import app.refocus.ui.settings.SettingsScreen
import app.refocus.ui.theme.RefocusTheme
import app.refocus.ui.viewer.ViewerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = application.graph

        // Auto-connect only when the app starts while the phone's gateway already
        // is the camera. If the WiFi comes up later, the user taps Connect.
        if (!graph.startupCheckDone) {
            graph.startupCheckDone = true
            lifecycleScope.launch {
                if (graph.network.isOnCameraWifiAtStartup()) graph.session.connect()
            }
        }

        setContent {
            RefocusTheme {
                val nav = rememberNavController()
                NavHost(
                    navController = nav,
                    startDestination = "gallery",
                    enterTransition = { fadeIn() + scaleIn(initialScale = 0.96f) },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() + scaleOut(targetScale = 0.96f) },
                ) {
                    composable("gallery") {
                        GalleryScreen(
                            graph = graph,
                            onOpen = { id -> nav.navigate("viewer/$id") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("viewer/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        ViewerScreen(graph = graph, id = entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(graph = graph, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
