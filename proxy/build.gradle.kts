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

// Prefer packaging installDist (top-level `proxy/bin/proxy`) over default distTar nesting.
tasks.register<Tar>("proxyDeployTar") {
    group = "distribution"
    description = "Tar of installDist as proxy/ with bin/proxy (Java 17)."
    dependsOn(tasks.named("installDist"))
    compression = Compression.GZIP
    archiveFileName.set("hongguo-playback-proxy.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("install/proxy")) {
        into("proxy")
    }
}

sourceSets {
    main {
        kotlin {
            // Reuse watch PlayerSigner / DeviceRegister / MobilePlayback (JVM-safe).
            srcDir("../app/src/main/java/com/hongguo/watch/upstream")
        }
    }
}
