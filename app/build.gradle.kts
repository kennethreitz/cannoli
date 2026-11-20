import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

fun git(vararg args: String): String = try {
    providers.exec { commandLine(listOf("git") + args) }.standardOutput.asText.get().trim()
} catch (_: Exception) { "" }

val gitCommitHash: String = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }

val gitDirty: Boolean = git("status", "--porcelain", "--untracked-files=no").isNotEmpty()

val buildTimeMillis: Long = System.currentTimeMillis()

val retroArchVersion: String = rootProject.file("retroarch/version.all").let { versionAll ->
    if (!versionAll.isFile) error("$versionAll not found. Run git submodule update --init.")
    versionAll.readLines().firstNotNullOfOrNull { line ->
        Regex("""#define\s+PACKAGE_VERSION\s+"([^"]+)"""").find(line)?.groupValues?.get(1)
    } ?: error("$versionAll no longer declares PACKAGE_VERSION.")
}

abstract class AutoconfigDigestValueSource : ValueSource<String, AutoconfigDigestValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val cfgDir: DirectoryProperty
    }

    override fun obtain(): String {
        val dir = parameters.cfgDir.get().asFile
        val cfgs = dir.listFiles { f: java.io.File -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        require(cfgs.isNotEmpty()) {
            "No bundled autoconfig cfgs found in $dir. Run scripts/fetch-autoconfig.sh first."
        }
        val md = MessageDigest.getInstance("SHA-256")
        for (f in cfgs) {
            md.update(f.name.toByteArray())
            md.update(f.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

val autoconfigDigest: String = providers.of(AutoconfigDigestValueSource::class) {
    parameters.cfgDir.set(layout.projectDirectory.dir("src/main/assets/autoconfig/cannoli"))
}.get()

// Local builds derive their version from `git describe --tags` (e.g. "1.9.1-74-ge5566b53").
// Release CI patches the literal versionName/versionCode lines in defaultConfig; when those
// differ from the "1.0.0"/1 placeholders, the CI values win.
data class GitVersion(val name: String, val code: Int)

fun gitVersion(): GitVersion? = try {
    val process = ProcessBuilder("git", "describe", "--tags", "--dirty", "--match", "v[0-9]*")
        .directory(rootDir).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0 || out.isEmpty()) null else {
        val name = out.removePrefix("v")
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(\d+)-g[0-9a-f]+)?""").find(name)
        if (m == null) null else {
            val (major, minor, patch) = m.destructured
            val ahead = m.groupValues[4].toIntOrNull() ?: 0
            // Same scheme as release.yml: M*1000000 + m*10000 + p*100 + 99 for a release.
            // Commits past the tag push the code above the tagged release, capped below
            // the next patch's release (x.y.(p+1) = ...99) so real releases still win.
            val release = major.toInt() * 1000000 + minor.toInt() * 10000 + patch.toInt() * 100 + 99
            GitVersion(name, if (ahead == 0) release else minOf(release + ahead, release + 99))
        }
    }
} catch (_: Exception) { null }

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "dev.cannoli.scorza"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.cannoli.scorza"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Placeholders above are replaced by release CI; otherwise use git describe.
        // (Written without spaces around '=' so release.yml's sed doesn't rewrite them.)
        if (versionCode == 1 && versionName == "1.0.0") {
            gitVersion()?.let {
                versionName=it.name
                versionCode=it.code
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("long", "BUILD_TIME", "${buildTimeMillis}L")
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("String", "AUTOCONFIG_DIGEST", "\"$autoconfigDigest\"")
        buildConfigField("String", "RETROARCH_VERSION", "\"$retroArchVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Kenneth's fork installs its debug build as dev.cannoli.scorza.secondaryscreen
            // (set in gradle.properties) so it keeps its own library next to official Cannoli.
            applicationIdSuffix = providers.gradleProperty("cannoli.debugAppIdSuffix").getOrElse(".debug")
            buildConfigField("boolean", "DEV_BUILD", "true")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "DEV_BUILD", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("profiling") {
            initWith(getByName("debug"))
            isDebuggable = false
            buildConfigField("boolean", "DEV_BUILD", "true")
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets.getByName("profiling").res.srcDir("src/debug/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.forkEvery = 10
        }
    }
    lint {
        abortOnError = true
        checkDependencies = true
        fatal += "NewApi"
    }
}

dependencies {
    implementation(project(":cannoli-igm"))
    implementation(project(":cannoli-core"))
    implementation(project(":ricotta"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation(libs.nanohttpd)
    implementation(libs.commons.fileupload.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.5.0")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.matching { it.name.startsWith("lintVital") && it.name.endsWith("Profiling") }
    .configureEach { enabled = false }
