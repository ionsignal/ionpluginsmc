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
    // Core Dependencies (Shadowed)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.gson)
    
    // Optional: Service Discovery (Shadowed)
    implementation(libs.classgraph)

    // API Dependencies (Exposed)
    compileOnly(libs.adventure.api)
}

tasks {
    // The standard jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("mojmap")
    }

    // Configure Shadow Jar
    shadowJar {
        // Use empty classifier for the final server-ready jar
        archiveClassifier.set("") 
        mergeServiceFiles()
        
        // Relocate dependencies to avoid conflicts
        // These match the libraries defined in libs.versions.toml
        relocate("com.zaxxer.hikari", "com.ionsignal.minecraft.ioncore.lib.hikari")
        relocate("org.postgresql", "com.ionsignal.minecraft.ioncore.lib.postgresql")
        relocate("okio", "com.ionsignal.minecraft.ioncore.lib.okio")
        relocate("com.google.gson", "com.ionsignal.minecraft.ioncore.lib.gson")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ioncore.lib.classgraph")
        
        // Exclude junk files
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
    
    // Ensure shadowJar runs when building
    assemble {
        dependsOn(shadowJar)
    }
}

// Expose the Mojang-mapped JAR as the primary artifact for the 'devJar' configuration
artifacts {
    add(devJar.name, tasks.jar)
}