package com.dez.sports.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dez.sports.MainActivity
import com.dez.sports.R
import com.dez.sports.model.GameState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0B0D)
private val White = Color(0xFFF5F5F7)
private val Sub = Color(0xFF9A9AA2)
private val Live = Color(0xFFFF453A)

private fun style(size: TextUnit, color: Color = White, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align)

class DezWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(120.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val TALL = DpSize(250.dp, 230.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetData.get(context)
        provideContent {
            val snap by WidgetData.flow.collectAsState()
            WidgetBody(snap)
        }
    }
}

@Composable
private fun WidgetBody(snap: WidgetSnapshot?) {
    val size = LocalSize.current
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(Bg).padding(10.dp),
    ) {
        if (size.width >= 200.dp) Header(snap)
        val games = snap?.games.orEmpty()
        when {
            snap == null -> Message("Loading your teams…")
            games.isEmpty() -> Message("No games for your teams")
            size.width < 200.dp -> SmallGame(games.first())
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(games, itemId = { it.id.hashCode().toLong() }) { g ->
                    Column { GameRow(g); Spacer(GlanceModifier.height(6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Header(snap: WidgetSnapshot?) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.ic_launcher_mono), "Dez Sports", GlanceModifier.size(18.dp))
        Spacer(GlanceModifier.width(4.dp))
        Text("DEZ SPORTS", style = style(11.sp, bold = true),
            modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))))
        val live = snap?.games?.count { it.state == GameState.LIVE } ?: 0
        if (live > 0) {
            Spacer(GlanceModifier.width(8.dp))
            Text("● $live LIVE", style = style(10.sp, Live, bold = true))
        }
        Spacer(GlanceModifier.defaultWeight())
        snap?.let { Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.updatedAt)), style = style(10.sp, Sub)) }
        Spacer(GlanceModifier.width(6.dp))
        Image(ImageProvider(R.drawable.ic_refresh), "Refresh", GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshAction>()))
    }
}

@Composable
private fun Message(text: String) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = style(13.sp, Sub, align = TextAlign.Center)) }
}

private fun openGame(context: Context, g: WidgetGame) = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse("dez://game/${g.league.name}/${g.id}"))  // unique per game so each row gets its own PendingIntent
        .putExtra("route", "game/${g.league.name}/${g.id}")
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

@Composable
private fun GameRow(g: WidgetGame) {
    val context = LocalContext.current
    val live = g.state == GameState.LIVE
    Column(
        GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(ImageProvider(g.background), androidx.glance.layout.ContentScale.FillBounds)
            .padding(horizontal = 10.dp, vertical = 6.dp).clickable(openGame(context, g)),
    ) {
        Text(g.label, style = style(9.sp, White.copy(alpha = .7f), bold = true, align = TextAlign.Center), modifier = GlanceModifier.fillMaxWidth())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Team(g.away, leading = true)
            Spacer(GlanceModifier.defaultWeight())
            Score(g.away, g.state)
            Column(GlanceModifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(g.status, style = style(12.sp, if (live) Live else White, bold = true, align = TextAlign.Center), maxLines = 1)
                g.sub?.let { Text(it, style = style(9.sp, White.copy(alpha = .6f), align = TextAlign.Center), maxLines = 1) }
            }
            Score(g.home, g.state)
            Spacer(GlanceModifier.defaultWeight())
            Team(g.home, leading = false)
        }
    }
}

@Composable
private fun Team(s: WidgetSide, leading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading) Logo(s, 30)
        Column(GlanceModifier.padding(horizontal = 6.dp), horizontalAlignment = if (leading) Alignment.Start else Alignment.End) {
            Text(s.abbr, style = style(13.sp, if (s.dim) White.copy(alpha = .55f) else White, bold = true))
            s.record?.let { Text(it, style = style(9.sp, White.copy(alpha = .55f))) }
        }
        if (!leading) Logo(s, 30)
    }
}

@Composable
private fun Logo(s: WidgetSide, dp: Int) {
    s.logo?.let { Image(ImageProvider(it), s.abbr, GlanceModifier.size(dp.dp)) } ?: Spacer(GlanceModifier.size(dp.dp))
}

@Composable
private fun Score(s: WidgetSide, state: GameState) {
    if (state == GameState.PRE) return
    Text(s.score ?: "0", style = style(22.sp, if (s.dim) White.copy(alpha = .5f) else White, bold = true))
}

/** 2x2 size: just the most relevant game, stacked. */
@Composable
private fun SmallGame(g: WidgetGame) {
    val context = LocalContext.current
    val live = g.state == GameState.LIVE
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(16.dp).background(ImageProvider(g.background), androidx.glance.layout.ContentScale.FillBounds)
            .padding(8.dp).clickable(openGame(context, g)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(g.status, style = style(11.sp, if (live) Live else White, bold = true, align = TextAlign.Center), maxLines = 1)
        Spacer(GlanceModifier.height(4.dp))
        listOf(g.away, g.home).forEach { s ->
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Logo(s, 26)
                Text(s.abbr, style = style(12.sp, if (s.dim) White.copy(alpha = .55f) else White, bold = true), modifier = GlanceModifier.padding(start = 6.dp).defaultWeight())
                if (g.state != GameState.PRE) Text(s.score ?: "0", style = style(18.sp, if (s.dim) White.copy(alpha = .5f) else White, bold = true))
            }
        }
        g.sub?.let { Text(it, style = style(9.sp, White.copy(alpha = .6f), align = TextAlign.Center), maxLines = 1) }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetScheduler.refreshNow(context)
    }
}

class DezWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DezWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetScheduler.ensurePeriodic(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetScheduler.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetScheduler.cancel(context)
    }
}
