import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.time.Instant

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.3.2"
}

group = "com.specops"

version = "1.3.1"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.2")
    implementation("io.swagger.parser.v3:swagger-parser:2.1.39")
    implementation("io.swagger.parser.v3:swagger-parser-v2-converter:2.1.39")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("SpecOps")
    archiveVersion.set("v${project.version}")
    archiveClassifier.set("")
    from("LICENSE")
    from("NOTICE")

    dependencies {
        exclude(dependency("net.portswigger.burp.extensions:montoya-api:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    manifest {
        attributes(
            "Burp-Extension-Name" to "SpecOps",
            "Implementation-Title" to "SpecOps",
            "Implementation-Version" to project.version.toString(),
            "Built-By" to "Prince Rawat",
            "Build-Timestamp" to Instant.now().toString()
        )
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}