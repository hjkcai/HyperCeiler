// file:noinspection DependencyNotationArgument
import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.gradle.tasks.PackageAndroidArtifact
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
}

val apkId = "HyperCeiler"
// 运行期身份，默认值与官方发布一致。改 gradle.properties 里的 hyperceilerAppId
// 即可换包名；Provider authority 与自定义权限名都从它派生，不会互相脱节。
val appModuleId: String = providers.gradleProperty("hyperceilerAppId")
    .getOrElse("com.sevtinge.hyperceiler")
val gitHash: String by lazy { runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown" }
val gitHashLong: String by lazy { runGitCommand("rev-parse", "HEAD") ?: "unknown" }
val gitCommitCount: Int by lazy { runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0 }
val gitBranch: String by lazy {
    val url = runGitCommand("remote", "get-url", "origin") ?: "unknown"
    val branch = runGitCommand("branch", "--show-current") ?: "unknown"
    """github\.com[:/](.+?)(\.git)?$""".toRegex().find(url)?.groupValues?.get(1).orEmpty() + "/" + branch
}
val gitVersionCode: Int by lazy { 5 + gitCommitCount }

fun runGitCommand(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        }
}.getOrNull()

fun loadPropertiesFromFile(fileName: String): Properties? =
    rootProject.file(fileName).takeIf { it.exists() }?.let { file ->
        Properties().apply { load(file.inputStream()) }
    }

android {
    namespace = "com.sevtinge.hyperceiler"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = appModuleId
        minSdk = 35
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = "2.10.166"

        // manifest 里的 authority 与自定义权限名全部从这里取，避免换包名时漏改一处
        // 就撞上 INSTALL_FAILED_CONFLICTING_PROVIDER。
        manifestPlaceholders += mapOf(
            "appModuleId" to appModuleId,
            "sharedPrefsAuthority" to "${appModuleId}.provider.sharedprefs",
            "settingsAuthority" to "${appModuleId}.provider.settings",
            "crashPermission" to "${appModuleId}.permission.APP_CRASH",
            "readSettingsPermission" to "${appModuleId}.permission.READ_SETTINGS",
            "writeSettingsPermission" to "${appModuleId}.permission.WRITE_SETTINGS"
        )

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())

        val buildConfigData = mapOf(
            "BUILD_TIME" to buildTime,
            "BUILD_OS_NAME" to System.getProperty("os.name"),
            "BUILD_USER_NAME" to System.getProperty("user.name"),
            "BUILD_JAVA_VERSION" to System.getProperty("java.version"),
            "GIT_BRANCH" to gitBranch
        )

        for ((key, value) in buildConfigData) {
            buildConfigField("String", key, "\"$value\"")
        }

        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
            }
        }
    }

    // 没有 x86_64 模拟能力的 aarch64 主机无法运行 NDK，因为官方 NDK 只发布 linux-x86_64 工具链。
    // 打开 prebuiltNative 后跳过 CMake，改用 app/src/prebuiltNative/arm64-v8a 下的 .so。
    // 只有在本机改过 app/src/main/cpp 时才必须关掉它。
    val usePrebuiltNative = providers.gradleProperty("prebuiltNative").map(String::toBoolean).getOrElse(false)

    if (!usePrebuiltNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    sourceSets {
        getByName("main") {
            if (usePrebuiltNative) {
                jniLibs.srcDir("src/prebuiltNative")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x36")
    }

    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
            excludes += listOf("**")
        }
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            useLegacyPackaging = true
        }
    }

    val properties: Properties? = loadPropertiesFromFile("signing.properties")
    fun getString(propertyName: String, environmentName: String, prompt: String): String =
        properties?.getProperty(propertyName)
            ?: System.getenv(environmentName)
            ?: System.console()?.readLine("\n$prompt: ").orEmpty()

    val buildTimeSuffix: String by lazy {
        SimpleDateFormat("MMddHHmm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
    }
    val dateSuffix: String by lazy {
        DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
    }

    signingConfigs {
        create("hasProperties") {
            if (properties != null) {
                storeFile = file(getString("storeFile", "STORE_FILE", "Store file"))
                storePassword = getString("storePassword", "STORE_PASSWORD", "Store password")
                keyAlias = getString("keyAlias", "KEY_ALIAS", "Key alias")
                keyPassword = getString("keyPassword", "KEY_PASSWORD", "Key password")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        val configSigning: ApplicationBuildType.() -> Unit = {
            val signingConfigName = if (properties != null) "hasProperties" else "debug"
            signingConfig = signingConfigs.findByName(signingConfigName)
        }

        val applyBase: ApplicationBuildType.() -> Unit = {
            optimization.enable = true
            buildConfigField("String", "GIT_CODE", "\"$gitVersionCode\"")
        }

        release {
            applyBase()
            configSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
            versionNameSuffix = "-$dateSuffix"
        }

        create("beta") {
            applyBase()
            configSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            versionNameSuffix = "-$dateSuffix"
        }

        create("canary") {
            applyBase()
            configSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            versionNameSuffix = "-${gitHash}-r${gitVersionCode}"
        }

        debug {
            isMinifyEnabled = false
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            buildConfigField("String", "GIT_CODE", "\"$gitVersionCode\"")
            versionNameSuffix = "-${buildTimeSuffix}-r${gitVersionCode}"
            if (properties != null) {
                signingConfig = signingConfigs.findByName("hasProperties")
            }
        }
    }

}

afterEvaluate {
    base {
        val buildTypeName = gradle.startParameter.taskNames
            .firstOrNull { it.contains("assemble", ignoreCase = true) }
            ?.substringAfterLast(":")
            ?.replace("assemble", "", ignoreCase = true)
            ?.lowercase() ?: "debug"
        val suffix = android.buildTypes.findByName(buildTypeName)?.versionNameSuffix ?: ""
        archivesName.set("$apkId-${android.defaultConfig.versionName}$suffix")
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin.jvmToolchain(25)

dependencies {
    implementation(libs.viewpager2)
    implementation(libs.expansion)
    implementation(projects.library.core)
    implementation(projects.library.common)

    api (libs.room.runtime)
    // FTS 支持
    api (libs.room.ktx)
    annotationProcessor (libs.room.compiler)
}

/**
 * Host-side native tests for the NativeHookRuntime (app/src/main/cpp/nativehook/)
 * and the desktop Dart hook's host-testable layers.
 *
 * These run on the build machine, not on Android: they need no device and no
 * Android SDK. On Linux they also build the ARM64 assembly harness test; on
 * macOS that single test is skipped by the script (eventfd / GNU as).
 *
 * Usage: ./gradlew :app:hostNativeTests
 * Optional: ./gradlew :app:hostNativeTests -PlibappSo=/path/to/libapp.so
 */
tasks.register<Exec>("hostNativeTests") {
    group = "verification"
    description = "Compiles and runs the host native tests (no device required)"
    workingDir = rootProject.projectDir
    val script = rootProject.file("tests/run_host_native_tests.sh")
    commandLine(buildList {
        add("bash")
        add(script.absolutePath)
        (project.findProperty("libappSo") as String?)?.let { add(it) }
    })
}
