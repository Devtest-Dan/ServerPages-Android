package dev.serverpages.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    var onNetworkAvailable: (() -> Unit)? = null
    var onNetworkLost: (() -> Unit)? = null

    private var registered = false
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            onNetworkAvailable?.invoke()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            onNetworkLost?.invoke()
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        registered = true
        Log.i(TAG, "Registered network callback")
    }

    fun unregister() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister: ${e.message}")
        }
        registered = false
        Log.i(TAG, "Unregistered network callback")
    }
}
