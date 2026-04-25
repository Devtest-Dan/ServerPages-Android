package dev.serverpages.tailscale

import android.content.Context
import android.util.Log
import dev.serverpages.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libtailscale.Libtailscale
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Central manager for the embedded Tailscale node. Initializes libtailscale
 * once per process, drives auth-key login, polls for the tailnet IPv4, and
 * exposes it as a stable URL.
 *
 * Lifecycle:
 *   1. App.onCreate() calls [ensureStarted] → libtailscale.Start with our AppContext
 *   2. CaptureService calls [requestVpnConsent] before starting [TailscaleVpnService]
 *   3. TailscaleVpnService.onStartCommand calls Libtailscale.requestVPN(this)
 *   4. libtailscale builds the tun, brings up wireguard, registers on tailnet
 *   5. Background poller fetches /localapi/v0/status until 100.x.x.x appears
 */
object TailscaleNode {

    private const val TAG = "TailscaleNode"
    private const val LOCAL_API_TIMEOUT_MS = 5000L

    @Volatile private var app: libtailscale.Application? = null
    @Volatile private var started = false
    // Strong reference — keep alive for libtailscale's lifetime so gomobile's
    // go.Seq ref tracker can call back into Java without seeing an "Unknown
    // reference" abort when the AppContext gets GC'd.
    @Volatile private var appCtx: TailscaleAppContext? = null
    @Volatile var ipv4: String = ""
        private set
    @Volatile var hostname: String = ""
        private set

    private var pollJob: Job? = null
    private var loginAttempted = false

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (started) return
        val authKey = BuildConfig.TAILSCALE_AUTH_KEY
        if (authKey.isEmpty()) {
            Log.w(TAG, "TAILSCALE_AUTH_KEY missing in local.properties — skipping start")
            return
        }
        val dataDir = ctx.filesDir.absolutePath
        val directFileRoot = ctx.filesDir.absolutePath
        try {
            Log.i(TAG, "Libtailscale.start dataDir=$dataDir")
            val ac = TailscaleAppContext(ctx.applicationContext)
            appCtx = ac
            app = Libtailscale.start(dataDir, directFileRoot, false, ac)
            started = true
        } catch (e: Throwable) {
            Log.e(TAG, "Libtailscale.start failed", e)
        }
    }

    fun runLoginAndPoll(scope: CoroutineScope, onIpChanged: (String) -> Unit) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            // Wait until libtailscale is up
            var tries = 0
            while (isActive && app == null && tries < 60) {
                delay(500)
                tries++
            }
            val a = app ?: run {
                Log.w(TAG, "App never initialized — abort login/poll")
                return@launch
            }

            // Set hostname pref before login so registration uses it.
            setHostnamePref(a, BuildConfig.TAILSCALE_HOSTNAME)

            // Send auth key once.
            if (!loginAttempted) {
                loginAttempted = true
                val ok = startWithAuthKey(a, BuildConfig.TAILSCALE_AUTH_KEY)
                Log.i(TAG, "Auth-key start request → $ok")
            }

            // Poll status until we see a 100.x.x.x address.
            while (isActive) {
                val newIp = fetchTailnetIpv4(a)
                if (newIp.isNotEmpty() && newIp != ipv4) {
                    ipv4 = newIp
                    Log.i(TAG, "Tailscale IPv4 = $newIp")
                    onIpChanged(newIp)
                }
                delay(if (ipv4.isEmpty()) 3_000 else 30_000)
            }
        }
    }

    private fun startWithAuthKey(a: libtailscale.Application, authKey: String): Boolean {
        if (authKey.isEmpty()) return false
        val body = JSONObject().put("AuthKey", authKey).toString().toByteArray(Charsets.UTF_8)
        val resp = try {
            a.callLocalAPI(
                LOCAL_API_TIMEOUT_MS,
                "POST",
                "/localapi/v0/start",
                BytesInputStream(body)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "/localapi/v0/start failed: ${e.message}")
            return false
        }
        val code = resp.statusCode().toInt()
        if (code !in 200..299) {
            val text = runCatching { String(resp.bodyBytes(), Charsets.UTF_8) }.getOrDefault("")
            Log.w(TAG, "start non-2xx: $code $text")
            return false
        }
        return true
    }

    private fun setHostnamePref(a: libtailscale.Application, hostname: String) {
        if (hostname.isEmpty()) return
        // PATCH /localapi/v0/prefs with MaskedPrefs { Hostname, HostnameSet=true }
        val body = JSONObject()
            .put("Hostname", hostname)
            .put("HostnameSet", true)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val resp = a.callLocalAPI(
                LOCAL_API_TIMEOUT_MS,
                "PATCH",
                "/localapi/v0/prefs",
                BytesInputStream(body)
            )
            val code = resp.statusCode().toInt()
            if (code !in 200..299) {
                Log.w(TAG, "prefs hostname patch non-2xx: $code")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "prefs hostname patch failed: ${e.message}")
        }
    }

    private fun fetchTailnetIpv4(a: libtailscale.Application): String {
        return try {
            val resp = a.callLocalAPI(LOCAL_API_TIMEOUT_MS, "GET", "/localapi/v0/status", null)
            if (resp.statusCode().toInt() !in 200..299) return ""
            val text = String(resp.bodyBytes(), Charsets.UTF_8)
            parseSelfIpv4(text)
        } catch (e: Throwable) {
            ""
        }
    }

    private fun parseSelfIpv4(json: String): String {
        return try {
            val obj = JSONObject(json)
            val self = obj.optJSONObject("Self") ?: return ""
            hostname = self.optString("HostName", hostname)
            val ips = self.optJSONArray("TailscaleIPs") ?: return ""
            for (i in 0 until ips.length()) {
                val ip = ips.optString(i)
                if (ip.contains('.')) return ip   // IPv4
            }
            ""
        } catch (_: JSONException) {
            ""
        }
    }
}

/** Adapter from a byte array to libtailscale.InputStream (one-shot). */
private class BytesInputStream(private val bytes: ByteArray) : libtailscale.InputStream {
    private var sent = false
    override fun read(): ByteArray? {
        if (sent) return null
        sent = true
        return bytes
    }
    override fun close() {}
}
