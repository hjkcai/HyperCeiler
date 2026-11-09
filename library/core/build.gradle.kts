plugins {
    alias(libs.plugins.android.library)
}

android {
    val appModuleId: String = providers.gradleProperty("hyperceilerAppId")
        .getOrElse("com.sevtinge.hyperceiler")

    namespace = "com.sevtinge.hyperceiler.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 35

        buildConfigField("String", "APP_MODULE_ID", "\"$appModuleId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {}
        create("beta") {}
        create("canary") {}
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // api(projects.library.hook)
    api(projects.library.common)
    api(projects.library.provision)
    api(libs.appiconloader)
}
