plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Core framework for Ion Signal plugins"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

// Configuration to expose the Mojang-mapped JAR to other subprojects (like ionnerrus)
// This prevents "NoSuchMethodError" during development by avoiding the reobfuscated jar.
val devJar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, Usage.JAVA_API))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class, LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class, Bundling.EXTERNAL))
    }
}

// Paper/Minecraft URLClassLoader owns core Jackson at runtime (2.13.4-2)
configurations.runtimeClasspath {
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.datatype")
    exclude(group = "com.fasterxml.jackson.module")
}

dependencies {
    // NATS
    implementation(libs.nats.client)
    // JSON
    compileOnly(libs.jackson.databind)
    compileOnly(libs.jackson.datatype.jdk8)
    compileOnly(libs.jackson.datatype.jsr310)
    compileOnly(libs.jackson.module.parameter.names)
    // Reflection
    implementation(libs.classgraph)
    // Text
    compileOnly(libs.adventure.api)
}

tasks {
    jar {
        archiveClassifier.set("mojmap")
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("io.nats", "com.ionsignal.minecraft.ioncore.lib.nats")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ioncore.lib.classgraph")
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
        exclude("META-INF/versions/**")
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(props)
        }
    }

    assemble {
        dependsOn(shadowJar)
    }
}

artifacts {
    add(devJar.name, tasks.jar)
}