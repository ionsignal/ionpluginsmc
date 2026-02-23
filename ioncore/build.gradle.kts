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

// Prevents Vert.x's transitive Jackson from entering the shadow JAR.
// Paper/Minecraft URLClassLoader owns core Jackson at runtime (2.13.4-2)
configurations.runtimeClasspath {
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.datatype")
    exclude(group = "com.fasterxml.jackson.module")
}

dependencies {
    implementation(libs.vertx.core)
    implementation(libs.vertx.pg.client)
    // Compile-time Jackson references only.
    // Effective runtime version is Paper/Minecraft bundled 2.13.4-2, provided via Paper/Minecraft shared URLClassLoader.
    // The catalog version is pinned to 2.13.4 to match.
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
        mergeServiceFiles() // Required for Vert.x ServiceLoader
        // Relocations
        relocate("io.vertx", "com.ionsignal.minecraft.ioncore.lib.vertx")
        relocate("io.netty", "com.ionsignal.minecraft.ioncore.lib.netty")
        relocate("com.ongres", "com.ionsignal.minecraft.ioncore.lib.ongres")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ioncore.lib.classgraph")
        // Exclude
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