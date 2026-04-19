package dev.serverpages.dannet

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import dev.serverpages.admin.DeviceAdminReceiver
import java.io.File

class DanNetInstaller(private val context: Context) {

    companion object {
        private const val TAG = "DanNetInstaller"
        const val DANNET_PACKAGE = "com.dannet"
        private const val PREFS_NAME = "airdeck"
        private const val KEY_INSTALLED = "dannet_installed"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, DeviceAdminReceiver::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(DANNET_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installIfNeeded() {
        if (isInstalled()) {
            Log.i(TAG, "DanNet already installed — ensuring protection")
            ensureProtected()
            return
        }
        Log.i(TAG, "DanNet not installed — starting silent install")
        val apkFile = extractApkFromAssets()
        silentInstall(apkFile)
    }

    private fun extractApkFromAssets(): File {
        val outFile = File(context.cacheDir, "dannet.apk")
        context.assets.open("dannet.apk").use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Extracted dannet.apk to ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile
    }

    private fun silentInstall(apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setInstallReason(PackageManager.INSTALL_REASON_POLICY)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        apkFile.inputStream().use { input ->
            session.openWrite("dannet", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent(context, InstallResultReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session.commit(pendingIntent.intentSender)
        session.close()

        Log.i(TAG, "Silent install session committed (id=$sessionId)")
    }

    fun ensureProtected() {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not Device Owner — cannot protect DanNet")
            return
        }
        dpm.setUninstallBlocked(admin, DANNET_PACKAGE, true)
        Log.i(TAG, "DanNet uninstall blocked")
        runCatching {
            dpm.setAlwaysOnVpnPackage(admin, DANNET_PACKAGE, true)
            Log.i(TAG, "DanNet always-on VPN enabled")
        }.onFailure { e ->
            Log.w(TAG, "Could not set always-on VPN: ${e.message}")
        }
        prefs.edit().putBoolean(KEY_INSTALLED, true).apply()
    }
}
