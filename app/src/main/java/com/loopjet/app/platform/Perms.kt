package com.loopjet.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The permission matrix, in code.
 *
 * Each entry says *why* it is needed so the UI can explain before the system dialog appears, and
 * every capability has a refusal path: no nearby-Wi-Fi ⇒ LAN/hotspot paths, no all-files ⇒ SAF
 * folder, no notification permission ⇒ silent foreground service.
 */
object Perms {
    /** Android 17 runtime permission for LAN access; asked on its own prompt. */
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    data class Requirement(val permission: String, val title: String, val why: String)

    fun runtimeRequirements(context: Context, forReceive: Boolean): List<Requirement> {
        val list = ArrayList<Requirement>()
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= 33) {
            list.add(
                Requirement(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    "Nearby Wi-Fi devices",
                    "Needed to create a direct Wi-Fi link, a hotspot and to find the other phone. Location is not used."
                )
            )
            list.add(
                Requirement(
                    Manifest.permission.POST_NOTIFICATIONS,
                    "Notifications",
                    "Shows live speed and lets you pause or cancel from the shade."
                )
            )
        }
        if (sdk >= 31) {
            list.add(Requirement(Manifest.permission.BLUETOOTH_SCAN, "Bluetooth scan", "Finds phones without any network."))
            list.add(Requirement(Manifest.permission.BLUETOOTH_ADVERTISE, "Bluetooth advertise", "Makes this phone findable offline."))
        }
        if (sdk in 31..32) {
            list.add(Requirement(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "Android 12 requires it for Wi-Fi Direct scanning."))
        }
        if (sdk >= 37) {
            list.add(
                Requirement(
                    LOCAL_NETWORK,
                    "Local network",
                    "Android 17 blocks LAN access without this. FlowLink never talks to the internet."
                )
            )
        }
        if (sdk in 26..29) {
            list.add(Requirement(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "Required by Android 8-10 for Wi-Fi Direct and hotspot."))
            list.add(Requirement(Manifest.permission.READ_EXTERNAL_STORAGE, "Storage read", "Lets you pick files to send."))
            list.add(Requirement(Manifest.permission.WRITE_EXTERNAL_STORAGE, "Storage write", "Saves received files into Downloads."))
        }
        if (sdk in 30..32) {
            list.add(Requirement(Manifest.permission.READ_EXTERNAL_STORAGE, "Storage read", "Lets you pick files to send."))
        }
        if (sdk >= 33) {
            list.add(Requirement(Manifest.permission.READ_MEDIA_IMAGES, "Photos", "Browse photos and videos to send."))
            list.add(Requirement(Manifest.permission.READ_MEDIA_VIDEO, "Videos", "Browse videos to send."))
        }
        return list
    }

    /**
     * Only what the *picker* needs to list files. Requested when the Send screen opens, not bundled
     * with the hotspot/Bluetooth set: until this existed, tapping Send showed empty Photos, Videos,
     * Music and Downloads on Android 13+, because those permissions were only ever asked for on
     * Receive. Reading the whole file tree (all-files access) makes these redundant.
     */
    fun pickerRequirements(context: Context): List<Requirement> {
        if (hasAllFiles(context)) return emptyList()
        val sdk = Build.VERSION.SDK_INT
        val list = ArrayList<Requirement>()
        when {
            sdk >= 34 -> {
                list.add(Requirement(Manifest.permission.READ_MEDIA_IMAGES, "Photos", "Lists photos to send."))
                list.add(Requirement(Manifest.permission.READ_MEDIA_VIDEO, "Videos", "Lists videos to send."))
                list.add(Requirement(Manifest.permission.READ_MEDIA_AUDIO, "Music", "Lists music to send."))
                // Lets the user grant only chosen photos instead of the whole library.
                list.add(Requirement(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, "Selected photos", "Adds only the photos you pick."))
            }
            sdk == 33 -> {
                list.add(Requirement(Manifest.permission.READ_MEDIA_IMAGES, "Photos", "Lists photos to send."))
                list.add(Requirement(Manifest.permission.READ_MEDIA_VIDEO, "Videos", "Lists videos to send."))
                list.add(Requirement(Manifest.permission.READ_MEDIA_AUDIO, "Music", "Lists music to send."))
            }
            sdk >= 30 -> list.add(Requirement(Manifest.permission.READ_EXTERNAL_STORAGE, "Storage read", "Lists files to send."))
            else -> {
                list.add(Requirement(Manifest.permission.READ_EXTERNAL_STORAGE, "Storage read", "Lists files to send."))
                list.add(Requirement(Manifest.permission.WRITE_EXTERNAL_STORAGE, "Storage write", "Saves received files."))
            }
        }
        return list
    }

    /** True when media categories can be listed at all. */
    fun canListMedia(context: Context): Boolean {
        if (hasAllFiles(context)) return true
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= 33) {
            val images = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val video = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val partial = sdk >= 34 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
            return images || video || partial
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context, requirements: List<Requirement>): List<Requirement> =
        requirements.filter { ContextCompat.checkSelfPermission(context, it.permission) != PackageManager.PERMISSION_GRANTED }

    fun hasAllFiles(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun allFilesIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 30) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun batteryIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** The only route back once a permission has been refused "for good". */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * The OEM battery-killer guide, written as data so the Settings screen can render it and the
     * README can quote the same text.
     */
    val batteryGuide: List<Pair<String, String>> = listOf(
        "Xiaomi / Redmi / POCO" to "Settings › Apps › Manage apps › FlowLink › Autostart ON, Battery saver = No restrictions.",
        "Samsung" to "Settings › Battery and device care › Battery › Background usage limits: remove FlowLink from Sleeping apps.",
        "OnePlus / OPPO / realme" to "Settings › Battery › App battery management › FlowLink: allow background activity; disable 'Optimise automatically'.",
        "vivo / iQOO" to "Settings › Battery › High background power consumption › allow FlowLink.",
        "Huawei / Honor" to "Settings › Battery › App launch › FlowLink: manage manually, enable all three switches.",
        "Anything else" to "Settings › Apps › FlowLink › Battery › Unrestricted, plus allow background data usage."
    )
}
