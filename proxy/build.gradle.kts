plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "21"
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.hongguo.proxy.ProxyMainKt")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.hongguo.proxy.ProxyMainKt"
    }
    // Fat-ish jar not required; use `./gradlew :proxy:run`.
}

sourceSets {
    main {
        kotlin {
            // Reuse watch PlayerSigner / DeviceRegister / MobilePlayback (JVM-safe).
            srcDir("../app/src/main/java/com/hongguo/watch/upstream")
        }
    }
}
