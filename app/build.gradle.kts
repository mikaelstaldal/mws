// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// Unit tests declare no annotation processors, so their kapt tasks would only warn that the
// Room options went unrecognized.
tasks.matching { it.name.startsWith("kapt") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

// Signing key shared by the nu.staldal.* apps. Enabling guarding services by a signature-level permission,
// so the two apps must be signed with the same key for it to be granted. The default
// ~/.android/debug.keystore would satisfy that, but it is a poor trust anchor: world-readable,
// fixed password "android", and shared by every debug APK built on the machine.
//
// Configure it in local.properties (kept out of version control), or through the matching
// environment variables for CI:
//
//     debugKeystore=/path/to/staldal-apps.keystore   DEBUG_KEYSTORE
//     debugKeystorePassword=…                        DEBUG_KEYSTORE_PASSWORD
//     debugKeyAlias=staldal-apps                     DEBUG_KEY_ALIAS
//     debugKeyPassword=…                             DEBUG_KEY_PASSWORD
//
// Absent or incomplete, the build still works but falls back to the default debug key and says so;
// the integration keeps working (both apps fall back alike) — it is the trust boundary that weakens,
// which must not happen quietly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(key: String, env: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStore = signingProperty("releaseKeystore", "RELEASE_KEYSTORE")?.let(::file)
val releaseStorePassword = signingProperty("releaseKeystorePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseAlias = signingProperty("releaseKeyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
val releaseSigningReady = releaseStore?.exists() == true && releaseStorePassword != null &&
    releaseAlias != null && releaseKeyPassword != null

// Local opt-in for testing providers before a matrix entry is qualified.
val debugProviderOverride = (localProperties.getProperty("debugProviderOverride")
    ?: System.getenv("DEBUG_PROVIDER_OVERRIDE")) == "true"

android {
    namespace = "nu.staldal.mws"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Overrides the built-in debug config, which debug and androidTest builds already use.
        getByName("debug") {
            val store = signingProperty("debugKeystore", "DEBUG_KEYSTORE")?.let(::file)
            val storePw = signingProperty("debugKeystorePassword", "DEBUG_KEYSTORE_PASSWORD")
            val alias = signingProperty("debugKeyAlias", "DEBUG_KEY_ALIAS")
            val keyPw = signingProperty("debugKeyPassword", "DEBUG_KEY_PASSWORD")
            if (store?.exists() == true && storePw != null && alias != null && keyPw != null) {
                storeFile = store
                storeType = "PKCS12"
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            } else {
                logger.warn(
                    "mypass: no shared debug signing key configured (see app/build.gradle.kts); " +
                        "falling back to the default debug keystore."
                )
            }
        }
        // Must be the same release identity used by MyPass. Without it, the
        // release artifact is unsigned and cannot call MyPass.
        // local.properties keys mirror the debug names with a "release" prefix;
        // CI may use RELEASE_KEYSTORE, RELEASE_KEYSTORE_PASSWORD,
        // RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD.
        create("sharedRelease") {
            if (releaseSigningReady) {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "nu.staldal.mws"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ANDROIDX_WEBKIT_VERSION", "\"${libs.versions.webkit.get()}\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_PROVIDER_OVERRIDE", debugProviderOverride.toString())
        }
        release {
            buildConfigField("boolean", "DEBUG_PROVIDER_OVERRIDE", "false")
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("sharedRelease")
            } else {
                logger.warn("mypass: release signing key not configured; release artifact will be unsigned.")
            }
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test>().configureEach {
    val roboHome = layout.buildDirectory.dir("robolectric-home").get().asFile
    doFirst { roboHome.mkdirs() }
    systemProperty("user.home", roboHome.absolutePath)
}

dependencies {
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.webkit)
    implementation(libs.androidsvg)
    implementation(libs.whatwg.url)
    implementation(libs.icu4j)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.accessibility)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
