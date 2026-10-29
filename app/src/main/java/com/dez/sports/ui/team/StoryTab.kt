package com.dez.sports.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dez.sports.data.SeasonStories
import com.dez.sports.data.SeasonStory
import com.dez.sports.data.StoryBeat
import com.dez.sports.model.Game
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez

/** The season told newest-first as a timeline: chapters, results, milestones and news on one spine. */
fun LazyGridScope.storyItems(story: SeasonStory, teamId: String, tc: Color, onGame: (Game) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "story-head") {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(tc.copy(alpha = .35f), Dez.Card))).padding(16.dp)) {
            Text("SEASON STORY", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            Text(story.headline, style = MaterialTheme.typography.headlineSmall, color = Dez.Text)
            Text(story.subhead, style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            story.upcoming?.let { u ->
                val opp = if (u.game.home.team.id == teamId) u.game.away else u.game.home
                Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .2f)).clickable { onGame(u.game) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("NEXT UP  ", style = MaterialTheme.typography.labelSmall, color = Dez.Gold)
                    Text(if (u.game.home.team.id == teamId) "vs " else "@ ", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                    TeamLogo(opp.team, 16.dp); Spacer(Modifier.width(4.dp))
                    Text("${opp.team.displayName} · ${SeasonStories.day(u.game.dateIso)}", style = MaterialTheme.typography.labelMedium, color = Dez.Text)
                }
            }
        }
    }
    story.beats.forEachIndexed { i, b ->
        item(span = { GridItemSpan(maxLineSpan) }, key = "beat-$i") {
            Spine(tc, first = i == 0, last = i == story.beats.lastIndex, big = b is StoryBeat.Chapter) {
                when (b) {
                    is StoryBeat.Chapter -> ChapterBeat(b, tc)
                    is StoryBeat.Result -> ResultBeat(b, teamId) { onGame(b.item.game) }
                    is StoryBeat.Milestone -> MilestoneBeat(b)
                    is StoryBeat.News -> NewsBeat(b)
                }
            }
        }
    }
}

/** The vertical line down the left with a node for each beat. */
@Composable
private fun Spine(tc: Color, first: Boolean, last: Boolean, big: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.padding(start = 20.dp, end = 12.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = if (first) 14.dp else 0.dp).width(2.dp).fillMaxHeight(if (last) .3f else 1f).background(tc.readable().copy(alpha = .35f)))
            Box(Modifier.padding(top = 12.dp).size(if (big) 12.dp else 8.dp).clip(CircleShape).background(if (big) tc.readable() else Dez.Sub))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun ChapterBeat(b: StoryBeat.Chapter, tc: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("CHAPTER ${b.number}", style = MaterialTheme.typography.labelSmall, color = tc.readable())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, color = Dez.Text, modifier = Modifier.weight(1f))
            Text(b.record, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Sub)
        }
        Text(b.summary, style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
    }
}

@Composable
private fun ResultBeat(b: StoryBeat.Result, teamId: String, onClick: () -> Unit) {
    val g = b.item.game
    val opp = if (g.home.team.id == teamId) g.away else g.home
    val win = b.item.result!!.startsWith("W")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Card).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(SeasonStories.day(g.dateIso), style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Dez.Faint, modifier = Modifier.width(46.dp))
        Text(if (g.home.team.id == teamId) "vs" else "@", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.width(18.dp))
        TeamLogo(opp.team, 20.dp); Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(opp.team.name.ifEmpty { opp.team.displayName }, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.tags.isNotEmpty()) Text(b.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Dez.Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(b.item.result!!, style = MaterialTheme.typography.labelLarge.merge(TabularNums).copy(fontWeight = FontWeight.Bold), color = if (win) Dez.Win else Dez.Live)
            Text(b.recordAfter, style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Dez.Faint)
        }
    }
}

@Composable
private fun MilestoneBeat(b: StoryBeat.Milestone) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Gold.copy(alpha = .1f)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(b.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(b.title, style = MaterialTheme.typography.labelLarge, color = Dez.Text)
            Text(b.detail, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
    }
}

@Composable
private fun NewsBeat(b: StoryBeat.News) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Card2).then(if (b.url != null) Modifier.clickable { runCatching { uri.openUri(b.url) } } else Modifier).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        b.image?.let { AsyncImage(it, null, Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text("NEWS · ${SeasonStories.day(b.dateIso)}", style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
            Text(b.headline, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
