package com.dez.sports.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dez.sports.data.Networks
import com.dez.sports.model.Game

/** Network logo on a small light chip (network logos are drawn for light backgrounds); falls back to the name. */
@Composable
fun ChannelBadge(name: String, height: Dp = 20.dp, modifier: Modifier = Modifier) {
    val logo = Networks.logo(name)
    if (logo == null) {
        Pill(name, Color.White.copy(alpha = .12f), Color.White.copy(alpha = .8f), modifier)
        return
    }
    AsyncImage(
        model = logo, contentDescription = name, contentScale = ContentScale.Fit,
        modifier = modifier.height(height).width(height * 2.3f).clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF2F2F5)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** The channel to show for a game: national TV first, then whatever ESPN lists. */
fun Game.mainChannel(): String? = channels.firstOrNull { Networks.isMarquee(it) }?.name ?: broadcasts.firstOrNull { !it.contains("League Pass", true) && !it.contains("radio", true) }

/** Score-card sized TV line: logo when we have one, plain text otherwise. */
@Composable
fun TvLine(game: Game, alpha: Float) {
    val name = game.mainChannel() ?: return
    if (Networks.logo(name) != null) ChannelBadge(name, 16.dp, Modifier.padding(top = 3.dp))
    else Text(name, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
