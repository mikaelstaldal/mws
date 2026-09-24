// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    }

    defaultConfig {
        applicationId = "nu.staldal.mws"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        lintConfig = file("lint.xml")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.webkit)

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
