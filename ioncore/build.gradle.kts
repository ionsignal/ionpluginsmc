plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Core framework for Ion Signal plugins"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

val devJar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

// Expose the development JAR as a consumable artifact for other subprojects (IDE support).
dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
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
        
        // Relocate dependencies to avoid conflicts
        relocate("org.java_websocket", "com.ionsignal.minecraft.ioncore.lib.websocket")
        relocate("okhttp3", "com.ionsignal.minecraft.ioncore.lib.okhttp3")
        relocate("okio", "com.ionsignal.minecraft.ioncore.lib.okio")
        relocate("com.google.gson", "com.ionsignal.minecraft.ioncore.lib.gson")
        
        // Exclude junk files
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
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

artifacts {
    add(devJar.name, tasks.jar)
}