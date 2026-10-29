package com.dez.sports.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.ui.graphics.toArgb
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.dez.sports.MainActivity
import com.dez.sports.R
import com.dez.sports.data.EspnApi
import com.dez.sports.model.Game
import com.dez.sports.model.GameState
import com.dez.sports.model.League
import com.dez.sports.model.SportKind
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.components.liveLabel
import com.dez.sports.ui.components.localTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Games pinned to the lock screen, persisted so the service survives restarts. */
object LivePins {
    private val _pins = MutableStateFlow<Set<String>>(emptySet())   // "NBA:401909861"
    val pins: StateFlow<Set<String>> = _pins

    fun load(context: Context) {
        _pins.value = prefs(context).getStringSet("pins", emptySet())!!.toSet()
    }

    fun isPinned(league: League, id: String) = "${league.name}:$id" in _pins.value

    fun pin(context: Context, league: League, id: String) {
        update(context, _pins.value + "${league.name}:$id")
        val i = Intent(context, LiveScoreService::class.java)
        context.startForegroundService(i)
    }

    fun unpin(context: Context, league: League, id: String) {
        update(context, _pins.value - "${league.name}:$id")
        context.getSystemService(NotificationManager::class.java).cancel(LiveScoreService.notificationId(id))
        context.startService(Intent(context, LiveScoreService::class.java))
    }

    private fun update(context: Context, v: Set<String>) {
        _pins.value = v
        prefs(context).edit().putStringSet("pins", v).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("dez", Context.MODE_PRIVATE)
}

/**
 * Keeps each pinned game's score on the lock screen. On Android 16+ the notification is a
 * promoted "Live Update": score chip in the status bar, game-progress bar split by period.
 */
class LiveScoreService : Service() {
    companion object {
        const val CHANNEL = "live_scores"
        const val ACTION_UNPIN = "com.dez.sports.UNPIN"
        fun notificationId(eventId: String) = 5000 + (eventId.hashCode() and 0xFFFF)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val logoCache = HashMap<String, Bitmap?>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LivePins.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live scores", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Pinned games on your lock screen"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNPIN) {
            val league = intent.getStringExtra("league")?.let { runCatching { League.valueOf(it) }.getOrNull() }
            val id = intent.getStringExtra("id")
            if (league != null && id != null) LivePins.unpin(this, league, id)
        }
        val pins = LivePins.pins.value
        if (pins.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        // Must call startForeground promptly; a placeholder is replaced on the first fetch.
        val placeholder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_mono).setContentTitle("Dez Sports").setContentText("Loading live score…").build()
        val first = pins.first().substringAfter(":")
        startForeground(notificationId(first), placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (loop?.isActive != true) loop = scope.launch { poll() }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun poll() {
        val nm = getSystemService(NotificationManager::class.java)
        while (scope.isActive) {
            val pins = LivePins.pins.value
            if (pins.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            var next = 60_000L
            for (pin in pins) {
                val league = runCatching { League.valueOf(pin.substringBefore(":")) }.getOrNull() ?: continue
                val id = pin.substringAfter(":")
                val g = runCatching { EspnApi.event(league, id) }.getOrNull() ?: continue
                nm.notify(notificationId(id), build(g))
                when (g.state) {
                    GameState.LIVE -> next = minOf(next, 15_000L)
                    GameState.PRE -> {
                        val mins = runCatching { Duration.between(Instant.now(), Instant.parse(EspnApi.fixIso(g.dateIso))).toMinutes() }.getOrDefault(0)
                        next = minOf(next, if (mins > 30) 5 * 60_000L else 60_000L)
                    }
                    GameState.POST -> {
                        // Final: leave a normal (dismissable) notification and stop tracking.
                        LivePins.unpin(this, league, id)
                        nm.notify(notificationId(id) + 1, build(g))
                    }
                }
            }
            delay(next)
        }
    }

    private suspend fun logo(url: String?): Bitmap? {
        if (url == null) return null
        logoCache[url]?.let { return it }
        val req = ImageRequest.Builder(this).data(url).size(128).allowHardware(false).build()
        val bmp = (SingletonImageLoader.get(this).execute(req) as? SuccessResult)?.image?.toBitmap()
        logoCache[url] = bmp
        return bmp
    }

    /** Both logos side by side for the notification's large icon. */
    private suspend fun matchupIcon(g: Game): Bitmap? {
        val a = logo(g.away.team.darkLogo ?: g.away.team.logo) ?: return null
        val h = logo(g.home.team.darkLogo ?: g.home.team.logo) ?: return a
        val out = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawBitmap(Bitmap.createScaledBitmap(a, 120, 120, true), 0f, 4f, null)
            drawBitmap(Bitmap.createScaledBitmap(h, 120, 120, true), 136f, 4f, null)
        }
        return out
    }

    private suspend fun build(g: Game): Notification {
        val title = when (g.state) {
            GameState.PRE -> "${g.away.team.abbreviation} @ ${g.home.team.abbreviation}"
            else -> "${g.away.team.abbreviation} ${g.away.score ?: 0}  –  ${g.home.score ?: 0} ${g.home.team.abbreviation}"
        }
        val status = when (g.state) {
            GameState.LIVE -> liveLabel(g)
            GameState.PRE -> "Starts ${localTime(g.dateIso)}"
            GameState.POST -> g.statusShort
        }
        val text = listOfNotNull(status, g.broadcasts.firstOrNull()).joinToString(" · ")
        val detail = g.football?.downDistance ?: g.baseball?.let { b -> "${b.balls}-${b.strikes}, ${b.outs} out" } ?: g.lastPlay

        val open = PendingIntent.getActivity(this, g.id.hashCode(),
            Intent(this, MainActivity::class.java).setData(Uri.parse("dez://game/${g.league.name}/${g.id}"))
                .putExtra("route", "game/${g.league.name}/${g.id}").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, g.id.hashCode() + 1,
            Intent(this, LiveScoreService::class.java).setAction(ACTION_UNPIN).putExtra("league", g.league.name).putExtra("id", g.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
        matchupIcon(g)?.let { b.setLargeIcon(it) }
        if (g.state != GameState.POST) {
            b.setOngoing(true).addAction(Notification.Action.Builder(null, "Unpin", stop).build())
        } else b.setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= 36 && g.state != GameState.POST) {
            // Same as Notification.Builder#setRequestPromotedOngoing (newer SDKs): ask for a promoted Live Update.
            b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            b.setShortCriticalText(if (g.state == GameState.PRE) localTime(g.dateIso) else "${g.away.score ?: 0}-${g.home.score ?: 0}")
            b.setStyle(progressStyle(g))
            detail?.let { b.setSubText(it) }
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text, detail).joinToString("\n")))
        }
        return b.build()
    }

    /** Game clock as a progress bar: one segment per period in alternating team colours. */
    @androidx.annotation.RequiresApi(36)
    private fun progressStyle(g: Game): Notification.ProgressStyle {
        val (periods, len) = when (g.league.kind) {
            SportKind.BASKETBALL -> if (g.league == League.CBB) 2 to 1200 else 4 to 720
            SportKind.FOOTBALL -> 4 to 900
            SportKind.HOCKEY -> 3 to 1200
            SportKind.BASEBALL -> 9 to 100
            else -> 4 to 100
        }
        val (awayC, homeC) = g.colors()
        val total = periods.coerceAtLeast(g.period)
        val clockSecs = g.clock?.let { c ->
            val parts = c.split(":"); if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toFloatOrNull()?.toInt() ?: 0) else c.toFloatOrNull()?.toInt()
        } ?: 0
        val elapsed = when {
            g.state == GameState.PRE -> 0
            g.league.kind == SportKind.BASEBALL -> ((g.period - 1).coerceAtLeast(0) * len + (if (g.statusShort.startsWith("Bot") || g.statusShort.startsWith("Mid")) len / 2 else 0))
            else -> (g.period - 1).coerceAtLeast(0) * len + (len - clockSecs).coerceIn(0, len)
        }
        return Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments((1..total).map { i -> Notification.ProgressStyle.Segment(len).setColor((if (i % 2 == 1) awayC else homeC).toArgb()) })
            .setProgress(elapsed.coerceAtMost(total * len))
            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_launcher_mono))
    }
}
