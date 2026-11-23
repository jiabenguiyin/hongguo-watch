plugins {
    kotlin("jvm")
    application
}

version = "0.1.0"

java {
    // Emit Java 17 bytecode (compile with JDK 21 on the agent; run with openjdk-17 on server).
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

application {
    applicationName = "proxy"
    mainClass.set("com.hongguo.proxy.ProxyMainKt")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("hongguo-playback-proxy")
    manifest {
        attributes["Main-Class"] = "com.hongguo.proxy.ProxyMainKt"
    }
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveBaseName.set("hongguo-playback-proxy")
    archiveVersion.set("")
    archiveExtension.set("tar.gz")
    // Prefer a top-level `proxy/` directory (matches installDist layout).
    into("proxy")
}

sourceSets {
    main {
        kotlin {
            // Reuse watch PlayerSigner / DeviceRegister / MobilePlayback (JVM-safe).
            srcDir("../app/src/main/java/com/hongguo/watch/upstream")
        }
    }
}
