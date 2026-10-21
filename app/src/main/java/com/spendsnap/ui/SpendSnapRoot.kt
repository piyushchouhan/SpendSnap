package com.spendsnap.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spendsnap.container

/** [route] is the destination pattern; [target] is what to navigate to. */
private data class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector, val target: String = route)

private const val HISTORY = "history?unverified={unverified}"

private val TABS = listOf(
    Tab("home", "Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab(HISTORY, "History", Icons.Rounded.ReceiptLong, Icons.Outlined.ReceiptLong, target = "history"),
    Tab("detection", "Detection", Icons.Rounded.Radar, Icons.Rounded.Radar),
    Tab("settings", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

private val TITLES = mapOf(
    "settings" to "Settings",
    "detection" to "Detection",
    "privacy" to "Privacy",
)

/** Screens that draw their own header (design: Home and the full-screen review). */
private val OWN_HEADER = setOf("home", HISTORY, "new/manual", "new/screenshot")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendSnapRoot() {
    val container = LocalContext.current.container
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTab = TABS.any { it.route == route }
    val reviewQueue = remember { container.repository.observeReviewQueue() }
    val reviewCount = reviewQueue.collectAsStateWithLifecycle(emptyList()).value.size
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A screenshot shared from another app (or picked below) opens the OCR review.
    LaunchedEffect(Unit) {
        container.pendingScreenshot.collect { uri -> if (uri != null) nav.navigate("new/screenshot") }
    }
    LaunchedEffect(Unit) {
        container.openTransaction.collect { id ->
            if (id != null) {
                container.openTransaction.value = null
                editingId = id
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) container.pendingScreenshot.value = uri
    }
    val switchTab: (String) -> Unit = { target ->
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (route != null && route !in OWN_HEADER) {
                TopAppBar(
                    title = { Text(TITLES[route] ?: "SpendSnap") },
                    navigationIcon = {
                        if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                )
            }
        },
        bottomBar = {
            if (isTab) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TABS.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { switchTab(tab.target) },
                            icon = {
                                BadgedBox(badge = { if (tab.route == HISTORY && reviewCount > 0) Badge { Text("$reviewCount") } }) {
                                    Icon(if (selected) tab.selected else tab.unselected, null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Opaque: during predictive back both screens are drawn, so a transparent screen
        // would show the one underneath through it.
        val m = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)
        val open: (Long) -> Unit = { editingId = it }
        // No navigation animations at all: screens switch instantly, including the back gesture
        // (the predictive-back defaults would otherwise shrink and blend the outgoing screen).
        NavHost(
            nav,
            startDestination = "home",
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { ExitTransition.None },
        ) {
            composable("home") {
                HomeScreen(
                    onOpen = open,
                    onReview = {
                        nav.navigate("history?unverified=true") {
                            popUpTo(nav.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                    onHistory = { switchTab("history") },
                    onDetection = { switchTab("detection") },
                    onImportScreenshot = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onAddManual = { nav.navigate("new/manual") },
                    modifier = m,
                )
            }
            composable(
                HISTORY,
                arguments = listOf(navArgument("unverified") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                HistoryScreen(onOpen = open, unverifiedOnly = entry.arguments!!.getBoolean("unverified"), modifier = m)
            }
            composable("detection") { DetectionScreen(m) }
            composable("settings") {
                SettingsScreen(
                    onDetection = { switchTab("detection") },
                    onPrivacy = { nav.navigate("privacy") },
                    onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                    modifier = m,
                )
            }
            composable("privacy") { PrivacyScreen(m) }
            composable("new/manual") { ReviewPaymentScreen(CaptureMode.MANUAL, onDone = { nav.popBackStack() }, modifier = m) }
            composable("new/screenshot") { ReviewPaymentScreen(CaptureMode.SCREENSHOT, onDone = { nav.popBackStack() }, modifier = m) }
        }
    }

    editingId?.let { id ->
        EditTransactionSheet(
            id = id,
            onDismiss = { editingId = null },
            onDeleted = { deleted ->
                scope.launch {
                    val result = snackbar.showSnackbar("Transaction deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) container.repository.restore(deleted)
                }
            },
        )
    }
}
