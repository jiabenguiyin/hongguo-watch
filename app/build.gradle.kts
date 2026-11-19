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
        // main tip 0.4.30/#37；本 PR：顶底 chrome 满幅 + 更新包彻底清理（T-79/T-80）→ 0.4.31
        versionCode = 37
        versionName = "0.4.31"
        resourceConfigurations += listOf("zh", "en")
        // 可选诊断上传端点（构建时注入）；空则走公开粘贴服务。切勿把密钥写进仓库。
        val diagUrl = (System.getenv("HG_DIAG_UPLOAD_URL") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "DIAG_UPLOAD_URL", "\"$diagUrl\"")
    }

    signingConfigs {
        // 统一侧载证书：仓库内 signing/debug.keystore（密码 android/android）。
        // GitHub Releases 的 assembleDebug、以及未配置正式密钥时的 assembleRelease
        // 都用同一把，避免安装包互相冲突。这不是 production keystore，勿伪造正式钥。
        getByName("debug") {
            val debugStore = rootProject.file("signing/debug.keystore")
            require(debugStore.isFile) {
                "Missing signing/debug.keystore — required for unified published APK signing"
            }
            storeFile = debugStore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 正式签名（可选）：仅环境变量 / 本地 signing/keystore.properties。
        // 未配置时 release buildType 显式复用上方 debug 配置（同一证书），见 signing/README.md。
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
            val sharedDebugSigning = signingConfigs.getByName("debug")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            } else {
                // 无正式密钥时与 assembleDebug / GitHub Releases 共用同一证书，保证可覆盖升级。
                // 明确使用仓库 debug.keystore，不是另造的「假 production」钥。
                signingConfig = sharedDebugSigning
                logger.warn(
                    "hongguo-watch: no WATCH_RELEASE_* / signing/keystore.properties — " +
                        "assembleRelease signs with signing/debug.keystore " +
                        "(same cert as assembleDebug). See signing/README.md",
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // CI / GitHub Releases 发的是 assembleDebug；对 debug 也开 R8+资源收缩，
            // 与 release 同规则，避免侧载包比 release 大一截。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "/META-INF/*.version",
                "/META-INF/NOTICE*",
                "/META-INF/LICENSE*",
                "/META-INF/DEPENDENCIES",
                "kotlin/**",
                "DebugProbesKt.bin",
                "org/bouncycastle/**",
            )
        }
        // 压缩 APK 内 .so（含 IJK/FFmpeg），减小下载体积；安装时仍解压到 lib 目录。
        // 不删除任何 ABI / codec 原生库，保留 CD12 Max 软解路径。
        jniLibs {
            useLegacyPackaging = true
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
    // SM3 已内置 Sm3.kt，不再依赖完整 bcprov（约 8MB jar）

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // 片源为渐进式 MP4（RemoteVideo），不打包 HLS 扩展
    implementation("androidx.media3:media3-ui:1.4.1")

    // IJK + FFmpeg soft decode（手表 / 低配 SoC 兼容路径；Media3 仍为可选内核）
    implementation(project(":ijkplayer-java"))

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.coil-kt:coil:2.6.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
