plugins {
    java
    // Velocity plugins are plain shaded jars; shadow bundles any runtime deps.
    id("com.gradleup.shadow") version "8.3.5"
}

group = "gg.australis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Velocity API
}

dependencies {
    // Velocity API — provided at runtime by the proxy, so compileOnly.
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    // Config (SnakeYAML is bundled with Velocity, but declare for clarity/tests).
    compileOnly("org.yaml:snakeyaml:2.2")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
