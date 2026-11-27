package com.dazix.caelis.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dazix.caelis.service.ShutterAccessibilityService

/**
 * Everything the app needs from the system before it can tap for the user. Read fresh from the
 * platform on every resume — none of these can be cached, because the user grants them in Settings
 * and comes back.
 */
data class PermissionState(
    val accessibilityEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val notificationsGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    /**
     * Alarms & reminders. Off by default on Android 14+; without it a long-interval session still runs,
     * but on an inexact alarm that Doze may deliver minutes late (see SessionEngine.scheduleAlarm).
     */
    val exactAlarmsAllowed: Boolean = false,
    val readMediaImagesGranted: Boolean = false,
    /**
     * The user answered the photos dialog with "Select photos": only the pictures they picked are
     * readable, so capture verification cannot see the frames the camera app writes from now on.
     */
    val readMediaPartialGranted: Boolean = false,
    /** Installed outside a store, so Android hides the accessibility switch until unblocked. */
    val installedFromOutsideStore: Boolean = false,
) {
    /** Only the two the tapping itself cannot work without. */
    val readyToTap: Boolean get() = accessibilityEnabled && canDrawOverlays

    val allGranted: Boolean
        get() = readyToTap && notificationsGranted && ignoringBatteryOptimizations && exactAlarmsAllowed
}

object Permissions {

    private val KNOWN_STORES = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.amazon.venezia",
        "org.fdroid.fdroid",
    )

    fun read(context: Context): PermissionState = PermissionState(
        accessibilityEnabled = accessibilityServiceEnabled(context),
        canDrawOverlays = Settings.canDrawOverlays(context),
        notificationsGranted = notificationsGranted(context),
        ignoringBatteryOptimizations = ignoringBatteryOptimizations(context),
        exactAlarmsAllowed = exactAlarmsAllowed(context),
        readMediaImagesGranted = granted(context, photos()),
        readMediaPartialGranted = partialPhotoAccess(context),
        installedFromOutsideStore = installedFromOutsideStore(context),
    )

    /**
     * The permission that reads the camera's MediaStore rows. Below Android 13 READ_MEDIA_IMAGES does
     * not exist, and checking it there answers "denied" forever, which would hide capture verification
     * from every Android 11 and 12 user however often they granted the dialog.
     */
    fun photos(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * What to ask for when photo access is wanted. From Android 14 the selected-photos permission is
     * asked together with [photos], as the platform expects of an app that declares it, so that
     * "Select photos" is answered with it and not with a one-time READ_MEDIA_IMAGES.
     */
    fun photoRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else {
            arrayOf(photos())
        }

    /** Whether the notification permission is a runtime dialog at all (Android 13+). */
    fun notificationsNeedRuntimeGrant(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Below Android 13 notifications are on until the user turns them off in the app's settings, and
     * POST_NOTIFICATIONS is unknown to the platform: checking it there answered "denied" for an app
     * whose notifications were showing.
     */
    private fun notificationsGranted(context: Context): Boolean =
        if (notificationsNeedRuntimeGrant()) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /**
     * True while the app has *only* the selected-photos grant. Full access implies it, so the full
     * grant wins. Partial access exists from Android 14; below it there is nothing to ask.
     */
    fun partialPhotoAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks the platform which accessibility services are enabled rather than whether our service
     * object happens to be bound, so the answer is right even while the service is starting up.
     */
    fun accessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${ShutterAccessibilityService::class.java.name}"
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val enabled = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.id.equals(expected, ignoreCase = true) }
        if (enabled == true) return true
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /** Exact alarms became a grant in Android 12; on Android 11 every app may set them. */
    fun exactAlarmsNeedGrant(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun exactAlarmsAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }

    private fun installedFromOutsideStore(context: Context): Boolean = runCatching {
        val installer = context.packageManager
            .getInstallSourceInfo(context.packageName)
            .installingPackageName
        installer == null || installer !in KNOWN_STORES
    }.getOrDefault(true)

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlaySettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationExemption(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )

    /**
     * The app's own Alarms & reminders switch. Android 11 has no such screen (exact alarms need no
     * grant there, see [exactAlarmsNeedGrant]), so the row that opens it is never offered and this
     * falls back to the app's info page rather than an intent nothing can resolve.
     */
    fun exactAlarmSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            appInfo(context)
        }

    /** Where the "Allow restricted settings" menu item lives, for sideloaded builds. */
    fun appInfo(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The app's notification switch, for Android 11 and 12, where notifications have no runtime
     * dialog: the only way to turn them back on is the settings page.
     */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
