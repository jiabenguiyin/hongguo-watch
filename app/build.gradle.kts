plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hongguo.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hongguo.watch"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        // MVP 侧载：默认用 debug 密钥签名；正式分发请换成自有 keystore。
        getByName("debug")
        create("release") {
            val releaseStore = System.getenv("WATCH_RELEASE_STORE_FILE")
            if (!releaseStore.isNullOrBlank()) {
                storeFile = file(releaseStore)
                storePassword = System.getenv("WATCH_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("WATCH_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("WATCH_RELEASE_KEY_PASSWORD")
            } else {
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias = signingConfigs.getByName("debug").keyAlias
                keyPassword = signingConfigs.getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // SM3 for App-path Signer（对照 hongguoTV Bytes.kt）
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.coil-kt:coil:2.6.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
