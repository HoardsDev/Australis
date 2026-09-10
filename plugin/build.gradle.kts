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

    // Tests (the protection-logic classes are pure JDK and fully unit-tested).
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
