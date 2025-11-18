plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "An LLM powered NPC decision engine"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

dependencies {
    // IonCore 
    compileOnly(project(":ioncore", configuration = "devJar"))
    // Add your dependencies here
    // LLM and HTTP dependencies (will be shaded)
    implementation(libs.simple.openai)
    implementation(libs.okhttp)
    implementation(libs.classgraph)
    // CraftEngine
    compileOnly(files("libs/craft-engine-paper-plugin-0.0.64.jar"))
    // FancyHolograms
    compileOnly(files("libs/fancyholograms-3.0.0.jar"))
}

tasks {
    // The regular jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("mojmap")
    }

    // Shadow task shades dependencies into the JAR
    shadowJar {
        archiveClassifier.set("") // No classifier for the final artifact
        
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
    
    // For MOJANG_PRODUCTION, shadowJar is the final artifact
    assemble {
        dependsOn(shadowJar)
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