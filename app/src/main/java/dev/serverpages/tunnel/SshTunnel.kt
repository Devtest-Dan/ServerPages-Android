package dev.serverpages.tunnel

import android.util.Log
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Public tunnel — exposes local HTTP port to the internet.
 * Tries serveo.net (free, unlimited) then pinggy.io as fallback.
 */
class SshTunnel(private val localPort: Int, private val subdomain: String = "airdeck") {

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

    private suspend fun connect(onUrlChanged: (String) -> Unit) {
        // Try serveo.net first (free, unlimited), then pinggy as fallback
        val url = tryServeo() ?: tryPinggy()

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
        } else {
            Log.e(TAG, "All tunnel services failed")
        }
    }

    private fun tryServeo(): String? {
        Log.i(TAG, "Trying serveo.net:22...")
        return try {
            val jsch = JSch()
            // No key needed — serveo authenticates via keyboard-interactive

            val session = jsch.getSession("airdeck", "serveo.net", 22)
            configureSession(session)
            session.setConfig("PreferredAuthentications", "keyboard-interactive,none")
            session.connect(15000)
            this.session = session
            Log.i(TAG, "SSH connected to serveo.net")

            connectTunnel(session, "serveo.net")
        } catch (e: Exception) {
            Log.w(TAG, "serveo.net failed: ${e.message}")
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            null
        }
    }

    private fun tryPinggy(): String? {
        Log.i(TAG, "Trying a.pinggy.io:443...")
        return try {
            val jsch = JSch()
            val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)
            val privKey = ByteArrayOutputStream()
            val pubKey = ByteArrayOutputStream()
            keyPair.writePrivateKey(privKey)
            keyPair.writePublicKey(pubKey, "airdeck")
            jsch.addIdentity("tunnel", privKey.toByteArray(), pubKey.toByteArray(), null)
            keyPair.dispose()

            val session = jsch.getSession("nokey", "a.pinggy.io", 443)
            configureSession(session)
            session.setConfig("PreferredAuthentications", "publickey,none,keyboard-interactive,password")
            session.setPassword("")
            session.connect(15000)
            this.session = session
            Log.i(TAG, "SSH connected to a.pinggy.io")

            connectTunnel(session, "a.pinggy.io")
        } catch (e: Exception) {
            Log.w(TAG, "a.pinggy.io failed: ${e.message}")
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            null
        }
    }

    private fun configureSession(session: Session) {
        // Implement both UserInfo and UIKeyboardInteractive —
        // serveo.net authenticates via keyboard-interactive (not publickey)
        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase() = ""
            override fun getPassword() = ""
            override fun promptPassword(msg: String?) = true
            override fun promptPassphrase(msg: String?) = true
            override fun promptYesNo(msg: String?) = true
            override fun showMessage(msg: String?) {
                msg?.let { Log.d(TAG, "banner> $it") }
            }
            override fun promptKeyboardInteractive(
                destination: String?, name: String?, instruction: String?,
                prompt: Array<out String>?, echo: BooleanArray?
            ): Array<String> {
                // Serveo sends empty prompts — respond with empty strings
                return Array(prompt?.size ?: 0) { "" }
            }
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("ServerAliveInterval", "30")
        session.setConfig("ServerAliveCountMax", "3")
        session.timeout = 15000
    }

    private fun connectTunnel(session: Session, serviceName: String): String? {
        // Step 1: Open shell channel with PTY BEFORE port forwarding
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

        // Step 3: Request port forwarding — triggers the server to output the URL
        // Use subdomain as bind address for Serveo fixed URL (ssh -R subdomain:80:localhost:port)
        try {
            session.setPortForwardingR(subdomain, 80, "localhost", localPort)
            Log.d(TAG, "Port forwarding active ($subdomain:80 → localhost:$localPort)")
        } catch (e: Exception) {
            Log.w(TAG, "Subdomain '$subdomain' failed, trying random: ${e.message}")
            try {
                session.setPortForwardingR(0, "localhost", localPort)
                Log.d(TAG, "Port forwarding active (random port → localhost:$localPort)")
            } catch (e2: Exception) {
                Log.e(TAG, "Port forwarding failed: ${e2.message}")
                shellChannel.disconnect()
                return null
            }
        }

        // Step 4: Wait for URL from shell output
        readerThread.join(25_000)
        val url = urlFromShell.get()

        if (url == null) {
            Log.w(TAG, "Could not capture URL from $serviceName")
            shellChannel.disconnect()
        }

        return url
    }

    private fun extractUrl(text: String): String? {
        val allUrls = Regex("https?://[a-zA-Z0-9._:/-]+")
            .findAll(text)
            .map { it.value.trimEnd('/') }
            .toList()

        // Prefer tunnel URLs over dashboard/docs links
        val tunnelUrl = allUrls.firstOrNull { url ->
            url.contains(".serveousercontent.com") ||
            url.contains(".serveo.net") ||
            url.contains(".pinggy.link") ||
            url.contains(".localhost.run") ||
            url.contains(".lhr.life") ||
            url.contains(".ngrok")
        }
        if (tunnelUrl != null) {
            // Prefer HTTPS version if both exist
            val https = allUrls.firstOrNull {
                it.startsWith("https://") && it.contains(tunnelUrl.substringAfter("://"))
            }
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
