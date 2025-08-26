import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.time.Instant

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.specops"

version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.8")
    implementation("io.swagger.parser.v3:swagger-parser:2.1.32")
    implementation("io.swagger.parser.v3:swagger-parser-v2-converter:2.1.32")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("SpecOps")
    archiveVersion.set("v${project.version}")
    archiveClassifier.set("") // no "-all" suffix

    dependencies {
        exclude(dependency("net.portswigger.burp.extensions:montoya-api:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    manifest {
        attributes(
            "Burp-Extension-Name" to "SpecOps",
            "Implementation-Title" to "SpecOps",
            "Implementation-Version" to project.version.toString(),
            "Built-By" to System.getProperty("user.name"),
            "Build-Timestamp" to Instant.now().toString()
        )
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}