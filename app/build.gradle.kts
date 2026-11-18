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
        versionCode = 10
        versionName = "0.4.4"
        resourceConfigurations += listOf("zh", "en")
        // 可选诊断上传端点（构建时注入）；空则走公开粘贴服务。切勿把密钥写进仓库。
        val diagUrl = (System.getenv("HG_DIAG_UPLOAD_URL") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "DIAG_UPLOAD_URL", "\"$diagUrl\"")
    }

    signingConfigs {
        // 仓库内固定 debug 证书：GitHub 发布的 debug APK 可互相覆盖安装。
        // 密码为公开的 android/android（仅调试用途，非正式发布密钥）。
        getByName("debug") {
            val debugStore = rootProject.file("signing/debug.keystore")
            if (debugStore.isFile) {
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // 正式签名：仅走环境变量 / 本地 signing/keystore.properties，禁止静默回退 debug，
        // 避免「看似 release、实则 debug 证书」导致日后换钥升级冲突。
        create("release") {
            val propsFile = rootProject.file("signing/keystore.properties")
            val props = mutableMapOf<String, String>()
            if (propsFile.isFile) {
                propsFile.readLines()
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() && !line.startsWith("#") && line.contains("=") }
                    .forEach { line ->
                        val idx = line.indexOf('=')
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        if (k.isNotEmpty()) props[k] = v
                    }
            }
            fun prop(env: String, key: String): String? {
                val fromEnv = System.getenv(env)
                if (!fromEnv.isNullOrBlank()) return fromEnv
                return props[key]?.takeIf { value -> value.isNotBlank() }
            }

            val storePath = prop("WATCH_RELEASE_STORE_FILE", "storeFile")
            val storePass = prop("WATCH_RELEASE_STORE_PASSWORD", "storePassword")
            val alias = prop("WATCH_RELEASE_KEY_ALIAS", "keyAlias")
            val keyPass = prop("WATCH_RELEASE_KEY_PASSWORD", "keyPassword")
            if (!storePath.isNullOrBlank() &&
                !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() &&
                !keyPass.isNullOrBlank()
            ) {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            } else {
                // 未配置正式密钥时不产出「假 release」；assembleRelease 会因缺 signingConfig 失败。
                // 侧载验证请用 assembleDebug。配置步骤见 signing/README.md。
                logger.warn(
                    "hongguo-watch: release signing not configured " +
                        "(WATCH_RELEASE_* or signing/keystore.properties). " +
                        "assembleRelease requires a real keystore — see signing/README.md",
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    testImplementation("junit:junit:4.13.2")
}
