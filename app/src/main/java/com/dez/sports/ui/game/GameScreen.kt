package com.dez.sports.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.model.*
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import kotlinx.coroutines.launch

@Composable
fun GameScreen(onBack: () -> Unit, onTeam: (League, String) -> Unit, onH2H: (League, String, String) -> Unit = { _, _, _ -> }, vm: GameViewModel = viewModel()) {
    CompositionLocalProvider(LocalOpenH2H provides onH2H) { GameScreenBody(onBack, onTeam, vm) }
}

/** Opens head-to-head history for (league, teamA, teamB). */
private val LocalOpenH2H = staticCompositionLocalOf<(League, String, String) -> Unit> { { _, _, _ -> } }

@Composable
private fun GameScreenBody(onBack: () -> Unit, onTeam: (League, String) -> Unit, vm: GameViewModel) {
    val s by vm.state.collectAsState()
    LifecycleResumeEffect(Unit) { vm.setVisible(true); onPauseOrDispose { vm.setVisible(false) } }
    val d = s.detail
    BoxWithConstraints(Modifier.fillMaxSize().background(Dez.Bg)) {
        val wide = maxWidth >= 700.dp
        when {
            d == null && s.error != null -> Column { BackBar(onBack); ErrorState("Couldn't load game", s.error, vm::retry) }
            d == null -> Column { BackBar(onBack); Box(Modifier.fillMaxWidth().height(260.dp).padding(16.dp).clip(RoundedCornerShape(28.dp)).shimmer()); SkeletonCards(3) }
            wide -> WideGame(d, onBack, onTeam)
            else -> CompactGame(d, onBack, onTeam)
        }
    }
}

@Composable
private fun BackBar(onBack: () -> Unit, tint: Color = Dez.Text) {
    Box(Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = tint)
    }
}

// ============================================================ hero

/** Pins this game's live score to the lock screen (asks for notification permission first). */
@Composable
private fun PinButton(g: Game) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pins by com.dez.sports.live.LivePins.pins.collectAsState()
    val pinned = "${g.league.name}:${g.id}" in pins
    var showTip by remember { mutableStateOf(false) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { com.dez.sports.live.LivePins.pin(context, g.league, g.id); showTip = true }
    }
    val tint by animateColorAsState(if (pinned) Dez.Gold else Color.White, label = "pin")
    Box(Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable {
        if (pinned) com.dez.sports.live.LivePins.unpin(context, g.league, g.id)
        else if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else { com.dez.sports.live.LivePins.pin(context, g.league, g.id); showTip = true }
    }, contentAlignment = Alignment.Center) {
        Icon(if (pinned) androidx.compose.material.icons.Icons.Filled.PushPin else androidx.compose.material.icons.Icons.Outlined.PushPin, "Pin live score to lock screen", tint = tint)
    }
    if (showTip) LaunchedEffect(Unit) {
        android.widget.Toast.makeText(context, "Live score pinned to your lock screen", android.widget.Toast.LENGTH_SHORT).show()
        showTip = false
    }
}

@Composable
private fun Hero(d: GameDetail, onBack: () -> Unit, onTeam: (League, String) -> Unit, big: Boolean) {
    val g = d.game
    val (awayC, homeC) = g.colors()
    Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(awayC.darken(.15f), awayC.darken(.55f), homeC.darken(.55f), homeC.darken(.15f))))) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Dez.Bg))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackBar(onBack, Color.White)
                Text(listOfNotNull(g.league.shortLabel, g.note ?: g.seriesSummary).joinToString("  ·  ").uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (g.state != GameState.POST) PinButton(g) else Spacer(Modifier.width(56.dp))
            }
            val logo = if (big) 84.dp else 64.dp
            val scoreSize = if (big) 64.sp else 52.sp
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroTeam(g, g.away, logo, Modifier.weight(1f)) { onTeam(g.league, g.away.team.id) }
                if (g.state != GameState.PRE) RollingScore(g.away.score ?: "0", ScoreStyle.copy(fontSize = scoreSize), scoreColor(g, g.away, g.home))
                Column(Modifier.width(if (big) 130.dp else 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (g.state) {
                        GameState.LIVE -> {
                            Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(5.dp); Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Dez.Live) }
                            Text(liveLabel(g), style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = Color.White, textAlign = TextAlign.Center)
                        }
                        GameState.PRE -> {
                            Text(localDay(g.dateIso), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .8f))
                            Text(localTime(g.dateIso), style = MaterialTheme.typography.titleLarge.merge(TabularNums), color = Color.White)
                        }
                        GameState.POST -> {
                            Text(g.statusShort.uppercase(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center)
                            Text(localDay(g.dateIso), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .55f))
                        }
                    }
                }
                if (g.state != GameState.PRE) RollingScore(g.home.score ?: "0", ScoreStyle.copy(fontSize = scoreSize), scoreColor(g, g.home, g.away))
                HeroTeam(g, g.home, logo, Modifier.weight(1f)) { onTeam(g.league, g.home.team.id) }
            }
            if (g.broadcasts.isNotEmpty() || g.odds != null) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(16.dp))
                    g.broadcasts.forEach { Pill("📺 $it", Color.White.copy(alpha = .14f), Color.White); Spacer(Modifier.width(6.dp)) }
                    g.odds?.takeIf { g.state == GameState.PRE }?.let { Pill(it, Color.White.copy(alpha = .08f), Color.White.copy(alpha = .75f)) }
                    Spacer(Modifier.width(16.dp))
                }
            }
            if (g.state == GameState.LIVE) LiveSituation(g)
        }
    }
}

/** One-line scoreboard (with the linescore inline) used above the unfolded box score. */
@Composable
private fun SlimHero(d: GameDetail, onBack: () -> Unit, onTeam: (League, String) -> Unit) {
    val g = d.game
    val (awayC, homeC) = g.colors()
    Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(awayC.darken(.25f), awayC.darken(.65f), homeC.darken(.65f), homeC.darken(.25f))))) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Dez.Bg.copy(alpha = .6f)))))
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 16.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BackBar(onBack, Color.White)
            @Composable fun side(s: Side, other: Side, leading: Boolean) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTeam(g.league, s.team.id) }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (leading) { TeamLogo(s.team, 34.dp); Spacer(Modifier.width(8.dp)) }
                    Column(horizontalAlignment = if (leading) Alignment.Start else Alignment.End) {
                        Text((s.rank?.let { "$it " } ?: "") + s.team.abbreviation, style = MaterialTheme.typography.titleSmall, color = Color.White)
                        s.record?.let { Text(it, style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Color.White.copy(alpha = .6f)) }
                    }
                    if (!leading) { Spacer(Modifier.width(8.dp)); TeamLogo(s.team, 34.dp) }
                }
                if (leading && g.state != GameState.PRE) RollingScore(s.score ?: "0", ScoreStyle.copy(fontSize = 34.sp), scoreColor(g, s, other))
            }
            side(g.away, g.home, true)
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (g.state == GameState.LIVE) Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(5.dp); Text(" " + liveLabel(g), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Color.White, maxLines = 1) }
                else Text(g.statusShort.uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .85f), maxLines = 1)
                Text(localDay(g.dateIso), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .55f))
            }
            if (g.state != GameState.PRE) RollingScore(g.home.score ?: "0", ScoreStyle.copy(fontSize = 34.sp), scoreColor(g, g.home, g.away))
            side(g.home, g.away, false)
            Spacer(Modifier.weight(1f))
            MiniLinescore(g)
        }
    }
}

@Composable
private fun MiniLinescore(g: Game) {
    val n = maxOf(g.away.linescores.size, g.home.linescores.size)
    if (n == 0) return
    val regular = when (g.league.kind) { SportKind.BASEBALL -> 9; SportKind.HOCKEY -> 3; else -> if (g.league == League.CBB) 2 else 4 }
    val cols = maxOf(n, if (g.league.kind == SportKind.BASEBALL) regular else n)
    val colW = if (cols > 6) 22.dp else 28.dp
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .3f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (i in 0 until cols) Column(Modifier.width(colW), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (i < regular || g.league.kind == SportKind.BASEBALL) "${i + 1}" else if (i == regular) "OT" else "${i - regular + 1}OT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .5f))
            listOf(g.away, g.home).forEach { s -> Text(s.linescores.getOrNull(i) ?: "–", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Color.White.copy(alpha = if (s.linescores.getOrNull(i) == null) .3f else .85f)) }
        }
    }
}

private fun scoreColor(g: Game, me: Side, other: Side) =
    if (g.state == GameState.POST && (me.score?.toDoubleOrNull() ?: 0.0) < (other.score?.toDoubleOrNull() ?: 0.0)) Color.White.copy(alpha = .5f) else Color.White

@Composable
private fun HeroTeam(g: Game, side: Side, logo: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(side.team, logo)
        Spacer(Modifier.height(6.dp))
        Text((side.rank?.let { "$it " } ?: "") + side.team.name.ifEmpty { side.team.abbreviation }, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        side.record?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Color.White.copy(alpha = .6f)) }
    }
}

@Composable
private fun LiveSituation(g: Game) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        g.football?.let { f ->
            FieldStrip(fieldPosition(g, f.spot)?.let { (it * 100).toInt() }, f.possessionTeamId?.let { it == g.away.team.id }, f.isRedZone, g.colors().first, g.colors().second, Modifier.padding(top = 6.dp))
            Text(f.downDistance ?: "", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(), textAlign = TextAlign.Center)
        }
        g.baseball?.let { b ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                BaseDiamond(b.onFirst, b.onSecond, b.onThird, 40.dp); Spacer(Modifier.width(14.dp))
                Column { CountDots("B", b.balls, 3, Dez.Win); CountDots("S", b.strikes, 2, Dez.Gold); CountDots("O", b.outs, 2, Dez.Live) }
                Spacer(Modifier.width(14.dp))
                Column { b.batter?.let { Text("AB  $it", style = MaterialTheme.typography.labelLarge, color = Color.White) }; b.pitcher?.let { Text("P  $it", style = MaterialTheme.typography.labelMedium, color = Dez.Sub) } }
            }
        }
        g.lastPlay?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .65f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), maxLines = 3) }
    }
}

// ============================================================ linescore

@Composable
fun Linescore(g: Game) {
    val n = maxOf(g.away.linescores.size, g.home.linescores.size)
    if (n == 0) return
    val regular = when (g.league.kind) { SportKind.BASEBALL -> 9; SportKind.HOCKEY -> 3; else -> if (g.league == League.CBB) 2 else 4 }
    val cols = maxOf(n, if (g.league.kind == SportKind.BASEBALL) regular else n)
    fun label(i: Int) = when {
        i < regular -> "${i + 1}"
        g.league.kind == SportKind.BASEBALL -> "${i + 1}"
        g.league.kind == SportKind.HOCKEY && i == regular + 1 -> "SO"
        else -> if (i == regular) "OT" else "${i - regular + 1}OT"
    }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Box(Modifier.height(26.dp))
                listOf(g.away, g.home).forEach { s ->
                    Row(Modifier.height(34.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(s.team, 20.dp); Spacer(Modifier.width(8.dp))
                        Text(s.team.abbreviation, style = MaterialTheme.typography.titleSmall, color = Dez.Text, modifier = Modifier.width(48.dp))
                    }
                }
            }
            for (i in 0 until cols) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                    Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { Text(label(i), style = MaterialTheme.typography.labelMedium, color = Dez.Sub) }
                    listOf(g.away, g.home).forEach { s ->
                        Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                            Text(s.linescores.getOrNull(i) ?: "–", style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = if (s.linescores.getOrNull(i) == null) Dez.Faint else Dez.Text.copy(alpha = .85f))
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp).padding(end = 6.dp)) {
                Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { Text(if (g.league.kind == SportKind.BASEBALL) "R" else "T", style = MaterialTheme.typography.labelMedium, color = Dez.Text) }
                listOf(g.away, g.home).forEach { s ->
                    Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) { Text(s.score ?: "–", style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = Dez.Text) }
                }
            }
        }
    }
}

// ============================================================ layouts

private enum class Tab(val label: String) { CAST("Gamecast"), BOX("Box"), STATS("Stats"), PLAYS("Plays"), INFO("Info"), PREVIEW("Preview") }

private fun hasCast(d: GameDetail) = d.game.league.kind != SportKind.GOLF && d.plays.isNotEmpty()

/** Live games open on the Gamecast; finished games open on the box score. */
private fun tabsFor(d: GameDetail) = when {
    d.game.state == GameState.PRE && d.boxes.isEmpty() -> listOf(Tab.PREVIEW, Tab.INFO)
    !hasCast(d) -> listOf(Tab.BOX, Tab.STATS, Tab.PLAYS, Tab.INFO)
    d.game.state == GameState.LIVE -> listOf(Tab.CAST, Tab.BOX, Tab.STATS, Tab.PLAYS, Tab.INFO)
    else -> listOf(Tab.BOX, Tab.CAST, Tab.STATS, Tab.PLAYS, Tab.INFO)
}

@Composable
private fun CompactGame(d: GameDetail, onBack: () -> Unit, onTeam: (League, String) -> Unit) {
    val tabs = tabsFor(d)
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    var boxTeam by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Hero(d, onBack, onTeam, big = false)
        TabRowPills(tabs.map { it.label }, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
        var arena by rememberSaveable { mutableStateOf(true) }
        val canArena = remember(d.plays.size, d.drives.size) { com.dez.sports.ui.game.arena.hasArena(d) }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            if (tabs[page] == Tab.CAST && canArena && arena) {
                com.dez.sports.ui.game.arena.ArenaCast(d, Modifier.fillMaxSize().clipToBounds(), onCharts = { arena = false })
                return@HorizontalPager
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tabs[page]) {
                    Tab.CAST -> {
                        if (canArena) item(key = "arena") {
                            Text("▶ ARENA VIEW", style = MaterialTheme.typography.labelMedium, color = Dez.Text,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Dez.Card2).clickable { arena = true }.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                        castContent(d)
                    }
                    Tab.BOX -> boxContent(d, boxTeam, { boxTeam = it }, wide = false)
                    Tab.STATS -> statsContent(d)
                    Tab.PLAYS -> playsContent(d)
                    Tab.INFO -> infoContent(d)
                    Tab.PREVIEW -> previewContent(d)
                }
            }
        }
    }
}

/**
 * Unfolded Pixel: Box Score gets the full inner screen so every stat column is visible;
 * Overview / Info split into two dense columns.
 */
@Composable
private fun WideGame(d: GameDetail, onBack: () -> Unit, onTeam: (League, String) -> Unit) {
    val pre = d.game.state == GameState.PRE && d.boxes.isEmpty()
    val opts = when {
        pre -> listOf("Preview", "Game Info")
        !hasCast(d) -> listOf("Box Score", "Overview", "Plays", "Game Info")
        d.game.state == GameState.LIVE -> listOf("Gamecast", "Box Score", "Overview", "Plays", "Game Info")
        else -> listOf("Box Score", "Gamecast", "Overview", "Plays", "Game Info")
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var boxTeam by rememberSaveable { mutableIntStateOf(0) }
    val sel = opts[tab.coerceAtMost(opts.lastIndex)]
    Column(Modifier.fillMaxSize()) {
        // The box score trades the big hero for a one-line scoreboard so the whole table fits.
        var arena by rememberSaveable { mutableStateOf(true) }
        val showArena = sel == "Gamecast" && arena && remember(d.plays.size, d.drives.size) { com.dez.sports.ui.game.arena.hasArena(d) }
        androidx.compose.animation.AnimatedContent(sel == "Box Score" || showArena, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "hero") { slim ->
            if (slim) SlimHero(d, onBack, onTeam) else Hero(d, onBack, onTeam, big = true)
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TabRowPills(opts, tab.coerceAtMost(opts.lastIndex)) { tab = it } }
            // On the box score the team switch shares the tab row to give the table more height.
            androidx.compose.animation.AnimatedVisibility(sel == "Box Score" && d.boxes.isNotEmpty()) {
                TeamSwitch(d.game, boxTeam, { boxTeam = it }, Modifier.width(330.dp).padding(end = 12.dp), dense = true)
            }
        }
        androidx.compose.animation.AnimatedContent(sel, Modifier.weight(1f), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "wide") { t ->
            when (t) {
                "Box Score" -> if (d.boxes.isEmpty()) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) { boxContent(d, boxTeam, { boxTeam = it }, wide = true) }
                    else Box(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp)) { WideBoxScore(d.game, d.boxes, boxTeam) }
                "Plays" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { playsContent(d) }
                "Gamecast" -> if (showArena) com.dez.sports.ui.game.arena.ArenaCast(d, onCharts = { arena = false }) else Column {
                    if (!arena) Text("▶ ARENA VIEW", style = MaterialTheme.typography.labelMedium, color = Dez.Text,
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp).clip(RoundedCornerShape(50)).background(Dez.Card2).clickable { arena = true }.padding(horizontal = 12.dp, vertical = 5.dp))
                    TwoColumns({ item(key = "tracker") { Tracker(d) }; recentPlays(d) }, { item(key = "upset") { rememberUpset(d.game)?.let { UpsetCard(it, d) } }; item(key = "mom") { MomentumCard(d) }; if (d.winProb.size > 2) item(key = "wp") { CardBox { WinProbabilityChart(d) } } }) }
                "Overview" -> TwoColumns({ statsLeft(d) }, { statsRight(d) })
                "Preview" -> TwoColumns({ previewContent(d) }, { infoContent(d) })
                else -> TwoColumns({ infoContent(d, includeRecap = true, includeExtras = false) }, { extrasContent(d) })
            }
        }
    }
}

@Composable
private fun TwoColumns(left: LazyListScope.() -> Unit, right: LazyListScope.() -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
    }
}

@Composable
private fun TabRowPills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Dez.Card).padding(4.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Dez.Card2 else Color.Transparent, tween(220), label = "tab")
            val fg by animateColorAsState(if (sel) Dez.Text else Dez.Sub, tween(220), label = "tabfg")
            Text(l, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp))
        }
    }
}

// ============================================================ tab content

private fun LazyListScope.boxContent(d: GameDetail, team: Int, onTeam: (Int) -> Unit, wide: Boolean) {
    item(key = "ls") { Linescore(d.game) }
    if (d.boxes.isEmpty()) { item { EmptyState("Box score not available yet") }; return }
    item(key = "switch") { TeamSwitch(d.game, team, onTeam) }
    val box = d.boxes.getOrNull(team) ?: return
    items(prepareTables(box.tables, d.game.league), key = { "t-${box.team.id}-${it.title}" }) { StatTableView(it, box.team, d.game.league, wide) }
}

private fun LazyListScope.statsContent(d: GameDetail) {
    item(key = "upset") { rememberUpset(d.game)?.let { UpsetCard(it, d) } }
    if (d.game.state == GameState.POST) item(key = "dez") { DezScoreCard(d.game) }
    if (d.winProb.size > 2) item(key = "wp") { CardBox { WinProbabilityChart(d) } }
    if (d.shots.size > 4) item(key = "shots") { CardBox { Column { Text("SHOT CHART", style = MaterialTheme.typography.labelMedium, color = Dez.Sub); Spacer(Modifier.height(8.dp)); ShotChart(d) } } }
    if (d.game.league.kind == SportKind.HOCKEY && d.plays.any { it.rink != null }) item(key = "rink") { RinkTracker(d) }
    if (d.leaders.isNotEmpty()) item(key = "leaders") { LeadersCard(d) }
    if (d.teamStats.isNotEmpty()) item(key = "ts") { TeamStatsCompare(d.game, d.teamStats) }
}

private fun LazyListScope.castContent(d: GameDetail) {
    item(key = "upset") { rememberUpset(d.game)?.let { UpsetCard(it, d) } }
    item(key = "tracker") { Tracker(d) }
    item(key = "mom") { MomentumCard(d) }
    recentPlays(d)
}

private fun LazyListScope.recentPlays(d: GameDetail) {
    val recent = d.plays.filter { it.text.isNotBlank() && it.type != "End Period" }.takeLast(8).reversed()
    if (recent.isEmpty()) return
    item(key = "rph") { Text(if (d.game.state == GameState.LIVE) "LATEST" else "FINAL PLAYS", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 6.dp)) }
    items(recent, key = { "rp-${it.hashCode()}-${it.clock}" }) { PlayRow(it, d.game, emphasize = it.scoring) }
}

private fun LazyListScope.statsLeft(d: GameDetail) {
    item(key = "ls") { Linescore(d.game) }
    item(key = "upset") { rememberUpset(d.game)?.let { UpsetCard(it, d) } }
    if (d.game.state == GameState.POST) item(key = "dez") { DezScoreCard(d.game) }
    if (d.winProb.size > 2) item(key = "wp") { CardBox { WinProbabilityChart(d) } }
    if (d.shots.size > 4) item(key = "shots") { CardBox { Column { Text("SHOT CHART", style = MaterialTheme.typography.labelMedium, color = Dez.Sub); Spacer(Modifier.height(8.dp)); ShotChart(d) } } }
    if (d.game.league.kind == SportKind.HOCKEY && d.plays.any { it.rink != null }) item(key = "rink") { RinkTracker(d) }
    if (d.leaders.isNotEmpty()) item(key = "leaders") { LeadersCard(d) }
}

private fun LazyListScope.statsRight(d: GameDetail) {
    if (d.teamStats.isNotEmpty()) item(key = "ts") { TeamStatsCompare(d.game, d.teamStats) }
    if (d.scoringPlays.isNotEmpty()) {
        item(key = "sph") { Text("SCORING", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 6.dp)) }
        items(d.scoringPlays.reversed().take(40), key = { "sp-${it.period}-${it.clock}-${it.text.hashCode()}" }) { PlayRow(it, d.game, emphasize = true) }
    }
}

private fun LazyListScope.playsContent(d: GameDetail) {
    if (d.game.league.kind == SportKind.FOOTBALL && d.drives.isNotEmpty()) {
        if (d.scoringPlays.isNotEmpty()) {
            item(key = "sph") { SectionTitle("Scoring Summary") }
            items(d.scoringPlays.reversed(), key = { "sp-${it.period}-${it.clock}-${it.text.hashCode()}" }) { PlayRow(it, d.game, emphasize = true) }
        }
        item(key = "dh") { SectionTitle("Drives") }
        items(d.drives.reversed(), key = { "dr-${it.description}-${it.plays.firstOrNull()?.text.hashCode()}" }) { DriveCard(it, d.game) }
        return
    }
    if (d.plays.isEmpty()) { item { EmptyState("No plays yet") }; return }
    item(key = "filter") { PlayFilterHeader() }
    val list = d.plays.reversed().filter { !PlayFilter.scoringOnly || it.scoring }
    var lastPeriod: String? = null
    list.forEach { p ->
        if (p.period != lastPeriod) {
            val per = p.period; lastPeriod = per
            item(key = "per-$per-${p.hashCode()}") { Text(per.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
        }
        item(key = "p-${p.hashCode()}-${p.clock}") { PlayRow(p, d.game, emphasize = p.scoring) }
    }
}

private object PlayFilter { var scoringOnly by mutableStateOf(false) }

@Composable
private fun PlayFilterHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Play-by-Play", style = MaterialTheme.typography.titleLarge, color = Dez.Text, modifier = Modifier.weight(1f))
        Pill(if (PlayFilter.scoringOnly) "SCORING ONLY ✓" else "ALL PLAYS", if (PlayFilter.scoringOnly) Dez.Accent.copy(alpha = .25f) else Dez.Card2, if (PlayFilter.scoringOnly) Dez.Accent else Dez.Sub,
            Modifier.clickable { PlayFilter.scoringOnly = !PlayFilter.scoringOnly })
    }
}

@Composable
private fun PlayRow(p: Play, g: Game, emphasize: Boolean) {
    val team = when (p.teamId) { g.away.team.id -> g.away; g.home.team.id -> g.home; else -> null }
    val c = team?.let { g.colorOf(it) } ?: Dez.Faint
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (emphasize) Dez.Card else Color.Transparent).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(if (emphasize) c else Dez.Hairline))
        Spacer(Modifier.width(10.dp))
        Text(p.clock ?: "", style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(44.dp))
        team?.let { TeamLogo(it.team, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(p.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal), color = if (emphasize) Dez.Text else Dez.Text.copy(alpha = .8f), modifier = Modifier.weight(1f))
        if (emphasize && p.awayScore != null && p.homeScore != null) {
            Spacer(Modifier.width(8.dp))
            Text("${p.awayScore}-${p.homeScore}", style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
        }
    }
}

@Composable
private fun DriveCard(dr: Drive, g: Game) {
    var open by remember { mutableStateOf(false) }
    val team = if (dr.teamId == g.home.team.id) g.home else g.away
    val score = dr.result.contains("Touchdown", true) || dr.result.contains("Field Goal", true) && !dr.result.contains("Missed", true)
    val rot by animateDpAsState(if (open) 180.dp else 0.dp, spring(), label = "rot")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dez.Card).clickable { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(team.team, 26.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(dr.result.ifEmpty { "Drive" }, style = MaterialTheme.typography.titleSmall, color = if (score) Dez.Win else Dez.Text)
                Text(dr.description, style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            }
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Dez.Sub, modifier = Modifier.rotate(rot.value))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp)) {
                dr.plays.forEach { p ->
                    HorizontalDivider(color = Dez.Hairline)
                    Text(p.text, style = MaterialTheme.typography.bodySmall, color = Dez.Text.copy(alpha = .8f), modifier = Modifier.padding(vertical = 7.dp))
                }
            }
        }
    }
}

private fun LazyListScope.infoContent(d: GameDetail, includeRecap: Boolean = true, includeExtras: Boolean = true) {
    if (includeRecap && d.recapHeadline != null) item(key = "recap") {
        CardBox {
            Column {
                Text("RECAP", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                Text(d.recapHeadline, style = MaterialTheme.typography.titleLarge, color = Dez.Text, modifier = Modifier.padding(vertical = 6.dp))
                var more by remember { mutableStateOf(false) }
                d.recapBody?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Dez.Text.copy(alpha = .8f), maxLines = if (more) 100 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { more = !more }) }
            }
        }
    }
    item(key = "h2h") { H2HLink(d.game) }
    item(key = "venue") { VenueCard(d) }
    if (includeExtras) extrasContent(d)
}

private fun LazyListScope.extrasContent(d: GameDetail) {
    if (d.game.league == League.NHL && d.game.state == GameState.LIVE) item(key = "lines") { LinesCard(d.game) }
    if (d.oddsDetail.isNotEmpty()) item(key = "odds") { KeyValueCard("BETTING", d.oddsDetail) }
    d.seasonSeries?.let { item(key = "series") { KeyValueCard("SEASON SERIES", listOf("Series" to it)) } }
    if (d.injuries.isNotEmpty()) item(key = "inj") { InjuriesCard(d) }
}

private fun LazyListScope.previewContent(d: GameDetail) {
    if (d.game.state == GameState.PRE) item(key = "watch") { WatchScoreCard(d.game) }
    d.pregameWinPct?.let { (a, h) -> item(key = "pred") { MatchupPredictor(d.game, a, h) } }
    if (d.game.league == League.NHL) item(key = "lines") { LinesCard(d.game) }
    if (d.leaders.isNotEmpty()) item(key = "leaders") { LeadersCard(d, title = "SEASON LEADERS") }
    if (d.oddsDetail.isNotEmpty()) item(key = "odds") { KeyValueCard("BETTING", d.oddsDetail) }
    d.seasonSeries?.let { item(key = "series") { KeyValueCard("SEASON SERIES", listOf("Series" to it)) } }
    if (d.injuries.isNotEmpty()) item(key = "inj") { InjuriesCard(d) }
    item(key = "venue") { VenueCard(d) }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) { content() }
}

@Composable
private fun MatchupPredictor(g: Game, away: Float, home: Float) {
    CardBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("MATCHUP PREDICTOR", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                listOf(g.away to away, g.home to home).forEach { (s, p) ->
                    RingProgress(p / 100f, g.colorOf(s), 96.dp, 8.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TeamLogo(s.team, 30.dp)
                            Text("%.1f%%".format(p), style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadersCard(d: GameDetail, title: String = "GAME LEADERS") {
    val g = d.game
    val cats = d.leaders.map { it.category }.distinct().take(4)
    CardBox {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            cats.forEach { cat ->
                Text(cat, style = MaterialTheme.typography.labelLarge, color = Dez.Text.copy(alpha = .9f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(g.away, g.home).forEach { side ->
                        val l = d.leaders.firstOrNull { it.category == cat && it.teamId == side.team.id }
                        val open = LocalOpenPlayer.current
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(g.colorOf(side).copy(alpha = .14f))
                            .then(if (open != null && l?.athleteId != null) Modifier.clickable { open(g.league, l.athleteId) } else Modifier)
                            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (l?.headshot != null) AsyncImage(l.headshot, null, Modifier.size(38.dp).clip(CircleShape).background(Dez.Card2), contentScale = ContentScale.Crop)
                            else TeamLogo(side.team, 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(l?.player ?: "—", style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(l?.line ?: "", style = MaterialTheme.typography.bodySmall, color = Dez.Sub, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun H2HLink(g: Game) {
    val open = LocalOpenH2H.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).clickable { open(g.league, g.away.team.id, g.home.team.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(g.away.team, 26.dp); Text(" vs ", style = MaterialTheme.typography.labelMedium, color = Dez.Sub); TeamLogo(g.home.team, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Head-to-head history", style = MaterialTheme.typography.titleSmall, color = Dez.Text)
            Text("Every meeting, streaks and margins", style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = Dez.Sub)
    }
}

@Composable
private fun VenueCard(d: GameDetail) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card)) {
        d.venueImage?.let {
            Box {
                AsyncImage(it, null, Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Dez.Card))))
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text(d.venue ?: d.game.venue ?: "Venue TBA", style = MaterialTheme.typography.titleMedium, color = Dez.Text)
            d.venueCity?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dez.Sub) }
            val rows = buildList {
                add("Date" to "${localDay(d.game.dateIso)} · ${localTime(d.game.dateIso)}")
                if (d.game.broadcasts.isNotEmpty()) add("TV" to d.game.broadcasts.joinToString(", "))
                d.attendance?.let { add("Attendance" to "%,d".format(it)) }
                if (d.officials.isNotEmpty()) add("Officials" to d.officials.joinToString(", "))
            }
            rows.forEach { (k, v) ->
                Row(Modifier.padding(top = 8.dp)) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.width(92.dp))
                    Text(v, style = MaterialTheme.typography.bodySmall, color = Dez.Text, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KeyValueCard(title: String, rows: List<Pair<String, String>>) {
    CardBox {
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            rows.forEach { (k, v) ->
                Row(Modifier.padding(top = 8.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = Dez.Text)
                }
            }
        }
    }
}

@Composable
private fun InjuriesCard(d: GameDetail) {
    CardBox {
        Column {
            Text("INJURY REPORT", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            listOf(d.game.away, d.game.home).forEach { side ->
                val list = d.injuries.filter { it.teamId == side.team.id }
                if (list.isEmpty()) return@forEach
                Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) { TeamLogo(side.team, 18.dp); Spacer(Modifier.width(6.dp)); Text(side.team.displayName, style = MaterialTheme.typography.labelLarge, color = Dez.Text) }
                list.take(10).forEach { i ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(i.player, style = MaterialTheme.typography.bodySmall, color = Dez.Text, modifier = Modifier.weight(1f))
                        Text(listOfNotNull(i.detail, i.status).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (i.status.equals("Out", true)) Dez.Live else Dez.Gold)
                    }
                }
            }
        }
    }
}
