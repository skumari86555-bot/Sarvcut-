package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.StudioPreferences
import com.example.core.authStateFlow
import com.example.core.configureAppCheck
import com.example.data.repository.FirestoreRepository
import com.example.pipeline.MediaExportHelper
import com.example.pipeline.VideoPipelineCoordinator
import com.example.ui.components.StudioBackdrop
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CreateStudioScreen
import com.example.ui.screens.ProjectDetailScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.ResearchReportsScreen
import com.example.ui.screens.SettingsAndAdminScreen
import com.example.ui.screens.UsageAndBudgetScreen
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.SarvcutTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.VioletBright
import com.example.ui.theme.VioletDeep
import com.example.viewmodel.StudioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private const val ROUTE_CREATE = "create"
private const val ROUTE_PROJECTS = "projects"
private const val ROUTE_RESEARCH = "research"
private const val ROUTE_USAGE = "usage"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_PROJECT_DETAIL = "project_detail/{projectId}"

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val NAV_TABS = listOf(
    NavTab(ROUTE_CREATE, "Create", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavTab(ROUTE_PROJECTS, "Projects", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    NavTab(ROUTE_RESEARCH, "Research", Icons.Filled.TravelExplore, Icons.Outlined.TravelExplore),
    NavTab(ROUTE_USAGE, "Usage", Icons.Filled.Insights, Icons.Outlined.Insights),
    NavTab(ROUTE_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            SarvcutTheme {
                SarvcutRoot()
            }
        }
    }
}

@Composable
fun SarvcutRoot() {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            SarvcutMainShell(
                currentUserId = user.uid,
                currentUserEmail = user.email.orEmpty()
            )
        }
    }
}

@Composable
fun SarvcutMainShell(
    currentUserId: String,
    currentUserEmail: String
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val viewModel: StudioViewModel = viewModel(
        key = currentUserId,
        factory = remember(currentUserId) {
            val dbId = appContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            val repo = FirestoreRepository(db)
            val coordinator = VideoPipelineCoordinator(appContext, repo)
            val prefs = StudioPreferences(appContext)
            val exportHelper = MediaExportHelper(appContext)
            StudioViewModel.provideFactory(
                repository = repo,
                coordinator = coordinator,
                preferences = prefs,
                exportHelper = exportHelper,
                currentUserId = currentUserId,
                currentUserEmail = currentUserEmail
            )
        }
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showTopLevelNav = currentRoute in NAV_TABS.map { it.route }

    val snackbarHostState = remember { SnackbarHostState() }
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    StudioBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useRail && showTopLevelNav) {
                        NavigationBar(
                            containerColor = CarbonSurface,
                            tonalElevation = 4.dp
                        ) {
                            NAV_TABS.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = VioletBright,
                                        selectedTextColor = VioletBright,
                                        indicatorColor = VioletDeep,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    ),
                                    modifier = Modifier.testTag("nav_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail && showTopLevelNav) {
                        NavigationRail(containerColor = CarbonSurface) {
                            NAV_TABS.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = VioletBright,
                                        selectedTextColor = VioletBright,
                                        indicatorColor = VioletDeep,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_CREATE,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(ROUTE_CREATE) {
                            CreateStudioScreen(
                                viewModel = viewModel,
                                onProjectStarted = { pid ->
                                    viewModel.selectProject(pid)
                                    navController.navigate("project_detail/$pid")
                                }
                            )
                        }
                        composable(ROUTE_PROJECTS) {
                            ProjectsScreen(
                                viewModel = viewModel,
                                onOpenProject = { pid ->
                                    viewModel.selectProject(pid)
                                    navController.navigate("project_detail/$pid")
                                }
                            )
                        }
                        composable(ROUTE_RESEARCH) {
                            ResearchReportsScreen(viewModel = viewModel)
                        }
                        composable(ROUTE_USAGE) {
                            UsageAndBudgetScreen(viewModel = viewModel)
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsAndAdminScreen(viewModel = viewModel)
                        }
                        composable(
                            route = ROUTE_PROJECT_DETAIL,
                            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                        ) { entry ->
                            val pid = entry.arguments?.getString("projectId")
                            LaunchedEffect(pid) {
                                if (pid != null) viewModel.selectProject(pid)
                            }
                            ProjectDetailScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
