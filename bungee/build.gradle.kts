plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "gg.australis"
version = "0.2.0"

// BungeeCord/Waterfall 1.21 runs on Java 17+. Compile for 17 so the jar loads on
// the widest range of proxies (the toolchain itself may be a newer JDK).
tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// Same shared protection logic as the Velocity/Paper plugins (one source of
// truth), plus the shared core unit tests so parity is proven here too.
sourceSets {
    main { java.srcDir("../common/src/main/java") }
    test { java.srcDir("../plugin/src/test/java/gg/australis/core") }
}

repositories {
    mavenCentral()
    // BungeeCord snapshots moved off oss.sonatype.org to the Central snapshot repo.
    maven("https://central.sonatype.com/repository/maven-snapshots")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    // Mojang-hosted brigadier, pulled in transitively by bungeecord-protocol.
    maven("https://libraries.minecraft.net")
}

dependencies {
    // The requested 1.21-R0.1-SNAPSHOT coordinate has been removed from the
    // snapshot repos; 26.1-R0.1-SNAPSHOT is the current BungeeCord API (still
    // Minecraft 1.21.x, same event/API surface we use).
    compileOnly("net.md-5:bungeecord-api:26.1-R0.1-SNAPSHOT")
    // SnakeYAML is bundled by BungeeCord at runtime (transitive via
    // bungeecord-config); declare it compileOnly so we never ship a second copy.
    compileOnly("org.yaml:snakeyaml:2.2")

    // The shared EdgeClient logs through SLF4J, but Bungee only exposes a
    // java.util.logging.Logger. We bundle slf4j-api and bridge it to JUL with a
    // tiny adapter (JulSlf4jLogger). Relocated so it can never clash with any
    // SLF4J a proxy or another plugin puts on the classpath.
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("org.slf4j", "gg.australis.bungee.libs.slf4j")
    }
    build {
        dependsOn(shadowJar)
    }
}
