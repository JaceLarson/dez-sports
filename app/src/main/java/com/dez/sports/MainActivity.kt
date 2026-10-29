package com.dez.sports

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.dez.sports.widget.WidgetScheduler
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dez.sports.model.Game
import com.dez.sports.model.League
import com.dez.sports.ui.components.LocalOpenPlayer
import com.dez.sports.ui.game.GameScreen
import com.dez.sports.ui.player.PlayerScreen
import com.dez.sports.ui.stats.CompareScreen
import com.dez.sports.ui.stats.HeadToHeadScreen
import com.dez.sports.ui.stats.SimulatorScreen
import com.dez.sports.ui.stats.StatsHubScreen
import androidx.compose.material.icons.filled.QueryStats
import com.dez.sports.ui.golf.GolfScreen
import com.dez.sports.ui.projections.ProjectionsScreen
import com.dez.sports.ui.rankings.RankingsScreen
import com.dez.sports.ui.scores.ScoresScreen
import com.dez.sports.ui.standings.StandingsScreen
import com.dez.sports.ui.team.TeamScreen
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.DezTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        route.value = intent?.getStringExtra("route")
        setContent { DezTheme { DezNav(route.value) { route.value = null } } }
    }

    /** Route from a widget tap while the app is already open. */
    private val route = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("route")?.let { route.value = it }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { WidgetScheduler.refreshIfPlaced(applicationContext) }
    }
}

private enum class Root(val label: String, val icon: ImageVector) {
    SCORES("Scores", Icons.Filled.SportsScore),
    STANDINGS("Standings", Icons.Filled.FormatListNumbered),
    STATS("Stats", Icons.Filled.QueryStats),
    RANKINGS("Rankings", Icons.Filled.EmojiEvents),
    OUTLOOK("Outlook", Icons.Filled.Insights),
}

private const val ANIM = 320

private val optionalAB = listOf(
    androidx.navigation.navArgument("a") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null },
    androidx.navigation.navArgument("b") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null },
)

@Composable
fun DezNav(startRoute: String? = null, onRouteHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val openGame: (Game) -> Unit = { g -> nav.navigate("game/${g.league.name}/${g.id}") }
    val openTeam: (League, String) -> Unit = { l, id -> nav.navigate("team/${l.name}/$id") }
    LaunchedEffect(startRoute) { startRoute?.let { nav.navigate(it) { launchSingleTop = true }; onRouteHandled() } }
    val openPlayer: (League, String) -> Unit = { l, id -> nav.navigate("player/${l.name}/$id") }
    // navigationBarsPadding: keeps content clear of the gesture bar and the unfolded Pixel's taskbar dock.
    val openRoute: (String) -> Unit = { nav.navigate(it) }
    CompositionLocalProvider(LocalOpenPlayer provides openPlayer, com.dez.sports.ui.stats.LocalOpenRoute provides openRoute) {
    NavHost(
        nav, "home", Modifier.fillMaxSize().background(Dez.Bg).navigationBarsPadding(),
        enterTransition = { slideInHorizontally(tween(ANIM)) { it / 4 } + fadeIn(tween(ANIM)) },
        exitTransition = { slideOutHorizontally(tween(ANIM)) { -it / 8 } + fadeOut(tween(ANIM / 2)) },
        popEnterTransition = { slideInHorizontally(tween(ANIM)) { -it / 8 } + fadeIn(tween(ANIM)) },
        popExitTransition = { slideOutHorizontally(tween(ANIM)) { it / 4 } + fadeOut(tween(ANIM / 2)) },
    ) {
        composable("home") { Home(nav, openGame, openTeam) }
        composable("game/{league}/{id}") {
            GameScreen(onBack = { nav.popBackStack() }, onTeam = openTeam, onH2H = { l, a, b -> nav.navigate("h2h/${l.name}?a=$a&b=$b") })
        }
        composable("team/{league}/{id}") {
            TeamScreen(onBack = { nav.popBackStack() }, onGame = openGame, onH2H = { l, a -> nav.navigate("h2h/${l.name}?a=$a&b=") })
        }
        composable("settings") { com.dez.sports.ui.settings.SettingsScreen(onBack = { nav.popBackStack() }, onTeam = openTeam) }
        composable("golf/{id}") { GolfScreen(onBack = { nav.popBackStack() }) }
        composable("compare/{league}?a={a}&b={b}", arguments = optionalAB) { CompareScreen(onBack = { nav.popBackStack() }) }
        composable("h2h/{league}?a={a}&b={b}", arguments = optionalAB) { HeadToHeadScreen(onBack = { nav.popBackStack() }, onGame = { l, id -> nav.navigate("game/${l.name}/$id") }) }
        composable("sim/{league}") { SimulatorScreen(onBack = { nav.popBackStack() }) }
        composable("statlist/{league}?player={player}&title={title}&team={team}&hl={hl}", arguments = listOf("player", "title", "team", "hl").map {
            androidx.navigation.navArgument(it) { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }
        }) { com.dez.sports.ui.stats.StatListScreen(onBack = { nav.popBackStack() }, onTeam = openTeam) }
        composable("player/{league}/{id}") {
            PlayerScreen(onBack = { nav.popBackStack() }, onGame = { l, id -> nav.navigate("game/${l.name}/$id") }, onTeam = openTeam,
                onCompare = { l, id -> nav.navigate("compare/${l.name}?a=$id&b=") })
        }
    }
    }
}

@Composable
private fun Home(nav: NavHostController, openGame: (Game) -> Unit, openTeam: (League, String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Root.SCORES) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 700.dp
        val content: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(tab, m, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                when (t) {
                    Root.SCORES -> ScoresScreen(openGame, openTeam, onGolf = { nav.navigate("golf/${it.id}") })
                    Root.STANDINGS -> StandingsScreen(openTeam)
                    Root.STATS -> StatsHubScreen(
                        onTeam = openTeam,
                        onCompare = { l -> nav.navigate("compare/${l.name}?a=&b=") },
                        onH2H = { l -> nav.navigate("h2h/${l.name}?a=&b=") },
                        onSim = { l -> nav.navigate("sim/${l.name}") },
                    )
                    Root.RANKINGS -> RankingsScreen(openTeam)
                    Root.OUTLOOK -> ProjectionsScreen(openTeam, onSimulate = { l -> nav.navigate("sim/${l.name}") })
                }
            }
        }
        if (rail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = Dez.Bg, modifier = Modifier.statusBarsPadding()) {
                    Spacer(Modifier.height(12.dp))
                    Root.entries.forEach { r ->
                        NavigationRailItem(tab == r, { tab = r }, { Icon(r.icon, r.label) }, label = { Text(r.label) },
                            colors = NavigationRailItemDefaults.colors(selectedIconColor = Color.Black, indicatorColor = Dez.Text, unselectedIconColor = Dez.Sub, selectedTextColor = Dez.Text, unselectedTextColor = Dez.Sub))
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
                NavigationBar(containerColor = Color(0xFF0B0B0C), tonalElevation = 0.dp) {
                    Root.entries.forEach { r ->
                        NavigationBarItem(tab == r, { tab = r }, { Icon(r.icon, r.label) }, label = { Text(r.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Black, indicatorColor = Dez.Text, unselectedIconColor = Dez.Sub, selectedTextColor = Dez.Text, unselectedTextColor = Dez.Sub))
                    }
                }
            }
        }
    }
}
