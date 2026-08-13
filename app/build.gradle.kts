plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.bossincrypto.velox"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "io.github.bossincrypto.velox"
        minSdk = 31
        targetSdk = 37

        // CI injects these; locally you get a stable dev build.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
    }

    signingConfigs {
        create("release") {
            // Not a secret, committed on purpose. Every release then carries the same
            // signature and installs as an upgrade over the previous one. The CI default
            // is a debug key generated fresh on each runner, which changes every build and
            // makes upgrades fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            // Swap in a private keystore before publishing anywhere that matters.
            storeFile = rootProject.file("keystore/velox-public.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "velox"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Ship only the languages we actually translate.
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    // Strip metadata nobody needs at runtime.
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
}



