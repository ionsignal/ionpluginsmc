plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "An LLM powered NPC decision engine"

dependencies {
    // Add your dependencies here
    // LLM and HTTP dependencies (will be shaded)
    implementation(libs.simple.openai)
    implementation(libs.okhttp)
    implementation(libs.classgraph)
    // FancyHolograms - local JAR (we'll handle this in Stage 2b)
    compileOnly(files("libs/fancyholograms-3.0.0.jar"))
}

tasks {
    // The regular jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("dev-mojmap")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    // Shadow task shades dependencies into the dev JAR
    shadowJar {
        archiveClassifier.set("dev-mojmap-all") // This becomes the primary artifact
        
        // Relocate dependencies to avoid conflicts
        relocate("okio", "com.ionsignal.minecraft.ionnerrus.lib.okio")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ionnerrus.lib.classgraph")
        relocate("com.squareup.okhttp3", "com.ionsignal.minecraft.ionnerrus.lib.okhttp3")
        relocate("io.github.sashirestela.openai", "com.ionsignal.minecraft.ionnerrus.lib.openai")
        relocate("com.fasterxml.jackson", "com.ionsignal.minecraft.ionnerrus.lib.jackson")
        
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
    }
    
    // ReobfJar takes the shadowed JAR and remaps it to Spigot mappings and
    // ensures shadowJar completes before reobfJar starts
    reobfJar {
        // Input is the shadowJar output
        inputJar.set(shadowJar.flatMap { it.archiveFile })
    }

    // Make assemble depend on reobfJar (the final artifact)
    assemble {
        dependsOn(reobfJar)
    }
    
    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description,
            "api" to "1.21"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}