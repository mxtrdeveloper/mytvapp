package com.example.tvremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.net.ServerSocket

class RemoteServerService : Service() {

    private lateinit var dispatcher: CommandDispatcher
    private var server: RemoteWebSocketServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var token = ""

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    // Re-publish the IP (and therefore the QR) whenever Wi-Fi/Ethernet changes.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAddress()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshAddress()
        override fun onLost(network: Network) = refreshAddress()
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground() // must happen within 5 s of startForegroundService()

        val specs = DeviceInfoProvider.detect(this)
        dispatcher = CommandDispatcher(applicationContext)
        token = Security.newToken()
        val port = pickPort(PREFERRED_PORT)

        SessionStore.update {
            it.copy(
                deviceModel = specs.model,
                osVersion = specs.osVersion,
                port = port,
                token = token,
                clientConnected = false,
                privilegedInjection = dispatcher.hasPrivilegedInjection,
            )
        }

        startServer(port)
        refreshAddress()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ROTATE_TOKEN) rotateToken()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { server?.shutdown() }
        wifiLock?.takeIf { it.isHeld }?.release()
        dispatcher.release()
        SessionStore.update { it.copy(serverRunning = false, clientConnected = false) }
        super.onDestroy()
    }

    // ---- server -----------------------------------------------------------------------------

    private fun startServer(port: Int) {
        server = RemoteWebSocketServer(
            port = port,
            currentToken = { token },
            dispatcher = dispatcher,
            callbacks = object : RemoteWebSocketServer.Callbacks {
                override fun onStarted() = SessionStore.update { it.copy(serverRunning = true) }
                override fun onClientChanged(connected: Boolean) =
                    SessionStore.update { it.copy(clientConnected = connected) }
                override fun onTooManyAuthFailures() = rotateToken()
                override fun onFatalError(e: Exception) {
                    Log.e(TAG, "Server failed", e)
                    SessionStore.update { it.copy(serverRunning = false) }
                }
            },
        ).also { it.start() }
    }

    /** New secret + QR; drops whatever is connected. */
    private fun rotateToken() {
        token = Security.newToken()
        server?.disconnectAll()
        SessionStore.update { it.copy(token = token, clientConnected = false) }
    }

    private fun refreshAddress() {
        val ip = DeviceInfoProvider.localIpv4(this).orEmpty()
        SessionStore.update { it.copy(ip = ip) }
    }

    /** Prefer 8080; if taken, walk up; last resort, let the OS pick. The chosen port goes in the QR. */
    private fun pickPort(preferred: Int): Int {
        for (p in preferred until preferred + 20) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: IOException) { /* in use, try next */ }
        }
        return ServerSocket(0).use { it.localPort }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "tvremote:ws").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("TV remote receiver is running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    companion object {
        const val ACTION_ROTATE_TOKEN = "com.example.tvremote.ROTATE_TOKEN"
        private const val TAG = "RemoteServerService"
        private const val PREFERRED_PORT = 8080
        private const val CHANNEL_ID = "remote_server"
        private const val NOTIF_ID = 1
    }
}
