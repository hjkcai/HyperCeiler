@file:Suppress("UnstableApiUsage")

data class GprCredentials(val user: String, val key: String)

/**
 * GitHub Packages 的凭据，取不到就返回 null。
 *
 * CI 有 token，走真实的私有 registry。本地或容器构建常常没有凭据，此时不再直接失败：
 * tools/fetch_github_packages.py 会把同一批产物抓进 localrepo/，Gradle 用本地 maven
 * 仓库即可解析。缺凭据又缺 localrepo 时，解析会在下载阶段报出找不到依赖，错误信息
 * 比在这里抛异常更具体。
 */
fun loadGprCredentials(): GprCredentials? {
    // 优先从环境变量读取
    val envUser = System.getenv("GIT_ACTOR")?.takeIf { it.isNotBlank() }
    val envKey = System.getenv("GIT_TOKEN")?.takeIf { it.isNotBlank() }

    if (envUser != null && envKey != null) {
        return GprCredentials(envUser, envKey)
    }

    // 从 signing.properties 读取
    val propsFile = File(rootDir, "signing.properties")
    if (!propsFile.isFile) return null

    val props = java.util.Properties().apply {
        propsFile.inputStream().use { load(it) }
    }

    val user = props.getProperty("gpr.user")?.takeIf { it.isNotBlank() } ?: return null
    val key = props.getProperty("gpr.key")?.takeIf { it.isNotBlank() } ?: return null

    return GprCredentials(user, key)
}

val gprCredentials: GprCredentials? by lazy { loadGprCredentials() }

/** 由 tools/fetch_github_packages.py 生成的离线依赖仓库，存在时优先于任何网络仓库。 */
val localRepoDir = File(rootDir, "localrepo")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // 离线依赖仓库排在最前：tools/fetch_github_packages.py 抓下来的
        // fan.miuix / hyperceiler-expansion 就在这里，命中后不再访问任何网络仓库。
        if (localRepoDir.isDirectory) {
            maven {
                name = "localOffline"
                url = uri(localRepoDir)
                metadataSources {
                    mavenPom()
                    artifact()
                }
            }
        }
        // Mirrors first: several artifacts on google()/mavenCentral() are redirected
        // to github.com release assets, which are unreachable here and stalls the build.
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        mavenLocal()
        // 没有凭据时依然注册这个仓库，但只放行缓存过的私有依赖组，避免空凭据
        // 请求打到其他公开依赖上拖慢解析。localrepo 命中时这一条根本不会被访问。
        maven("https://maven.pkg.github.com/ReChronoRain/HyperCeiler") {
            val gpr = gprCredentials
            if (gpr != null) {
                credentials {
                    username = gpr.user
                    password = gpr.key
                }
            } else {
                content {
                    includeGroup("com.sevtinge.hyperceiler")
                    includeGroup("fan.miuix")
                }
            }
        }
        maven("https://jitpack.io")
        maven("https://api.xposed.info")
    }
}

rootProject.name = "HyperCeiler"

include(
    "app",
    // ":library:hook",
    ":library:libhook",
    // ":library:xposed-api-101",
    ":library:core",
    ":library:provision",
    ":library:common",
    ":library:processor",
    ":library:hidden-api",
)
