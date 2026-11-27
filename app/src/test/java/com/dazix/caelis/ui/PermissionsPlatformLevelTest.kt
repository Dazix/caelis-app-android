package com.dazix.caelis.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.verify.CaptureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `minSdk` is 30, and three of the permissions the app reads changed shape after Android 11: the photos
 * permission (READ_MEDIA_IMAGES from 13), the notification dialog (POST_NOTIFICATIONS from 13) and the
 * exact-alarm grant (from 12). Checking the newer name on an older platform compiles, runs and answers
 * "denied" forever, so each one is pinned here on both sides of the level it changed at.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsPlatformLevelTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 reads photos through READ_EXTERNAL_STORAGE`() {
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, Permissions.photos())
        assertFalse(Permissions.read(context).readMediaImagesGranted)
        assertFalse(CaptureVerifier(context).available())

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertTrue(Permissions.read(context).readMediaImagesGranted)
        assertTrue(CaptureVerifier(context).available())
        assertFalse(
            "partial photo access does not exist before Android 14",
            Permissions.read(context).readMediaPartialGranted,
        )
    }

    @Test
    @Config(sdk = [33])
    fun `android 13 reads photos through READ_MEDIA_IMAGES only`() {
        assertEquals(Manifest.permission.READ_MEDIA_IMAGES, Permissions.photos())

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertFalse(
            "READ_EXTERNAL_STORAGE is capped at API 32 and must not count on 13",
            Permissions.read(context).readMediaImagesGranted,
        )
        assertFalse(CaptureVerifier(context).available())

        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(Permissions.read(context).readMediaImagesGranted)
        assertTrue(CaptureVerifier(context).available())
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 asks for READ_EXTERNAL_STORAGE`() {
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), Permissions.photoRequest().toList())
    }

    @Test
    @Config(sdk = [33])
    fun `android 13 asks for READ_MEDIA_IMAGES alone`() {
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), Permissions.photoRequest().toList())
    }

    @Test
    @Config(sdk = [34])
    fun `android 14 asks for selected photos too and tells them from full access`() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            Permissions.photoRequest().toList(),
        )

        // "Select photos" with the permission declared: only the selected-photos grant.
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertFalse(Permissions.read(context).readMediaImagesGranted)
        assertTrue(Permissions.read(context).readMediaPartialGranted)
        assertFalse(CaptureVerifier(context).available())

        // "Allow all" grants both; full access wins.
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(Permissions.read(context).readMediaImagesGranted)
        assertFalse(Permissions.read(context).readMediaPartialGranted)
        assertTrue(CaptureVerifier(context).available())
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 notifications follow the app switch, not POST_NOTIFICATIONS`() {
        assertFalse(Permissions.notificationsNeedRuntimeGrant())
        assertTrue(
            "notifications are on by default below Android 13",
            Permissions.read(context).notificationsGranted,
        )

        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(false)
        assertFalse(Permissions.read(context).notificationsGranted)

        val intent = Permissions.notificationSettings(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [33])
    fun `android 13 notifications need POST_NOTIFICATIONS`() {
        assertTrue(Permissions.notificationsNeedRuntimeGrant())
        assertFalse(Permissions.read(context).notificationsGranted)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Permissions.read(context).notificationsGranted)
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 allows exact alarms without a grant and offers no alarm screen`() {
        assertFalse(Permissions.exactAlarmsNeedGrant())
        assertTrue(Permissions.read(context).exactAlarmsAllowed)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Permissions.exactAlarmSettings(context).action,
        )
    }

    @Test
    @Config(sdk = [31])
    fun `android 12 sends the user to the Alarms and reminders switch`() {
        assertTrue(Permissions.exactAlarmsNeedGrant())
        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Permissions.exactAlarmSettings(context).action,
        )
    }
}
