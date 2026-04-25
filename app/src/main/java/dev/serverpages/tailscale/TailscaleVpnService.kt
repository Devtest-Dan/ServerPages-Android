package dev.serverpages.tailscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.util.Log
import libtailscale.Libtailscale
import libtailscale.ParcelFileDescriptor
import java.net.InetAddress
import java.util.UUID

/**
 * VPN service that hosts the embedded Tailscale node. Implements
 * libtailscale.IPNService so libtailscale's Go backend can drive VPN tunnel
 * setup. Started by TailscaleNode after VPN consent has been granted.
 */
class TailscaleVpnService : VpnService(), libtailscale.IPNService {

    private val randomId: String = UUID.randomUUID().toString()
    private var closed = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TailscaleVpnService onCreate")
        ensureNotificationChannel()
        // Ensure libtailscale is initialized before we ask it to set up VPN.
        TailscaleNode.ensureStarted(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: must call startForeground within 5s of startForegroundService.
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        Libtailscale.requestVPN(this)
        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tailscale",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Embedded Tailscale node"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, dev.serverpages.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AirDeck")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "TailscaleVpnService onDestroy")
        close()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        close()
        super.onRevoke()
    }

    override fun id(): String = randomId

    override fun protect(fd: Int): Boolean = (this as VpnService).protect(fd)

    override fun newBuilder(): libtailscale.VPNServiceBuilder {
        val b = Builder()
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false)
        }
        b.setUnderlyingNetworks(null)
        return TailscaleVpnBuilder(b)
    }

    override fun close() {
        if (closed) return
        closed = true
        Libtailscale.serviceDisconnect(this)
    }

    override fun disconnectVPN() {
        stopSelf()
    }

    override fun updateVpnStatus(running: Boolean) {
        Log.i(TAG, "updateVpnStatus: $running")
    }

    companion object {
        private const val TAG = "TailscaleVpnService"
        private const val CHANNEL_ID = "tailscale_vpn"
        private const val NOTIFICATION_ID = 2
    }
}

/** Wraps Android's VpnService.Builder for libtailscale to drive. */
class TailscaleVpnBuilder(private val b: VpnService.Builder) : libtailscale.VPNServiceBuilder {

    override fun setMTU(mtu: Int) { b.setMtu(mtu) }
    override fun addDNSServer(addr: String) { b.addDnsServer(addr) }
    override fun addSearchDomain(domain: String) { b.addSearchDomain(domain) }
    override fun addRoute(addr: String, prefix: Int) { b.addRoute(addr, prefix) }
    override fun excludeRoute(addr: String, prefix: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b.excludeRoute(IpPrefix(InetAddress.getByName(addr), prefix))
        }
    }
    override fun addAddress(addr: String, prefix: Int) { b.addAddress(addr, prefix) }

    override fun establish(): ParcelFileDescriptor? =
        b.establish()?.let { TailscalePfd(it) }
}

/** Wraps Android's ParcelFileDescriptor for libtailscale's Go side. */
class TailscalePfd(private val pfd: android.os.ParcelFileDescriptor) : ParcelFileDescriptor {
    override fun detach(): Int = pfd.detachFd()
}
