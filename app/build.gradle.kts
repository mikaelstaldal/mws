// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

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

val releaseStore = signingProperty("releaseKeystore", "RELEASE_KEYSTORE")?.let(::file)
val releaseStorePassword = signingProperty("releaseKeystorePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseAlias = signingProperty("releaseKeyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
val releaseSigningReady = releaseStore?.exists() == true && releaseStorePassword != null &&
    releaseAlias != null && releaseKeyPassword != null

// Local opt-in for testing providers before a matrix entry is qualified.
val debugProviderOverride = (localProperties.getProperty("debugProviderOverride")
    ?: System.getenv("DEBUG_PROVIDER_OVERRIDE")) == "true"

val providerTableDir = layout.buildDirectory.dir("generated/source/providerCompatibility")
val generateProviderTable = tasks.register("generateProviderCompatibilityTable") {
    val matrix = rootProject.file("spec/ARCHITECTURE.md")
    inputs.file(matrix)
    inputs.property("webkitVersion", libs.versions.webkit.get())
    outputs.dir(providerTableDir)
    doLast {
        val section = matrix.readText().substringAfter("## 15. Provider compatibility matrix\n")
            .substringBefore("\n## ")
        require(section.contains("| Provider package | Exact provider version |")) {
            "Provider compatibility matrix missing"
        }
        val floors = sortedMapOf<String, List<Int>>()
        section.lineSequence().filter { it.startsWith("| ") }.drop(2).forEach { row ->
            val cells = row.trim().trim('|').split('|').map(String::trim)
            require(cells.size == 9) { "Malformed provider compatibility row: $row" }
            val result = cells[8].removeSurrounding("`")
            require(result in setOf("PASS", "FAIL", "BLOCKED")) {
                "Invalid provider compatibility result: ${cells[8]}"
            }
            if (result != "PASS") return@forEach
            val pkg = cells[0]
            val version = cells[1]
            require(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]*"))) { "Invalid provider package: $pkg" }
            require(cells[4] == libs.versions.webkit.get()) { "Qualified provider uses different WebKit version: $pkg" }
            require(cells.subList(2, 8).all { it.isNotBlank() && it != "—" }) {
                "Qualified provider lacks evidence: $pkg"
            }
            require(version.matches(Regex("[0-9]+(\\.[0-9]+)*"))) { "Invalid provider version: $version" }
            val parts = version.split('.').map(String::toInt)
            val old = floors[pkg]
            if (old == null || compareVersions(parts, old) < 0) floors[pkg] = parts
        }
        val entries = floors.entries.joinToString(",\n") { (pkg, parts) ->
            "        \"$pkg\" to listOf(${parts.joinToString(", ")})"
        }
        val output = File(providerTableDir.get().asFile,
            "nu/staldal/mws/GeneratedProviderFloors.kt")
        output.parentFile.mkdirs()
        output.writeText("""package nu.staldal.mws

/** Generated from the qualifying PASS rows in ARCHITECTURE.md §15. */
internal object GeneratedProviderFloors {
    val values: Map<String, List<Int>> = mapOf(
$entries
    )
}
""")
        File(providerTableDir.get().asFile, "qualified-count.txt").writeText(floors.size.toString())
    }
}

val verifyReleaseProviderCompatibility = tasks.register("verifyReleaseProviderCompatibility") {
    dependsOn(generateProviderTable)
    doLast {
        val count = File(providerTableDir.get().asFile, "qualified-count.txt").readText().toInt()
        require(count > 0) { "Release requires at least one qualifying PASS provider in ARCHITECTURE.md §15" }
    }
}

fun compareVersions(left: List<Int>, right: List<Int>): Int {
    for (i in 0 until maxOf(left.size, right.size)) {
        val comparison = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

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
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    sourceSets.getByName("main").java.srcDir(providerTableDir)

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

tasks.named("preBuild").configure { dependsOn(generateProviderTable) }
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseProviderCompatibility)
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
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.webkit)
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
