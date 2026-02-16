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

dependencies {
    implementation(libs.vertx.core)
    implementation(libs.vertx.pg.client)

    // Jackson (JSON-Only Architecture)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jdk8)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.parameter.names)

    implementation(libs.classgraph)

    compileOnly(libs.adventure.api)
}

tasks {
    jar {
        archiveClassifier.set("mojmap")
    }

    shadowJar {
        archiveClassifier.set("")

        mergeServiceFiles() // Required for Vert.x ServiceLoader

        // Relocations for Vert.x 5 / Netty Hell Mitigation
        relocate("io.vertx", "com.ionsignal.minecraft.ioncore.lib.vertx")
        relocate("io.netty", "com.ionsignal.minecraft.ioncore.lib.netty")
        relocate("com.fasterxml.jackson", "com.ionsignal.minecraft.ioncore.lib.jackson")
        relocate("com.ongres", "com.ionsignal.minecraft.ioncore.lib.ongres")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ioncore.lib.classgraph")

        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
        exclude("META-INF/versions/**")
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
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