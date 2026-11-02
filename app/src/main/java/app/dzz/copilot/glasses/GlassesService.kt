package app.dzz.copilot.glasses

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.dzz.copilot.MainActivity
import app.dzz.copilot.R
import app.dzz.copilot.hub
import app.dzz.copilot.reminder.Channels
import app.dzz.copilot.reminder.ReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GlassesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pumpsStarted = false
    private var locationListener: LocationListener? = null
    private val nearby = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Channels.ensure(this)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                hub().glasses.disconnect()
                hub().aura.stop()
                stopLocation()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startInForeground(hub().glasses.snapshot.value)
        if (!pumpsStarted) {
            pumpsStarted = true
            scope.launch {
                hub().glasses.snapshot.collect { snap ->
                    startInForeground(snap)
                    app.dzz.copilot.widget.StatusWidget.update(this@GlassesService)
                }
            }
            // Only when entering Ready — not on every battery/heartbeat snapshot.
            scope.launch {
                hub().glasses.snapshot
                    .map { it.phase == LinkPhase.Ready }
                    .distinctUntilChanged()
                    .collect { ready ->
                        if (ready) hub().aura.preferGlassesMic()
                    }
            }
            scope.launch {
                hub().store.placesArmed.collect { refreshLocation() }
            }
            scope.launch {
                hub().store.auraEnabled.collect { enabled ->
                    if (enabled && hasMicPermission()) hub().aura.start() else hub().aura.stop()
                }
            }
        }
        if (intent?.action != ACTION_DISCONNECT) {
            hub().glasses.reconnectSaved()
            if (hub().store.auraEnabled.value && hasMicPermission()) hub().aura.start()
        }
        refreshLocation()
        return START_STICKY
    }

    override fun onDestroy() {
        hub().aura.stop()
        stopLocation()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(snap: GlassesSnapshot) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, GlassesService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            hub().aura.state.value.phase.name == "Listening" -> "Ouvindo Aura"
            snap.battery != null -> "${snap.name ?: "Óculos"} · ${snap.battery}%"
            snap.phase == LinkPhase.Ready -> snap.name ?: "Conectado"
            else -> snap.status
        }
        val notification = NotificationCompat.Builder(this, Channels.STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Aura Glasses")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Desconectar", disconnect)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val started = runCatching { startForeground(NOTIFICATION_ID, notification, foregroundType()) }
            if (started.isFailure) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 30 && hub().store.auraEnabled.value && hasMicPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= 29 && hub().store.placesArmed.value && hasLocationPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshLocation() {
        stopLocation()
        if (!hub().store.placesArmed.value || !hasLocationPermission()) return
        val manager = getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = this@GlassesService.onLocation(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        locationListener = listener
        runCatching {
            manager.requestLocationUpdates(provider, 120_000L, 40f, listener, Looper.getMainLooper())
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) onLocation(last)
        }
    }

    private fun stopLocation() {
        val listener = locationListener ?: return
        val manager = getSystemService(LocationManager::class.java)
        runCatching { manager?.removeUpdates(listener) }
        locationListener = null
    }

    private fun onLocation(location: Location) {
        val vault = hub().vault
        val places = vault.places.value
        vault.tasks.value.filter { !it.done && it.placeId != null }.forEach { task ->
            val place = places.firstOrNull { it.id == task.placeId } ?: return@forEach
            val distance = meters(location.latitude, location.longitude, place.latitude, place.longitude)
            if (distance <= place.radiusMeters) {
                if (nearby.add(task.id)) {
                    ReminderNotifier.show(this, "place-${task.id}", place.name, task.title)
                }
            } else if (distance > place.radiusMeters + 220) {
                nearby.remove(task.id)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_DISCONNECT = "app.dzz.copilot.DISCONNECT"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, GlassesService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GlassesService::class.java).setAction(ACTION_DISCONNECT)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * earth * asin(sqrt(a))
}
