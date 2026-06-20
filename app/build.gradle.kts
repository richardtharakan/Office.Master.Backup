plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.officemasterbackup"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.officemasterbackup"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}