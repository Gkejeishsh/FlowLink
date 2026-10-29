package com.loopjet.app.platform.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.loopjet.app.platform.SendItem
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val apkPaths: List<String>,
    val minSdk: Int,
    val targetSdk: Int,
    val nativeAbis: List<String>,
    val system: Boolean,
    val icon: Drawable?
)

/**
 * Reads installed apps straight from PackageManager. The base APK and every split are collected in
 * one item — sending only `base.apk` is the classic bug that makes transferred apps crash.
 */
class AppRepository(private val context: Context) {

    suspend fun load(includeSystem: Boolean = false): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val apps = ArrayList<InstalledApp>()
        for (resolve in activities) {
            val packageName = resolve.activityInfo?.packageName ?: continue
            if (!seen.add(packageName)) continue
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: continue
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && !includeSystem) continue
            val paths = ArrayList<String>()
            info.sourceDir?.let { paths.add(it) }
            info.splitSourceDirs?.forEach { if (it != null) paths.add(it) }
            val totalSize = paths.sumOf { File(it).length() }
            apps.add(
                InstalledApp(
                    packageName = packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    versionName = runCatching<String?> { pm.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                    versionCode = packageVersionCode(pm, packageName),
                    size = totalSize,
                    apkPaths = paths,
                    minSdk = if (Build.VERSION.SDK_INT >= 24) info.minSdkVersion else 0,
                    targetSdk = info.targetSdkVersion,
                    nativeAbis = nativeAbisOf(info),
                    system = system,
                    icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                )
            )
        }
        apps.sortBy { it.label.lowercase() }
        apps
    }

    /**
     * ABIs the app ships code for. `nativeLibraryDir` names the directory this device uses
     * (`.../lib/arm64`), so it is reported as the single ABI that is certain to be present.
     */
    private fun nativeAbisOf(info: ApplicationInfo): List<String> {
        val dir = info.nativeLibraryDir ?: return emptyList()
        val abi = dir.substringAfterLast('/')
        if (abi.isBlank()) return emptyList()
        val normalised = when (abi) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> abi
        }
        return listOf(normalised)
    }

    private fun packageVersionCode(pm: PackageManager, packageName: String): Long {
        return runCatching {
            val info = pm.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
    }

    /** Turns an app into send items: one per APK, grouped by package name. */
    fun toSendItems(app: InstalledApp, saveGroupFolder: String = "Apps"): List<SendItem> {
        val safeLabel = app.label.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { app.packageName }
        return app.apkPaths.mapIndexed { index, path ->
            val file = File(path)
            val splitName = when {
                index == 0 -> "base.apk"
                else -> file.name
            }
            SendItem(
                key = "app:${app.packageName}:$splitName",
                displayName = if (index == 0) "${app.label}.apk" else "${app.label} ($splitName)",
                relativePath = "$saveGroupFolder/$safeLabel/$splitName",
                size = file.length(),
                mtime = file.lastModified(),
                mime = "application/vnd.android.package-archive",
                filePath = path,
                appPackage = app.packageName,
                appLabel = app.label,
                appVersionName = app.versionName,
                appVersionCode = app.versionCode,
                splitName = splitName,
                isBaseApk = index == 0,
                kind = com.loopjet.app.core.FileKind.APP_APK,
                appMinSdk = app.minSdk,
                appTargetSdk = app.targetSdk,
                appAbis = app.nativeAbis
            )
        }
    }
}

/**
 * Installs a received app from all of its APKs in a single PackageInstaller session, which is the
 * only way Android accepts splits together. Errors are translated into sentences a person can act on.
 */
class AppInstaller(private val context: Context) {

    fun canInstall(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(true)

    fun install(
        packageName: String,
        apkFiles: List<File>,
        onProgress: (Float) -> Unit,
        onResult: (Boolean, String) -> Unit
    ) {
        if (apkFiles.isEmpty()) {
            onResult(false, "No APK files to install")
            return
        }
        val installer = context.packageManager.packageInstaller
        val totalBytes = apkFiles.sumOf { it.length() }
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(totalBytes)
                // Best effort: a privileged app can skip the confirm dialog, a normal one cannot. Never
                // let the attempt abort an install that would otherwise work.
                if (Build.VERSION.SDK_INT >= 31) {
                    runCatching { setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) }
                }
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            var written = 0L
            for (file in apkFiles) {
                val name = if (file.name.contains("base")) "base.apk" else file.name
                session.openWrite(name, 0, file.length()).use { out ->
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (totalBytes > 0) onProgress(written.toFloat() / totalBytes)
                        }
                    }
                    session.fsync(out)
                }
            }
            val action = "com.loopjet.app.INSTALL_RESULT.$sessionId"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                    val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    runCatching { context.unregisterReceiver(this) }
                    when (status) {
                        PackageInstaller.STATUS_SUCCESS -> onResult(true, "Installed")
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirm != null) {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(confirm) }
                                onResult(true, "Waiting for your confirmation")
                            } else {
                                onResult(false, "Android needs confirmation but gave no dialog")
                            }
                        }
                        PackageInstaller.STATUS_FAILURE_ABORTED -> onResult(false, "Install cancelled")
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> onResult(false, "Install blocked by device policy")
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> onResult(
                            false,
                            "A different version with another signature is installed. Uninstall it first."
                        )
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> onResult(
                            false,
                            "This app is not compatible with this phone or Android version."
                        )
                        PackageInstaller.STATUS_FAILURE_STORAGE -> onResult(false, "Not enough storage for the install")
                        else -> onResult(false, explain(message))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setPackage(context.packageName),
                flags
            )
            session.commit(pending.intentSender)
            session.close()
        } catch (t: Throwable) {
            Log.w("FlowLinkInstall", "install failed", t)
            onResult(false, explain(t.message ?: "Install failed"))
        }
    }

    private fun explain(message: String): String = when {
        message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
            "The installed copy is signed with a different key. Uninstall it first."
        message.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
            "The installed version is newer. Uninstall it first to downgrade."
        message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> "Not enough storage"
        message.contains("INSTALL_FAILED_INVALID_APK") -> "The APK is damaged"
        message.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> "This app has no code for this phone's CPU"
        message.isBlank() -> "Install failed"
        else -> message
    }

    fun permissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    /** Compatibility verdict shown before sending, using the receiver's advertised SDK. */
    fun compatibilityWarning(app: InstalledApp, receiverSdk: Int, receiverAbis: List<String>): String? {
        if (receiverSdk > 0 && app.minSdk > receiverSdk) {
            return "${app.label} needs Android ${app.minSdk} but the receiver runs SDK $receiverSdk."
        }
        if (app.nativeAbis.isNotEmpty() && receiverAbis.isNotEmpty() &&
            app.nativeAbis.none { it in receiverAbis }
        ) {
            return "${app.label} ships ${app.nativeAbis.joinToString()} code; the receiver has ${receiverAbis.joinToString()}."
        }
        return null
    }

}
