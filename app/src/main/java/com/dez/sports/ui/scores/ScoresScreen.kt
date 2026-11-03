package com.dez.sports.ui.scores

import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.Conference
import com.dez.sports.data.Conferences
import com.dez.sports.data.FavoriteCard
import com.dez.sports.data.ForYou
import com.dez.sports.model.*
import com.dez.sports.ui.components.*
import com.dez.sports.ui.golf.GolfCard
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.lighten
import com.dez.sports.ui.theme.teamColor
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Widgets
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen(
    onGame: (Game) -> Unit,
    onTeam: (League, String) -> Unit,
    onGolf: (GolfEvent) -> Unit,
    vm: ScoresViewModel = viewModel(),
) {
    val s by vm.state.collectAsState()
    LifecycleResumeEffect(Unit) { vm.setVisible(true); onPauseOrDispose { vm.setVisible(false) } }

    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        Header(s)
        LeagueChips(s.league, vm::selectLeague)
        val league = s.league
        val filters by com.dez.sports.data.Favorites.scoreFilter.collectAsState()
        val filter = league?.takeIf { Conferences.filterable(it) }?.let { filters[it] } ?: Conferences.ALL
        if (league != null && league != League.PGA) {
            if (league.weekly) WeekStrip(s.board, s.week, vm::selectWeek) else DateStrip(s.date, vm::selectDate)
            val compact by com.dez.sports.data.Favorites.compactScores.collectAsState()
            val all = s.board?.games.orEmpty()
            if (Conferences.filterable(league) && all.isNotEmpty()) ConferenceChips(league, all, filter)
            val games = all.filter { Conferences.matches(it, filter) }
            val live = games.count { it.state == GameState.LIVE }
            ViewToggle(compact ?: com.dez.sports.data.Favorites.compact(), com.dez.sports.data.Favorites::setCompact,
                if (all.isEmpty()) "" else (if (games.size < all.size) "${games.size} of ${all.size} games" else "${all.size} games") + if (live > 0) " · $live live" else "")
        }
        val feed = s.feed
        if (league == null && feed != null) {
            val compact by com.dez.sports.data.Favorites.compactScores.collectAsState()
            val live = feed.live.size + feed.tv.big.count { it.game.state == GameState.LIVE }
            ViewToggle(compact ?: com.dez.sports.data.Favorites.compact(), com.dez.sports.data.Favorites::setCompact,
                "${feed.favorites.size} teams" + if (live > 0) " · $live live" else "")
        }
        PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            AnimatedContent(targetState = Triple(s.league, s.loading && s.board == null && s.feed == null, s.date to s.week),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "board") { (lg, loading, _) ->
                when {
                    loading -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) { SkeletonCards() }
                    lg == null -> ForYouFeed(s.feed, onGame, onTeam, onGolf)
                    else -> LeagueBoard(lg, s, filter, onGame, onGolf)
                }
            }
        }
    }
}

@Composable
private fun Header(s: ScoresState) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            Text(if (s.league == null) "Dez Sports" else s.league.label, style = MaterialTheme.typography.headlineLarge, color = Dez.Text)
        }
        val liveCount = s.feed?.live?.size?.takeIf { s.league == null } ?: s.board?.games?.count { it.state == GameState.LIVE } ?: 0
        if (liveCount > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            LiveDot(5.dp); Text("$liveCount LIVE", style = MaterialTheme.typography.labelMedium, color = Dez.Live)
        }
        if (s.league == null) AddWidgetButton()
        val open = com.dez.sports.ui.stats.LocalOpenRoute.current
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Settings, "Settings", tint = Dez.Sub,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).size(40.dp).clip(RoundedCornerShape(50)).background(Dez.Card2).clickable { open("settings") }.padding(9.dp),
        )
    }
}

@Composable
private fun LeagueChips(selected: League?, onSelect: (League?) -> Unit) {
    val items = listOf<League?>(null) + League.entries
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { l ->
            val sel = l == selected
            val bg by animateColorAsState(if (sel) Dez.Text else Dez.Card2, tween(250), label = "chip")
            val fg by animateColorAsState(if (sel) Color.Black else Dez.Text, tween(250), label = "chipfg")
            Text(l?.shortLabel ?: "For You", style = MaterialTheme.typography.labelLarge, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(l) }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun DateStrip(date: LocalDate, onSelect: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val days = (-7..10).map { today.plusDays(it.toLong()) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(date) - 2).coerceAtLeast(0))
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        items(days) { d ->
            val sel = d == date
            val bg by animateColorAsState(if (sel) Dez.Card2 else Color.Transparent, tween(250), label = "day")
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { onSelect(d) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (d) { today -> "TODAY"; today.minusDays(1) -> "YEST"; today.plusDays(1) -> "TMRW"; else -> d.format(DateTimeFormatter.ofPattern("EEE")).uppercase() },
                    style = MaterialTheme.typography.labelSmall, color = if (sel) Dez.Text else Dez.Sub)
                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = if (sel) Dez.Text else Dez.Sub)
            }
        }
    }
}

@Composable
private fun WeekStrip(board: Scoreboard?, selected: WeekOption?, onSelect: (WeekOption) -> Unit) {
    val weeks = board?.weeks ?: return
    val cur = selected?.key ?: board.currentWeekKey
    val idx = weeks.indexOfFirst { it.key == cur }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 1).coerceAtLeast(0))
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        items(weeks) { w ->
            val sel = w.key == cur
            val bg by animateColorAsState(if (sel) Dez.Card2 else Color.Transparent, tween(250), label = "wk")
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { onSelect(w) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(w.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (sel) Dez.Text else Dez.Sub, maxLines = 1)
                Text(w.detail, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, maxLines = 1)
            }
        }
    }
}

/** All · Top 25 · your conference first, then every conference playing on this slate, with game counts. */
@Composable
private fun ConferenceChips(league: League, games: List<Game>, selected: String) {
    val confs by produceState(emptyList<Conference>(), league) {
        value = withContext(Dispatchers.Default) { runCatching { Conferences.of(league) }.getOrDefault(emptyList()) }
    }
    val favs by com.dez.sports.data.Favorites.teams.collectAsState()
    val chips = remember(confs, games, favs, selected) {
        val mine = confs.filter { c -> favs.any { it.league == league && it.teamId in c.teamIds } }.map { it.id }.toSet()
        val count = confs.associate { c -> c.id to games.count { it.away.conferenceId == c.id || it.home.conferenceId == c.id } }
        listOf(Triple(Conferences.ALL, "All", games.size), Triple(Conferences.TOP25, "Top 25", games.count { it.away.rank != null || it.home.rank != null })) +
            confs.filter { (count[it.id] ?: 0) > 0 || it.id == selected }.sortedWith(compareBy({ it.id !in mine }, { it.short }))
                .map { Triple(it.id, it.short, count[it.id] ?: 0) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(league, chips.size > 2) { chips.indexOfFirst { it.first == selected }.takeIf { it > 1 }?.let { state.scrollToItem(it - 1) } }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        items(chips, key = { it.first }) { (id, label, n) ->
            val sel = id == selected
            val bg by animateColorAsState(if (sel) league.accent else Dez.Card, tween(200), label = "conf")
            Row(Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { com.dez.sports.data.Favorites.setScoreFilter(league, id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) Color.White else Dez.Text, maxLines = 1)
                Text(" $n", style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = if (sel) Color.White.copy(alpha = .75f) else Dez.Faint)
            }
        }
    }
}

private val gridCells = GridCells.Adaptive(minSize = 340.dp)

@Composable
private fun LeagueBoard(league: League, s: ScoresState, filter: String, onGame: (Game) -> Unit, onGolf: (GolfEvent) -> Unit) {
    val b = s.board
    if (s.error != null && b == null) { EmptyState("Couldn't reach ESPN", s.error); return }
    if (b == null) return
    if (league == League.PGA) {
        if (b.golf.isEmpty()) { EmptyState("No tournaments this week"); return }
        LazyVerticalGrid(gridCells, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(b.golf, key = { it.id }) { GolfCard(it, onClick = { onGolf(it) }, modifier = Modifier.animateItem()) }
        }
        return
    }
    if (b.games.isEmpty()) { EmptyState("No games", "Try another day — swipe the date strip."); return }
    val shown = b.games.filter { Conferences.matches(it, filter) }
    if (shown.isEmpty()) { EmptyState(if (filter == Conferences.TOP25) "No Top 25 games" else "No games from this conference", "Tap All to see every game."); return }
    val groups = shown.sortedWith(compareBy<Game>({ when (it.state) { GameState.LIVE -> 0; GameState.PRE -> 1; GameState.POST -> 2 } }, { it.dateIso }))
        .groupBy { if (league.weekly) localDay(it.dateIso) else when (it.state) { GameState.LIVE -> "Live"; GameState.PRE -> "Upcoming"; GameState.POST -> "Final" } }
    val compact = com.dez.sports.data.Favorites.compactScores.collectAsState().value ?: com.dez.sports.data.Favorites.compact()
    LazyVerticalGrid(if (compact) GridCells.Adaptive(minSize = 228.dp) else gridCells, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        groups.forEach { (title, games) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$title") { SectionTitle(title) }
            items(games, key = { it.id }) { g ->
                if (compact) CompactScore(g, isFav(g), onClick = { onGame(g) }, modifier = Modifier.animateItem())
                else ScoreCard(g, onClick = { onGame(g) }, modifier = Modifier.animateItem(), highlight = isFav(g))
            }
        }
    }
}

private fun isFav(g: Game) = com.dez.sports.data.Favorites.teams.value.any { it.league == g.league && g.involves(it.teamId) }

@Composable
private fun ForYouFeed(feed: ForYou?, onGame: (Game) -> Unit, onTeam: (League, String) -> Unit, onGolf: (GolfEvent) -> Unit) {
    if (feed == null) { EmptyState("Couldn't load your teams", "Pull down to retry."); return }
    val compact = com.dez.sports.data.Favorites.compactScores.collectAsState().value ?: com.dez.sports.data.Favorites.compact()
    if (compact) { CompactForYou(feed, onGame, onTeam, onGolf); return }
    LazyVerticalGrid(gridCells, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (feed.upsets.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "upsets") { SectionTitle("🚨 Upset Alert") }
            items(feed.upsets, key = { "upset-${it.league}-${it.id}" }) { g -> LeagueTagged(g) { ScoreCard(g, onClick = { onGame(g) }) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "my") { SectionTitle("My Teams") }
        items(feed.favorites, key = { "fav-${it.league}-${it.team.id}" }) { c -> FavoriteTeamCard(c, onGame, onTeam, Modifier.animateItem()) }
        if (feed.tv.big.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "big") {
                SectionTitle("Big Games Tonight", trailing = { Text("ON NATIONAL TV", style = MaterialTheme.typography.labelSmall, color = Dez.Sub) })
            }
            items(feed.tv.big, key = { "big-${it.game.league}-${it.game.id}" }) { b -> BigGameCard(b, onGame, Modifier.animateItem()) }
        }
        if (feed.tv.guide.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "guide-h") { SectionTitle(if (feed.tv.big.isEmpty()) "On TV Tonight" else "Full TV Guide") }
            item(span = { GridItemSpan(maxLineSpan) }, key = "guide") { TvGuideCard(feed.tv.guide, onGame, Modifier.animateItem()) }
        }
        if (feed.live.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "live") {
                SectionTitle("Live Now", trailing = { LiveDot(5.dp) })
            }
            items(feed.live, key = { "live-${it.league}-${it.id}" }) { g -> LeagueTagged(g) { ScoreCard(g, onClick = { onGame(g) }) } }
        }
        if (feed.golf.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "golf") { SectionTitle("On the Course") }
            items(feed.golf, key = { "golf-${it.id}" }) { GolfCard(it, onClick = { onGolf(it) }) }
        }
        if (feed.upNext.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "next") { SectionTitle("Later Today") }
            items(feed.upNext, key = { "up-${it.league}-${it.id}" }) { g -> LeagueTagged(g) { ScoreCard(g, onClick = { onGame(g) }) } }
        }
    }
}

/** For You as small tiles: one line per team per game, so everything you follow fits on a screen or two. */
@Composable
private fun CompactForYou(feed: ForYou, onGame: (Game) -> Unit, onTeam: (League, String) -> Unit, onGolf: (GolfEvent) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(minSize = 228.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (feed.upsets.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "upsets") { SectionTitle("🚨 Upset Alert") }
            items(feed.upsets, key = { "cupset-${it.league}-${it.id}" }) { g -> CompactScore(g, fav = false, onClick = { onGame(g) }) }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "my") { SectionTitle("My Teams") }
        items(feed.favorites, key = { "cfav-${it.league}-${it.team.id}" }) { c ->
            val g = c.featured
            Column(Modifier.animateItem()) {
                val tc = teamColor(c.team.color, c.team.altColor).lighten(.35f)
                Row(Modifier.clickable { onTeam(c.league, c.team.id) }.padding(start = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (c.featuredLabel == "LIVE") LiveDot(3.dp)
                    Text("${c.team.name.uppercase()} · ${c.featuredLabel}", style = MaterialTheme.typography.labelSmall, color = if (c.featuredLabel == "LIVE") Dez.Live else tc, maxLines = 1)
                }
                if (g != null) CompactScore(g, fav = false, onClick = { onGame(g) })
                else Text("No games scheduled", style = MaterialTheme.typography.bodySmall, color = Dez.Faint, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (feed.tv.big.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "big") {
                SectionTitle("Big Games Tonight", trailing = { Text("ON NATIONAL TV", style = MaterialTheme.typography.labelSmall, color = Dez.Sub) })
            }
            items(feed.tv.big, key = { "cbig-${it.game.league}-${it.game.id}" }) { b ->
                Column(Modifier.animateItem()) {
                    Row(Modifier.padding(start = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf(b.game.league.shortLabel, *b.tags.map { it.uppercase() }.toTypedArray()).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = if (b.mustWatch) Dez.Live else Dez.Sub, maxLines = 1, modifier = Modifier.weight(1f))
                        b.channel?.let { ChannelBadge(it.name, 16.dp) }
                    }
                    CompactScore(b.game, fav = false, onClick = { onGame(b.game) })
                }
            }
        }
        if (feed.live.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "live") { SectionTitle("Live Now", trailing = { LiveDot(5.dp) }) }
            items(feed.live, key = { "clive-${it.league}-${it.id}" }) { g -> LeagueTagged(g) { CompactScore(g, isFav(g), onClick = { onGame(g) }) } }
        }
        if (feed.tv.guide.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "guide-h") { SectionTitle(if (feed.tv.big.isEmpty()) "On TV Tonight" else "Full TV Guide") }
            item(span = { GridItemSpan(maxLineSpan) }, key = "guide") { TvGuideCard(feed.tv.guide, onGame) }
        }
        if (feed.golf.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "golf") { SectionTitle("On the Course") }
            items(feed.golf, key = { "cgolf-${it.id}" }) { GolfCard(it, onClick = { onGolf(it) }) }
        }
        if (feed.upNext.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "next") { SectionTitle("Later Today") }
            items(feed.upNext, key = { "cup-${it.league}-${it.id}" }) { g -> LeagueTagged(g) { CompactScore(g, isFav(g), onClick = { onGame(g) }) } }
        }
    }
}

@Composable
private fun LeagueTagged(g: Game, content: @Composable () -> Unit) {
    Column {
        Text(g.league.shortLabel, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun FavoriteTeamCard(c: FavoriteCard, onGame: (Game) -> Unit, onTeam: (League, String) -> Unit, modifier: Modifier) {
    val tc = teamColor(c.team.color, c.team.altColor).lighten(.35f)
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(Dez.Card).padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onTeam(c.league, c.team.id) }.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(c.team, 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.team.displayName, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(c.league.shortLabel, c.record, c.standing).joinToString(" · "), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = Dez.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                c.form.forEach { r ->
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (r == "W") Dez.Win.copy(alpha = .22f) else Dez.Live.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                        Text(r, style = MaterialTheme.typography.labelSmall, color = if (r == "W") Dez.Win else Dez.Live)
                    }
                }
            }
        }
        val g = c.featured
        if (g != null) {
            Row(Modifier.padding(start = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.featuredLabel == "LIVE") LiveDot(4.dp)
                Text(c.featuredLabel + if (c.featuredLabel != "LIVE") "  ·  ${localDay(g.dateIso)}" else "", style = MaterialTheme.typography.labelSmall, color = if (c.featuredLabel == "LIVE") Dez.Live else tc)
            }
            ScoreCard(g, onClick = { onGame(g) }, modifier = Modifier.padding(horizontal = 8.dp))
        } else Text("No games scheduled yet", style = MaterialTheme.typography.bodyMedium, color = Dez.Faint, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        c.next?.let { n ->
            val opp = if (n.home.team.id == c.team.id) "vs ${n.away.team.abbreviation}" else "@ ${n.home.team.abbreviation}"
            Row(Modifier.fillMaxWidth().clickable { onGame(n) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT", style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
                Spacer(Modifier.width(8.dp))
                TeamLogo(if (n.home.team.id == c.team.id) n.away.team else n.home.team, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("$opp  ·  ${localDay(n.dateIso)}  ${localTime(n.dateIso)}", style = MaterialTheme.typography.labelLarge, color = Dez.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.mainChannel()?.let { ChannelBadge(it, 18.dp) }
            }
        }
    }
}

@Composable
private fun AddWidgetButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.compose.material3.Icon(
        androidx.compose.material.icons.Icons.Filled.Widgets, "Add widget to home screen", tint = Dez.Sub,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp).size(40.dp).clip(RoundedCornerShape(50)).background(Dez.Card2)
            .clickable { scope.launch { androidx.glance.appwidget.GlanceAppWidgetManager(context).requestPinGlanceAppWidget(com.dez.sports.widget.DezWidgetReceiver::class.java) } }
            .padding(9.dp),
    )
}
