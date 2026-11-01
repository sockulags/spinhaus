package se.spinhaus.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.components.CenteredMessage
import se.spinhaus.app.ui.screens.ActivityScreen
import se.spinhaus.app.ui.screens.ButtonsScreen
import se.spinhaus.app.ui.screens.DashboardEditorScreen
import se.spinhaus.app.ui.screens.DevicesScreen
import se.spinhaus.app.ui.screens.DiagnosticsScreen
import se.spinhaus.app.ui.screens.EditorScreen
import se.spinhaus.app.ui.screens.FreeRideSetupScreen
import se.spinhaus.app.ui.screens.HistoryScreen
import se.spinhaus.app.ui.screens.HomeScreen
import se.spinhaus.app.ui.screens.LibraryScreen
import se.spinhaus.app.ui.screens.ProfileEditScreen
import se.spinhaus.app.ui.screens.ProfilePickerScreen
import se.spinhaus.app.ui.screens.RideScreen
import se.spinhaus.app.ui.screens.SettingsScreen
import se.spinhaus.app.ui.screens.TrendsScreen
import se.spinhaus.app.ui.screens.WorkoutDetailScreen
import se.spinhaus.core.ride.RideMode

object Routes {
    const val PROFILES = "profiles"
    const val PROFILE_EDIT = "profile/{id}" // id 0 = new
    const val HOME = "home"
    const val DEVICES = "devices"
    const val LIBRARY = "library?cat={cat}"
    const val WORKOUT = "workout/{id}"
    const val EDITOR = "editor/{id}" // id "new" = new workout
    const val FREE = "free"
    const val RIDE = "ride"
    const val ACTIVITY = "activity/{id}?testFtp={testFtp}&estFtp={estFtp}&fresh={fresh}"
    const val HISTORY = "history"
    const val TRENDS = "trends"
    const val SETTINGS = "settings"
    const val DASHBOARD = "dashboard"
    const val BUTTONS = "buttons"
    const val DIAGNOSTICS = "diagnostics"

    fun profileEdit(id: Long) = "profile/$id"
    fun library(category: String? = null) = if (category == null) "library" else "library?cat=$category"
    fun workout(id: String) = "workout/${android.net.Uri.encode(id)}"
    fun editor(id: String?) = "editor/${android.net.Uri.encode(id ?: "new")}"
    fun activity(id: Long, testFtp: Int? = null, estFtp: Int? = null, fresh: Boolean = false) =
        "activity/$id?testFtp=${testFtp ?: -1}&estFtp=${estFtp ?: -1}&fresh=$fresh"
}

class Nav(val controller: NavHostController, val app: AppContainer) {
    fun go(route: String) = controller.navigate(route) { launchSingleTop = true }
    fun back() { if (!controller.popBackStack()) controller.navigate(Routes.HOME) }
    fun home() = controller.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true }; launchSingleTop = true }

    /** Starts a ride and opens the ride screen. type: WORKOUT, FREE or TEST */
    fun startRide(profile: ProfileEntity, mode: RideMode, title: String, type: String) {
        app.scope.launch {
            if (app.ride.start(profile, mode, title, type) || app.ride.active) {
                controller.navigate(Routes.RIDE) { launchSingleTop = true }
            }
        }
    }

    /** After a ride ends: show the activity, replacing the ride screen. */
    fun showFinished(activityId: Long, testFtp: Int?, estFtp: Int?) {
        controller.navigate(Routes.activity(activityId, testFtp, estFtp, fresh = true)) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }
}

@Composable
fun SpinhausNavHost(app: AppContainer) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller, app) }
    val profileId by app.currentProfileId.collectAsState()
    val profileFlow = remember(profileId) { profileId?.let { app.profiles.observe(it) } ?: flowOf(null) }
    val profile by profileFlow.collectAsState(initial = null)
    val rideSession by app.ride.session.collectAsState()

    LaunchedEffect(profileId) {
        if (profileId == null && controller.currentDestination?.route != Routes.PROFILES) {
            controller.navigate(Routes.PROFILES) { popUpTo(0) }
        }
    }

    CompositionLocalProvider(LocalLanguage provides (profile?.language ?: "sv")) {
        // Computed once: changing startDestination would rebuild the graph and wipe the back stack.
        val start = remember { if (profileId == null) Routes.PROFILES else if (rideSession != null) Routes.RIDE else Routes.HOME }
        NavHost(controller, startDestination = start) {
            composable(Routes.PROFILES) { ProfilePickerScreen(app, nav) }
            composable(Routes.PROFILE_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ProfileEditScreen(app, nav, it.arguments?.getLong("id") ?: 0L)
            }
            composable(Routes.HOME) { WithProfile(profile) { p -> HomeScreen(app, nav, p) } }
            composable(Routes.DEVICES) { WithProfile(profile) { p -> DevicesScreen(app, nav, p) } }
            composable(Routes.LIBRARY, arguments = listOf(navArgument("cat") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                WithProfile(profile) { p -> LibraryScreen(app, nav, p, it.arguments?.getString("cat")) }
            }
            composable(Routes.WORKOUT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                WithProfile(profile) { p -> WorkoutDetailScreen(app, nav, p, it.arguments?.getString("id") ?: "") }
            }
            composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments?.getString("id")
                WithProfile(profile) { p -> EditorScreen(app, nav, p, if (id == "new") null else id) }
            }
            composable(Routes.FREE) { WithProfile(profile) { p -> FreeRideSetupScreen(app, nav, p) } }
            composable(Routes.RIDE) { WithProfile(profile) { p -> RideScreen(app, nav, p) } }
            composable(
                Routes.ACTIVITY,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("testFtp") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("estFtp") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("fresh") { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                val a = it.arguments
                WithProfile(profile) { p ->
                    ActivityScreen(
                        app, nav, p, a?.getLong("id") ?: 0L,
                        testFtp = a?.getInt("testFtp")?.takeIf { v -> v > 0 },
                        estimatedFtp = a?.getInt("estFtp")?.takeIf { v -> v > 0 },
                        justFinished = a?.getBoolean("fresh") ?: false,
                    )
                }
            }
            composable(Routes.HISTORY) { WithProfile(profile) { p -> HistoryScreen(app, nav, p) } }
            composable(Routes.TRENDS) { WithProfile(profile) { p -> TrendsScreen(app, nav, p) } }
            composable(Routes.SETTINGS) { WithProfile(profile) { p -> SettingsScreen(app, nav, p) } }
            composable(Routes.DASHBOARD) { WithProfile(profile) { p -> DashboardEditorScreen(app, nav, p) } }
            composable(Routes.BUTTONS) { WithProfile(profile) { p -> ButtonsScreen(app, nav, p) } }
            composable(Routes.DIAGNOSTICS) { WithProfile(profile) { p -> DiagnosticsScreen(app, nav, p) } }
        }
    }
}

@Composable
private fun WithProfile(profile: ProfileEntity?, content: @Composable (ProfileEntity) -> Unit) {
    if (profile == null) CenteredMessage(t("Loading…", "Laddar…")) else content(profile)
}
