package dev.serverpages.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import org.json.JSONArray
import java.net.NetworkInterface

/**
 * Implements libtailscale.AppContext for an embedded Tailscale node.
 * Modeled on the official tailscale-android App.kt but trimmed to skip
 * MDM, hardware attestation, ShareFileHelper, and client logging.
 */
class TailscaleAppContext(private val context: Context) : libtailscale.AppContext {

    companion object {
        private const val TAG = "TailscaleAppContext"
        private const val SECRET_PREFS = "tailscale_secret_prefs"
    }

    private val gson = Gson()

    private val encryptedPrefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECRET_PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun log(tag: String?, line: String?) {
        Log.d(tag ?: "", line ?: "")
    }

    override fun encryptToPref(prefKey: String?, plaintext: String?) {
        encryptedPrefs.edit().putString(prefKey, plaintext).commit()
    }

    override fun decryptFromPref(prefKey: String?): String? =
        encryptedPrefs.getString(prefKey, null)

    override fun getStateStoreKeysJSON(): String {
        val prefix = "statestore-"
        val keys = encryptedPrefs.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
        return JSONArray(keys).toString()
    }

    override fun getOSVersion(): String = try {
        Build.VERSION.RELEASE ?: "0"
    } catch (e: Throwable) {
        Log.w(TAG, "getOSVersion threw", e); "0"
    }

    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    override fun getDeviceName(): String = dev.serverpages.BuildConfig.TAILSCALE_HOSTNAME.ifEmpty { "AirDeck" }

    override fun getInstallSource(): String = "airdeck"

    private fun safeLog(name: String, e: Throwable) {
        Log.w(TAG, "$name threw: ${e.message}")
    }


    override fun isChromeOS(): Boolean = false

    override fun isClientLoggingEnabled(): Boolean = false

    override fun shouldUseGoogleDNSFallback(): Boolean { return false }

    private data class AddrJson(val ip: String, val prefixLen: Int)
    private data class InterfaceJson(
        val name: String,
        val index: Int,
        val mtu: Int,
        val up: Boolean,
        val broadcast: Boolean,
        val loopback: Boolean,
        val pointToPoint: Boolean,
        val multicast: Boolean,
        val addrs: List<AddrJson>,
    )

    override fun getInterfacesAsJson(): String {
        val interfaces = try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces: ${e.message}")
            return "[]"
        }
        val out = ArrayList<InterfaceJson>(interfaces.size)
        for (nif in interfaces) {
            try {
                val addrs = ArrayList<AddrJson>()
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    val host = addr.hostAddress ?: continue
                    addrs.add(AddrJson(host, ia.networkPrefixLength.toInt()))
                }
                out.add(
                    InterfaceJson(
                        name = nif.name ?: "",
                        index = nif.index,
                        mtu = runCatching { nif.mtu }.getOrDefault(0),
                        up = runCatching { nif.isUp }.getOrDefault(false),
                        broadcast = runCatching { nif.supportsMulticast() }.getOrDefault(false),
                        loopback = runCatching { nif.isLoopback }.getOrDefault(false),
                        pointToPoint = runCatching { nif.isPointToPoint }.getOrDefault(false),
                        multicast = runCatching { nif.supportsMulticast() }.getOrDefault(false),
                        addrs = addrs,
                    )
                )
            } catch (_: Exception) {
                continue
            }
        }
        return gson.toJson(out)
    }

    override fun getPlatformDNSConfig(): String = ""

    override fun getSyspolicyStringValue(key: String?): String =
        throw UnsupportedOperationException("no syspolicy")

    override fun getSyspolicyBooleanValue(key: String?): Boolean =
        throw UnsupportedOperationException("no syspolicy")

    override fun getSyspolicyStringArrayJSONValue(key: String?): String =
        throw UnsupportedOperationException("no syspolicy")

    override fun hardwareAttestationKeySupported(): Boolean = false
    override fun hardwareAttestationKeyCreate(): String =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeyRelease(id: String?) =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeyPublic(id: String?): ByteArray =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeySign(id: String?, data: ByteArray?): ByteArray =
        throw UnsupportedOperationException()

    override fun hardwareAttestationKeyLoad(id: String?) =
        throw UnsupportedOperationException()

    override fun bindSocketToNetwork(fd: Int): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        return try {
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                net.bindSocket(pfd.fileDescriptor)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToNetwork fd=$fd failed: ${e.message}")
            false
        }
    }

    override fun getUserCACertsPEM(): ByteArray {
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val sb = StringBuilder()
            val encoder = Base64.NO_WRAP
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("user:")) continue
                val cert = ks.getCertificate(alias) ?: continue
                val pem = Base64.encodeToString(cert.encoded, encoder)
                sb.append("-----BEGIN CERTIFICATE-----\n")
                pem.chunked(64).forEach { sb.append(it).append('\n') }
                sb.append("-----END CERTIFICATE-----\n")
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "getUserCACertsPEM failed: ${e.message}")
            ByteArray(0)
        }
    }
}
