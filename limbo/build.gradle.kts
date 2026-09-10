plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "gg.australis"
version = "0.2.0"

// Paper 1.21 requires Java 21.
tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

// Reuse the shared core (LimboCheck) — one source of truth — and the shared
// core unit tests.
sourceSets {
    main { java.srcDir("../common/src/main/java") }
    test { java.srcDir("../plugin/src/test/java/gg/australis/core") }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
