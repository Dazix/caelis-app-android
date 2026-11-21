import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// The Geospatial API key. It comes from the machine (local.properties, gitignored, or the
// CAELIS_GEOSPATIAL_API_KEY environment variable of a CI job) and is never in the repository. A build
// without it is a complete, working build: the Geospatial switch is simply not offered
// (BuildConfig.HAS_GEOSPATIAL_API_KEY is false) and ArCoreSessionController never requests Geospatial.
//
// A key in a manifest is readable by anyone who unpacks the APK, so restrict it in the Google Cloud
// console to this package name and the signing certificate and to the ARCore API alone.
val geospatialApiKey: String = run {
    val localProperties = Properties()
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { localProperties.load(it) }
    (localProperties.getProperty("geospatial.api.key") ?: System.getenv("CAELIS_GEOSPATIAL_API_KEY") ?: "").trim()
}

android {
    namespace = "com.dazix.caelis"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.dazix.caelis"
        minSdk = 30
        targetSdk = 37
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["geospatialApiKey"] = geospatialApiKey
        buildConfigField("boolean", "HAS_GEOSPATIAL_API_KEY", geospatialApiKey.isNotEmpty().toString())
    }

    signingConfigs {
        val keystorePath = System.getenv("SIGNING_KEY_STORE_PATH")
        val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")

        if (!keystorePath.isNullOrBlank() &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank() &&
            file(keystorePath).exists()
        ) {
            create("release") {
                this.storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The build time is appended to the version name below (androidComponents), not here:
            // a value computed in this block is frozen by the configuration cache.
        }
        release {
            // R8: unused code removed and the rest renamed, so the shipped APK no longer reads like the
            // source. proguard-rules.pro says why it needs no keeps; res/raw/keep.xml keeps the
            // artwork that is looked up by name. The mapping file goes to Play with each upload.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never the debug key as a fallback: checkReleaseSigning below fails the packaging instead.
            signingConfig = signingConfigs.findByName("release")
        }    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Writes the locale config from the values-<language> directories, so Android 13+ offers the
        // app's own language picker in its settings (Settings > Apps > Caelis > Language). Without it the
        // picker is not shown at all. res/resources.properties says what values/ is: English.
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // NewApi is an error already. InlinedApi is the same mistake with a constant: a permission or
        // flag from a newer Android compiles into the APK and silently answers "denied" or "no" on
        // Android 11 and 12, which no emulator run of a newer image would ever show.
        error += "InlinedApi"
    }
}

// Filament materials: app/src/main/materials/*.mat -> generated assets/*.filamat, compiled by the
// matc of exactly the Filament version the app links against (see CompileFilamentMaterialsTask).
val compileFilamentMaterials = tasks.register<CompileFilamentMaterialsTask>("compileFilamentMaterials") {
    filamentVersion.set(libs.versions.filament)
    checksumFile.set(rootProject.layout.projectDirectory.file("gradle/filament-tools.sha256"))
    materialSources.from(layout.projectDirectory.dir("src/main/materials").asFileTree.matching { include("*.mat") })
    backends.set("opengl")
    toolsCacheDir.set(File(gradle.gradleUserHomeDir, "caches/caelis-filament-tools"))
}

val checkReleaseSigning = tasks.register<CheckReleaseSigningTask>("checkReleaseSigning") {
    configured.set(android.signingConfigs.findByName("release") != null)
}
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    dependsOn(checkReleaseSigning)
}

// Build time in the debug version name, so a phone shows which APK is actually installed. Debug only:
// a release's version comes from CI and must stay reproducible. See BuildTimeValueSource for why this
// is a value source and not a string.
val debugVersionName = providers.of(BuildTimeValueSource::class.java) {
    parameters.versionName.set(android.defaultConfig.versionName.orEmpty())
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileFilamentMaterials,
            CompileFilamentMaterialsTask::outputDir,
        )
        if (variant.buildType == "debug") {
            variant.outputs.forEach { output ->
                output.versionName.set(debugVersionName)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.exifinterface)
    implementation(libs.flatbuffers.java)
    implementation(libs.filament.android)
    // Consent-gated at runtime (ArCoreSessionController); `optional` in the manifest.
    implementation(libs.arcore)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

