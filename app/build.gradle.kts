plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versions are injected by CI from the git tag (v1.2.3 -> 1.2.3 / 10203).
val versionNameEnv = System.getenv("VERSION_NAME") ?: "0.0.0-dev"
val versionCodeEnv = System.getenv("VERSION_CODE")?.toInt() ?: 1

android {
    namespace = "com.nico7an.terminal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nico7an.terminal"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeEnv
        versionName = versionNameEnv
        buildConfigField("String", "UPDATE_REPO", "\"Nico7an/Terminal\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"${System.getenv("UPDATE_TOKEN") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("SIGNING_STORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.hierynomus:sshj:0.41.1")
}
