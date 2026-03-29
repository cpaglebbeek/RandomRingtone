package nl.icthorse.randomringtone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nl.icthorse.randomringtone.data.AppRingtoneManager
import nl.icthorse.randomringtone.data.RingtoneDatabase
import nl.icthorse.randomringtone.ui.screens.*
import java.io.File
import nl.icthorse.randomringtone.ui.theme.RandomRingtoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RandomRingtoneTheme {
                RandomRingtoneApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomRingtoneApp() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val ringtoneManager = remember { AppRingtoneManager(context) }
    val db = remember { RingtoneDatabase.getInstance(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val tabs = listOf(
        Triple("search", "Zoeken", Icons.Default.MusicNote),
        Triple("library", "Bibliotheek", Icons.Default.LibraryMusic),
        Triple("assignments", "Toewijzingen", Icons.Default.Tune),
        Triple("settings", "Instellingen", Icons.Default.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RandomRingtone v${BuildConfig.VERSION_NAME}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (route, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            navController.navigate(route) {
                                popUpTo("search") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "search",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("search") {
                PlaylistScreen(
                    ringtoneManager = ringtoneManager,
                    snackbarHostState = snackbarHostState,
                    db = db,
                    onOpenEditor = { track, file ->
                        // Sla editor-params op in navigation args via route
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set("editorTrackTitle", track.titleShort.ifBlank { track.title })
                            set("editorTrackArtist", track.artist.name)
                            set("editorFilePath", file.absolutePath)
                            set("editorTrackId", track.id)
                            set("editorPreviewUrl", track.preview)
                        }
                        navController.navigate("editor")
                    }
                )
            }
            composable("editor") {
                val prevEntry = navController.previousBackStackEntry
                val title = prevEntry?.savedStateHandle?.get<String>("editorTrackTitle") ?: ""
                val artist = prevEntry?.savedStateHandle?.get<String>("editorTrackArtist") ?: ""
                val filePath = prevEntry?.savedStateHandle?.get<String>("editorFilePath") ?: ""
                val trackId = prevEntry?.savedStateHandle?.get<Long>("editorTrackId") ?: 0L
                val previewUrl = prevEntry?.savedStateHandle?.get<String>("editorPreviewUrl") ?: ""

                if (filePath.isNotBlank()) {
                    EditorScreen(
                        trackTitle = title,
                        trackArtist = artist,
                        audioFile = File(filePath),
                        deezerTrackId = trackId,
                        previewUrl = previewUrl,
                        db = db,
                        ringtoneManager = ringtoneManager,
                        snackbarHostState = snackbarHostState,
                        onDone = { navController.popBackStack() }
                    )
                }
            }
            composable("library") {
                LibraryScreen(
                    db = db,
                    ringtoneManager = ringtoneManager,
                    snackbarHostState = snackbarHostState
                )
            }
            composable("assignments") {
                AssignmentScreen(
                    db = db,
                    onNavigateToContactPicker = { },
                    onNavigateToTrackPicker = { }
                )
            }
            composable("settings") {
                SettingsScreen(
                    ringtoneManager = ringtoneManager,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}
