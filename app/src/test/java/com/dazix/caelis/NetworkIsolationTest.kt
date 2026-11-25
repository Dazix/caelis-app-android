package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app declares `INTERNET`, and that permission cannot be taken back.
 *
 * `INTERNET` has protection level `normal`: Android grants it at install time, offers no runtime
 * API for it and shows the user no switch. Before the terrain horizon layer existed, the absence of
 * that line in the manifest was a guarantee the operating system enforced — worth stating plainly in
 * an app that also holds the accessibility permission.
 *
 * That guarantee is now the source's to keep, so it is kept here. This test walks the shipped Kotlin
 * and fails the build the moment a second place in the app learns how to open a connection. It is
 * the reason the claim in the README and in the manifest comment can still be checked rather than
 * merely believed.
 *
 * The same reasoning covers ARCore, which is a client for a Google component that reports to Google on
 * its own terms: the app cannot see or limit that, so what is pinned here is that exactly one file can
 * start it and that file asks the user's consent in front of every step.
 */
class NetworkIsolationTest {

    /**
     * The single file allowed to touch the network, as a path suffix.
     *
     * If this list ever needs a second entry, that is the decision to argue about — not the test.
     */
    private val allowedNetworkFiles = listOf(
        "com/dazix/caelis/data/horizon/DemTileDownloader.kt",
    )

    /**
     * Files that may name a network *permission* or a connectivity API without opening anything.
     * [com.dazix.caelis.data.horizon.NetworkAvailability] asks the package manager what this build
     * declares; that is the opposite of making a request.
     */
    private val allowedConnectivityFiles = allowedNetworkFiles + listOf(
        "com/dazix/caelis/data/horizon/NetworkAvailability.kt",
    )

    /** Ways to open a socket. Matched against source text, so a new one has to be added knowingly. */
    private val networkMarkers = listOf(
        "java.net.",
        "HttpURLConnection",
        "openConnection(",
        "java.net.Socket",
        "URLConnection",
        "OkHttp",
        "Retrofit",
        "WebSocket",
        "DatagramSocket",
        "ServerSocket",
    )

    private val permittedNetworkPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    @Test
    fun `only the tile downloader opens a connection`() {
        val sources = mainSources()
        assertTrue("no Kotlin sources found — wrong working directory?", sources.size > 50)

        val offenders = mutableListOf<String>()
        for (file in sources) {
            val relative = file.invariantPath()
            if (allowedConnectivityFiles.any { relative.endsWith(it) }) continue

            val text = file.readText()
            for (marker in networkMarkers) {
                if (text.contains(marker)) offenders += "$relative uses '$marker'"
            }
        }

        assertEquals(
            "network access must stay inside ${allowedNetworkFiles.single()}",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the downloader really is where the network lives`() {
        val downloader = mainSources().single { it.invariantPath().endsWith(allowedNetworkFiles.single()) }
        val text = downloader.readText()

        assertTrue("the allowed file no longer opens anything — is the allowance stale?", text.contains("openConnection("))
        assertTrue("consent must be consulted before connecting", text.contains("if (!consent())"))
    }

    @Test
    fun `the manifest declares no network permission beyond the two the tile fetch needs`() {
        val manifest = manifestFile()
        val declared = Regex("""uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filter { it.contains("INTERNET") || it.contains("NETWORK") || it.contains("SOCKET") }
            .toSet()

        assertEquals(permittedNetworkPermissions, declared)
    }

    /**
     * Removing the `INTERNET` line has to stay a supported way to build this from source, which
     * means nothing may hard-depend on it. The runtime check is what lets the UI adapt instead.
     */
    @Test
    fun `the build can tell at runtime whether it has the permission at all`() {
        val availability = mainSources()
            .single { it.invariantPath().endsWith("com/dazix/caelis/data/horizon/NetworkAvailability.kt") }
            .readText()

        assertTrue(availability.contains("fun hasInternetPermission"))
        assertTrue(availability.contains("checkPermission"))
    }

    // ---- ARCore: Google Play Services for AR is consent-gated, and only one file can start it ----

    private val arCoreController = "com/dazix/caelis/ar/pose/ArCoreSessionController.kt"

    /**
     * Files allowed to *import* ARCore types at all (a pose source reads `Frame` and `Pose`). Only
     * the controller may start, probe or install it - see the next test - so this list never makes
     * a second way in.
     */
    private val arCoreImportDir = "com/dazix/caelis/ar/pose/"

    /**
     * ARCore is a client for Google Play Services for AR, which reports to Google on its own terms.
     * The app's old promise (nothing leaves the device unless the user said yes) only survives having
     * that dependency if the decision to start it is in exactly one reviewable place.
     */
    @Test
    fun `only the session controller constructs, probes or installs ARCore`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val relative = file.invariantPath()
            if (relative.endsWith(arCoreController)) continue
            val text = file.readText()

            val importsArCore = text.contains("com.google.ar.core")
            if (importsArCore && !relative.contains(arCoreImportDir)) {
                offenders += "$relative imports com.google.ar.core outside ar/pose/"
            }
            if (importsArCore && Regex("""(?<![\w.])Session\(""").containsMatchIn(text)) {
                offenders += "$relative constructs a Session"
            }
            for (marker in listOf("com.google.ar.core.Session(", "createForSharedCamera(", "ArCoreApk", "requestInstall(", "checkAvailability(")) {
                if (text.contains(marker)) offenders += "$relative uses '$marker'"
            }
        }

        assertEquals(
            "ARCore may only be started from $arCoreController, behind the user's consent",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the session controller consults consent in front of everything that reaches Google`() {
        val text = mainSources().single { it.invariantPath().endsWith(arCoreController) }.readText()

        val check = body(text, "suspend fun checkAvailability()")
        val install = body(text, "suspend fun requestInstallFromUserAction(")
        val resume = body(text, "suspend fun resume()")
        val consentCheck = "if (!consent())"
        val snapshot = "val startedIn = generation"
        val staleCheck = "if (generation != startedIn) return _status.value"

        // Each entry point first snapshots the generation and then reads consent, nothing else. The
        // snapshot has to come before the read: a "yes" read just before a withdrawal closed the
        // controller is only recognisable as stale against a generation taken before it.
        for ((name, fn) in listOf("checkAvailability" to check, "requestInstall" to install, "resume" to resume)) {
            val code = fn.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            assertEquals("$name must snapshot the generation first", snapshot, code[0])
            assertTrue("$name must check consent straight after", code[1].startsWith(consentCheck))
            // A consent read suspends; whatever happened meanwhile (a close, a withdrawal) wins.
            code.forEachIndexed { i, line ->
                if (line.startsWith(consentCheck)) {
                    assertEquals("$name: a consent read must be followed by a generation check", staleCheck, code.getOrNull(i + 1))
                }
            }
        }

        // The consent read has to be the last thing before the constructor: any suspension between
        // the two (an availability retry, a Geospatial read) is a window in which it may have changed.
        val beforeConstruction = resume.substringBefore("platform.createSession(")
        assertTrue("resume no longer constructs a session?", resume.contains("platform.createSession("))
        val lastConsent = beforeConstruction.lastIndexOf(consentCheck)
        assertTrue("no consent check in front of the constructor", lastConsent >= 0)
        assertTrue(
            "the Geospatial read must come before the final consent check, not after it",
            lastConsent > beforeConstruction.lastIndexOf("geospatialConsent()"),
        )
        assertFalse(
            "nothing may suspend between the last consent check and the constructor",
            beforeConstruction.substring(lastConsent).let { it.contains("delay(") || it.contains("checkAvailability()") },
        )
        assertTrue(
            "the last consent check before the constructor must be followed by a generation check",
            beforeConstruction.substring(lastConsent).contains(staleCheck),
        )

        // Each Google-facing call has exactly one call site, inside the function that checked.
        assertEquals(1, Regex("""platform\.createSession\(""").findAll(text).count())
        assertEquals(1, Regex("""platform\.requestInstall\(""").findAll(text).count())
        assertEquals(1, Regex("""platform\.checkAvailability\(""").findAll(text).count())
        assertTrue(install.contains("platform.requestInstall("))
        // probe() is the one wrapper around the availability call and only checkAvailability uses it.
        val probeCalls = Regex("""\bprobe\(\)""").findAll(text).count()
        val probeCallsInCheck = Regex("""\bprobe\(\)""").findAll(check).count()
        assertEquals("probe() is called outside checkAvailability", probeCallsInCheck + 1, probeCalls)

        // Geospatial is never requested without both the user's yes and a key in this build.
        assertTrue(resume.contains("geospatialApiKeyAvailable && geospatialConsent()"))
    }

    @Test
    fun `an install can only be requested from a screen`() {
        val callers = mainSources()
            .filter { it.invariantPath().let { p -> !p.endsWith(arCoreController) } }
            .filter { it.readText().contains("requestInstallFromUserAction(") }
            .map { it.invariantPath() }

        // The controller cannot tell a click from a lifecycle callback, so the places that may call
        // it are the ones that only run from a click: Compose screens.
        assertEquals(emptyList<String>(), callers.filterNot { it.contains("com/dazix/caelis/ui/") })
    }

    @Test
    fun `ARCore is declared optional and the Geospatial key is only ever a build placeholder`() {
        val manifest = manifestFile().readText()

        assertTrue(
            "ARCore must be optional, never required",
            Regex("""<meta-data\s+android:name="com\.google\.ar\.core"\s+android:value="optional"\s*/>""")
                .containsMatchIn(manifest),
        )
        assertTrue(
            "the API key must come from the build, not from the manifest source",
            Regex("""android:name="com\.google\.android\.ar\.API_KEY"\s+android:value="\$\{geospatialApiKey\}"""")
                .containsMatchIn(manifest),
        )
    }

    @Test
    fun `the manifest declares exactly the permissions the app is known to need`() {
        val declared = Regex("""uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifestFile().readText())
            .map { it.groupValues[1] }
            .toSet()

        // Adding ARCore added none. A new entry here is a decision to make on purpose, not a
        // side effect of a dependency.
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.WAKE_LOCK",
                // Never USE_EXACT_ALARM: Play reserves it for alarm clocks and calendars.
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "android.permission.READ_MEDIA_IMAGES",
                // What "Select photos" grants on Android 14+; undeclared, that choice reported
                // READ_MEDIA_IMAGES as granted while only the picked photos could be read.
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                // READ_MEDIA_IMAGES below Android 13 (minSdk 30); capped, see the test below.
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
            ),
            declared,
        )
    }

    /**
     * READ_EXTERNAL_STORAGE is only the Android 11 and 12 spelling of the photos permission. Uncapped,
     * Play lists it as a broad storage permission on every newer phone, where it grants nothing anyway.
     */
    @Test
    fun `the old storage permission stops at Android 12`() {
        val entry = Regex("""<uses-permission\s[^>]*android:name="android\.permission\.READ_EXTERNAL_STORAGE"[^>]*>""")
            .find(manifestFile().readText())
            ?.value
        assertTrue("READ_EXTERNAL_STORAGE is not declared", entry != null)
        assertTrue(
            "READ_EXTERNAL_STORAGE must carry android:maxSdkVersion=\"32\", found: $entry",
            entry!!.contains("""android:maxSdkVersion="32""""),
        )
    }

    /**
     * QUERY_ALL_PACKAGES would hand the app the list of everything installed. What it needs to see is
     * declared instead: the launchable apps a session can target, the cameras among them, and ARCore.
     */
    @Test
    fun `the app sees only the packages it can target, not every installed one`() {
        val manifest = manifestFile().readText()
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1)
            ?: error("the manifest declares no <queries>, so the target picker would list nothing")

        listOf(
            "android.intent.category.LAUNCHER",
            "android.media.action.STILL_IMAGE_CAMERA",
            "android.media.action.IMAGE_CAPTURE",
        ).forEach { assertTrue("<queries> lacks $it", queries.contains("android:name=\"$it\"")) }
        assertTrue(
            "ArCorePresence looks ARCore up by name",
            queries.contains("""<package android:name="com.google.ar.core" />"""),
        )
        assertFalse("QueryAllPackagesPermission must not be silenced", manifest.contains("QueryAllPackages"))
    }

    /**
     * A stored horizon profile is named after where the user stood and holds the exact coordinates, so
     * the folder is a location history. Android's Auto Backup takes everything not excluded to the
     * Google account; this pins that the profiles are among what it does not take.
     */
    @Test
    fun `the cloud backup takes no location history`() {
        val manifest = manifestFile().readText()
        assertTrue(
            "allowBackup without rules backs up every file",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
        )

        val rules = manifestFile().resolveSibling("res/xml/data_extraction_rules.xml").readText()
        val cloud = Regex("""<cloud-backup>(.*?)</cloud-backup>""", RegexOption.DOT_MATCHES_ALL)
            .find(rules)?.groupValues?.get(1)
            ?: error("data_extraction_rules.xml has no <cloud-backup> section")
        listOf("horizons/", "locations/", "dem_offline/", "debug/").forEach { path ->
            assertTrue(
                "the cloud backup still takes filesDir/$path",
                cloud.contains("""<exclude domain="file" path="$path" />"""),
            )
        }
    }

    /** A key in the repository is a key in everyone's clone and in every APK anyone built from it. */
    @Test
    fun `no Google API key is committed and the build reads it from the machine`() {
        val appDir = firstExisting(File("app"), File("."))
        val keyPattern = Regex("""AIza[0-9A-Za-z_\-]{35}""")
        val textExtensions = setOf("kt", "kts", "xml", "properties", "md", "json", "sh", "gradle", "txt")

        val offenders = appDir.resolve("src").walkTopDown()
            .filter { it.isFile && it.extension in textExtensions }
            .plus(listOf(appDir.resolve("build.gradle.kts"), appDir.resolve("../gradle.properties")).filter { it.isFile })
            .filter { keyPattern.containsMatchIn(it.readText()) }
            .map { it.invariantPath() }
            .toList()
        assertEquals("a Google API key is written into the sources", emptyList<String>(), offenders)

        val buildScript = appDir.resolve("build.gradle.kts").readText()
        assertTrue(buildScript.contains("geospatial.api.key"))
        assertTrue(buildScript.contains("CAELIS_GEOSPATIAL_API_KEY"))
        assertTrue(buildScript.contains("manifestPlaceholders[\"geospatialApiKey\"]"))
        assertTrue(buildScript.contains("HAS_GEOSPATIAL_API_KEY"))

        val gitignore = appDir.resolve("../.gitignore").readLines().map { it.trim() }
        assertTrue("local.properties holds the key and must stay untracked", "local.properties" in gitignore)
    }

    /**
     * The text of the member that starts at [signature], up to the next member of the class, which
     * the codebase indents by exactly four spaces.
     */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found in the controller", start >= 0)
        val rest = text.substring(start + signature.length)
        val end = Regex("""\n    (?:/\*\*|fun |suspend fun |private |internal |companion |var |val |sealed |enum )""")
            .find(rest)?.range?.first ?: rest.length
        // Skip the rest of the signature line and its opening brace.
        return rest.substring(0, end).substringAfter("{")
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun mainSources(): List<File> = sourceRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun sourceRoot(): File = firstExisting(
        File("src/main/java"),
        File("app/src/main/java"),
    )

    private fun manifestFile(): File = firstExisting(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    )

    /** The suite runs from either the module or the repository root, depending on the invocation. */
    private fun firstExisting(vararg candidates: File): File =
        candidates.firstOrNull { it.exists() }
            ?: error("none of ${candidates.joinToString { it.path }} exists from ${File(".").absolutePath}")
}
