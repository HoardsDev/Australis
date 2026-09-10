plugins {
    java
    // Velocity plugins are plain shaded jars; shadow bundles any runtime deps.
    id("com.gradleup.shadow") version "8.3.5"
}

group = "gg.australis"
version = "0.2.0"

// Target Java 17 bytecode (Velocity's minimum) while compiling with whatever
// JDK 17+ is available. Using `release` avoids needing a specific toolchain JDK.
tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// Shared protection logic (gg.australis.core) lives in ../common and is compiled
// into both the Velocity and Paper plugins — one source of truth, no drift.
sourceSets {
    main {
        java.srcDir("../common/src/main/java")
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Velocity API
}

dependencies {
    // Velocity API — provided at runtime by the proxy, so compileOnly.
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    // Config (SnakeYAML is bundled with Velocity at runtime, so compileOnly for
    // the shaded jar; on the test classpath it must be present so AustralisConfig
    // links (it references Yaml/SafeConstructor/LoaderOptions).
    compileOnly("org.yaml:snakeyaml:2.2")
    testImplementation("org.yaml:snakeyaml:2.2")

    // Tests (the protection-logic classes are pure JDK and fully unit-tested).
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // AustralisConfig has Component-typed getters, so the class needs adventure
    // on the test classpath to link (the config tests never build a Component).
    testImplementation("net.kyori:adventure-api:4.17.0")
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
