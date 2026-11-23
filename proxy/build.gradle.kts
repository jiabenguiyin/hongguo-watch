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

    // MetaSec / unidbg (runtime jars ~35 MiB). Native .so stays private on the host.
    implementation("com.github.zhkl0228:unidbg-android:0.9.8")
    implementation("com.github.zhkl0228:unidbg-api:0.9.8")
    implementation("com.github.zhkl0228:unidbg-unicorn2:0.9.8")
    implementation("com.github.zhkl0228:unidbg-dynarmic:0.9.8")
    implementation("com.github.zhkl0228:unicorn:1.0.14")
    implementation("com.github.zhkl0228:keystone:0.9.7")
    implementation("net.java.dev.jna:jna:5.10.0")
    implementation("org.scijava:native-lib-loader:2.3.5")
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("com.alibaba:fastjson:1.2.83")
    implementation("commons-codec:commons-codec:1.15")
    implementation("org.apache.commons:commons-collections4:4.4")
    implementation("commons-io:commons-io:2.11.0")
    implementation("commons-logging:commons-logging:1.2")

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
    from("scripts") {
        into("proxy/scripts")
    }
}

sourceSets {
    main {
        kotlin {
            // Reuse watch Medusa V5 / DeviceRegister / MobilePlayback (JVM-safe).
            srcDir("../app/src/main/java/com/hongguo/watch/upstream")
        }
    }
}
