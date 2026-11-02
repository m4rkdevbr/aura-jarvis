package app.dzz.copilot

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.dzz.copilot.ui.AlbumScreen
import app.dzz.copilot.ui.AppBackground
import app.dzz.copilot.ui.BrowserScreen
import app.dzz.copilot.ui.ChatScreen
import app.dzz.copilot.ui.Copper
import app.dzz.copilot.ui.CopilotScreen
import app.dzz.copilot.ui.DzzTheme
import app.dzz.copilot.ui.GlassEdge
import app.dzz.copilot.ui.GlassHigh
import app.dzz.copilot.ui.GlassesScreen
import app.dzz.copilot.ui.GuideScreen
import app.dzz.copilot.ui.HomeScreen
import app.dzz.copilot.ui.MutedField
import app.dzz.copilot.ui.PanelScreen
import app.dzz.copilot.ui.SettingsScreen
import app.dzz.copilot.ui.Space
import app.dzz.copilot.ui.TasksScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Start on-device LFM from Activity (allowed to launch FGS); not from Application.
        ensureLocalBrain()
        setContent {
            DzzTheme {
                AppBackground {
                    DzzApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Only re-check; do not force a second FGS start (port race).
        Thread {
            runCatching {
                val hub = (application as DzzApplication).hub
                hub.onDeviceLlm.refresh()
                if (hub.onDeviceLlm.probeHealth()) {
                    hub.onDeviceLlm.markServer(true, "LFM3-Omni ativo em 127.0.0.1:11434")
                }
            }
        }.start()
    }

    private fun ensureLocalBrain() {
        Log.e("DzzBrain", "ensureLocalBrain()")
        val hub = try {
            (application as DzzApplication).hub
        } catch (error: Throwable) {
            Log.e("DzzBrain", "Hub indisponível", error)
            return
        }
        Thread {
            try {
                hub.onDeviceLlm.refresh()
                Log.e(
                    "DzzBrain",
                    "installed=${hub.onDeviceLlm.isInstalled()} enabled=${hub.store.localLfmEnabled.value} health=${hub.onDeviceLlm.probeHealth()}",
                )
                if (!hub.store.localLfmEnabled.value) return@Thread
                if (!hub.onDeviceLlm.isInstalled()) {
                    hub.onDeviceLlm.log("Cérebro não instalado — use Ajustes → Instalar.")
                    return@Thread
                }
                if (hub.onDeviceLlm.probeHealth()) {
                    hub.onDeviceLlm.markServer(true, "LFM3-Omni já respondendo em 127.0.0.1:11434")
                    return@Thread
                }
                if (!hub.onDeviceLlm.tryBeginStart()) {
                    hub.onDeviceLlm.log("Início já em andamento — aguardando.")
                    return@Thread
                }
                hub.onDeviceLlm.log("Iniciando LocalLlmService pela Activity…")
                runOnUiThread {
                    app.dzz.copilot.copilot.LocalLlmService.start(this)
                }
            } catch (error: Throwable) {
                Log.e("DzzBrain", "Falha ao iniciar cérebro", error)
                hub.onDeviceLlm.log("Falha ao iniciar cérebro: ${error.message}")
                hub.onDeviceLlm.endStart()
            }
        }.start()
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun DzzApp() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val stage = context.hub().stage
    val page by stage.page.collectAsState()
    val tabs = listOf(
        Tab("home", "Início", Icons.Filled.Home),
        Tab("aura", "Conversa", Icons.AutoMirrored.Filled.Chat),
        Tab("glasses", "Óculos", Icons.Filled.Bluetooth),
        Tab("tasks", "Agenda", Icons.Filled.Checklist),
        Tab("settings", "Ajustes", Icons.Filled.Settings),
    )
    LaunchedEffect(page) {
        if (!page.isNullOrBlank()) {
            nav.navigate("browser") { launchSingleTop = true }
        }
    }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val focus = route == "browser" || route == "panel"
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!focus) {
                Box(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                    NavigationBar(
                        modifier = Modifier
                            .shadow(16.dp, RoundedCornerShape(28.dp), spotColor = Copper.copy(alpha = 0.28f))
                            .clip(RoundedCornerShape(28.dp))
                            .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                            .background(GlassHigh),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { tab ->
                            val selected = route == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = {
                                    Text(
                                        tab.label,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = Copper,
                                    indicatorColor = Copper,
                                    unselectedIconColor = MutedField,
                                    unselectedTextColor = MutedField,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = {
                fadeIn(tween(220)) + slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(220),
                )
            },
            exitTransition = {
                fadeOut(tween(160))
            },
            popEnterTransition = {
                fadeIn(tween(220)) + slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(220),
                )
            },
            popExitTransition = {
                fadeOut(tween(160))
            },
        ) {
            composable("home") {
                HomeScreen(
                    onGlasses = { nav.navigate("glasses") },
                    onAura = { nav.navigate("aura") },
                    onSee = { nav.navigate("copilot") },
                    onTasks = { nav.navigate("tasks") },
                    onAlbum = { nav.navigate("album") },
                    onGuide = { nav.navigate("guide") },
                    onSettings = { nav.navigate("settings") },
                    onPanel = { nav.navigate("panel") },
                )
            }
            composable("glasses") { GlassesScreen(onSettings = { nav.navigate("settings") }) }
            composable("aura") { ChatScreen() }
            composable("copilot") { CopilotScreen() }
            composable("tasks") { TasksScreen() }
            composable("album") { AlbumScreen() }
            composable("guide") {
                GuideScreen(
                    onTasks = { nav.navigate("tasks") },
                    onAlbum = { nav.navigate("album") },
                )
            }
            composable("browser") {
                BrowserScreen(onBack = {
                    stage.clear()
                    nav.popBackStack()
                })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("panel") { PanelScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
