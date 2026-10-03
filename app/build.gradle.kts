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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"Nico7an/Terminal\"")
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
        debug {
            // Installable next to the real app.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                System.getenv("SIGNING_STORE_FILE") != null -> signingConfigs.getByName("release")
                // Only for the CI smoke test, never published.
                System.getenv("ALLOW_DEBUG_SIGNING") == "1" -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Local pseudo-terminals for the built-in Linux environment.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // proot is shipped as libproot.so: it must exist as a real (executable) file in nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
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
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    // ADB client (pairing + TLS) for the local "this device" terminal. Its BouncyCastle is the same as ours.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") { exclude(group = "org.bouncycastle") }
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
