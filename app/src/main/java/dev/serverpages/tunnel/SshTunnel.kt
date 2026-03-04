package dev.serverpages.tunnel

import android.util.Log
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Public tunnel — exposes local HTTP port to the internet.
 * Tries pinggy.io then serveo.net, captures URL from SSH shell output.
 */
class SshTunnel(private val localPort: Int) {

    companion object {
        private const val TAG = "SshTunnel"
        private const val RECONNECT_DELAY_MS = 15_000L
        private const val MAX_RETRIES = 10
    }

    private var session: Session? = null
    private var tunnelJob: Job? = null

    @Volatile
    var publicUrl: String = ""
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    fun start(scope: CoroutineScope, onUrlChanged: (String) -> Unit) {
        tunnelJob = scope.launch(Dispatchers.IO) {
            var retries = 0
            while (isActive && retries < MAX_RETRIES) {
                try {
                    connect(onUrlChanged)
                    retries++
                    Log.w(TAG, "Tunnel disconnected, retry $retries/$MAX_RETRIES")
                    delay(RECONNECT_DELAY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retries++
                    Log.e(TAG, "Tunnel error (retry $retries/$MAX_RETRIES): ${e.message}")
                    delay(RECONNECT_DELAY_MS)
                }
            }
            if (retries >= MAX_RETRIES) {
                Log.e(TAG, "Tunnel gave up after $MAX_RETRIES retries — LAN-only mode")
            }
        }
    }

    private data class TunnelService(val host: String, val port: Int, val user: String)

    private suspend fun connect(onUrlChanged: (String) -> Unit) {
        val jsch = JSch()

        // Generate ephemeral key pair
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)
        val privKey = ByteArrayOutputStream()
        val pubKey = ByteArrayOutputStream()
        keyPair.writePrivateKey(privKey)
        keyPair.writePublicKey(pubKey, "airdeck")
        jsch.addIdentity("tunnel", privKey.toByteArray(), pubKey.toByteArray(), null)
        keyPair.dispose()

        val services = listOf(
            TunnelService("a.pinggy.io", 443, "nokey"),
            TunnelService("serveo.net", 22, "serveo"),
        )

        for (service in services) {
            try {
                Log.i(TAG, "Trying ${service.host}:${service.port}...")
                val url = tryService(jsch, service)
                if (url != null) {
                    publicUrl = url
                    isConnected = true
                    withContext(Dispatchers.Main) { onUrlChanged(url) }
                    Log.i(TAG, "Public URL: $url")

                    // Keep session alive
                    try {
                        while (session?.isConnected == true) {
                            delay(5000)
                        }
                    } catch (_: CancellationException) {}

                    // Cleanup
                    isConnected = false
                    publicUrl = ""
                    try { session?.disconnect() } catch (_: Exception) {}
                    session = null
                    withContext(Dispatchers.Main) { onUrlChanged("") }
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "${service.host} failed: ${e.message}")
            }
            // Cleanup failed attempt
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
        }

        Log.e(TAG, "All tunnel services failed")
    }

    private fun tryService(jsch: JSch, service: TunnelService): String? {
        val session = jsch.getSession(service.user, service.host, service.port)

        // Capture banner/auth messages — some services send URL here
        val urlFromBanner = AtomicReference<String?>(null)
        session.userInfo = object : UserInfo {
            override fun getPassphrase() = ""
            override fun getPassword() = ""
            override fun promptPassword(msg: String?) = true
            override fun promptPassphrase(msg: String?) = true
            override fun promptYesNo(msg: String?) = true
            override fun showMessage(msg: String?) {
                msg?.let {
                    Log.d(TAG, "banner> $it")
                    extractUrl(it)?.let { url -> urlFromBanner.set(url) }
                }
            }
        }

        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("ServerAliveInterval", "30")
        session.setConfig("ServerAliveCountMax", "3")
        session.setConfig("PreferredAuthentications", "publickey,none,keyboard-interactive,password")
        session.setPassword("")
        session.timeout = 15000

        session.connect(15000)
        this.session = session
        Log.i(TAG, "SSH connected to ${service.host}")

        // Check if URL came in banner
        urlFromBanner.get()?.let { return it }

        // Step 1: Open shell channel with PTY BEFORE port forwarding
        // This mirrors what `ssh -R ...` does: opens a session + requests forwarding
        val shellChannel = session.openChannel("shell") as ChannelShell
        shellChannel.setPty(true)
        shellChannel.setPtyType("xterm", 80, 24, 640, 480)

        // CRITICAL: Get input stream BEFORE connect — otherwise early output is lost
        val shellInput: InputStream = shellChannel.inputStream
        shellChannel.connect(5000)
        Log.d(TAG, "Shell channel opened with PTY")

        // Step 2: Start reading shell output in background thread
        val urlFromShell = AtomicReference<String?>(null)
        val readerThread = Thread {
            try {
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 25_000
                while (System.currentTimeMillis() < deadline && urlFromShell.get() == null) {
                    val avail = shellInput.available()
                    if (avail > 0) {
                        val n = shellInput.read(buf, 0, minOf(avail, buf.size))
                        if (n > 0) {
                            val text = String(buf, 0, n)
                            Log.d(TAG, "shell> $text")
                            extractUrl(text)?.let { urlFromShell.set(it) }
                        }
                    } else {
                        Thread.sleep(100)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Shell reader: ${e.message}")
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        // Step 3: Request port forwarding — this triggers the server to output the URL
        try {
            session.setPortForwardingR(0, "localhost", localPort)
            Log.d(TAG, "Port forwarding active (port 0 → localhost:$localPort)")
        } catch (e: Exception) {
            Log.w(TAG, "Port 0 forwarding failed, trying port 80: ${e.message}")
            try {
                session.setPortForwardingR(80, "localhost", localPort)
                Log.d(TAG, "Port forwarding active (port 80 → localhost:$localPort)")
            } catch (e2: Exception) {
                Log.e(TAG, "Port forwarding failed entirely: ${e2.message}")
                shellChannel.disconnect()
                return null
            }
        }

        // Step 4: Wait for URL from shell output
        readerThread.join(25_000)

        var url = urlFromShell.get() ?: urlFromBanner.get()

        // Step 5: If shell didn't produce URL, try exec channel as fallback
        if (url == null) {
            Log.d(TAG, "Shell channel didn't produce URL, trying exec channel...")
            url = tryExecForUrl(session)
        }

        if (url == null) {
            Log.w(TAG, "Could not capture URL from ${service.host}")
            shellChannel.disconnect()
        }

        return url
    }

    private fun tryExecForUrl(session: Session): String? {
        return try {
            val channel = session.openChannel("exec") as ChannelExec
            // Get streams BEFORE connect
            val inputStream: InputStream = channel.inputStream
            val errStream: InputStream = channel.errStream
            channel.setCommand("")
            channel.connect(5000)

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 8_000

            while (System.currentTimeMillis() < deadline) {
                for (stream in listOf(inputStream, errStream)) {
                    val avail = stream.available()
                    if (avail > 0) {
                        val n = stream.read(buf, 0, minOf(avail, buf.size))
                        if (n > 0) {
                            val text = String(buf, 0, n)
                            Log.d(TAG, "exec> $text")
                            extractUrl(text)?.let { return it }
                        }
                    }
                }
                Thread.sleep(200)
            }
            channel.disconnect()
            null
        } catch (e: Exception) {
            Log.d(TAG, "Exec channel failed: ${e.message}")
            null
        }
    }

    private fun extractUrl(text: String): String? {
        // Find all URLs in the text
        val allUrls = Regex("https?://[a-zA-Z0-9._:/-]+")
            .findAll(text)
            .map { it.value.trimEnd('/') }
            .toList()

        // Prefer tunnel URLs over dashboard/docs links
        val tunnelUrl = allUrls.firstOrNull { url ->
            url.contains(".pinggy.link") ||
            url.contains(".serveo.net") ||
            url.contains(".localhost.run") ||
            url.contains(".lhr.life") ||
            url.contains(".ngrok")
        }
        if (tunnelUrl != null) {
            // Prefer HTTPS version if both exist
            val https = allUrls.firstOrNull { it.startsWith("https://") && it.contains(tunnelUrl.substringAfter("://")) }
            return https ?: tunnelUrl
        }

        return null
    }

    fun stop() {
        tunnelJob?.cancel()
        tunnelJob = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        isConnected = false
        publicUrl = ""
    }
}
