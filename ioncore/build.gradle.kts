plugins {
    id("paperweight-conventions")
}

description = "Core framework for Ion Signal plugins"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

// Expose the development JAR as a consumable artifact for other subprojects (IDE support).
val devJar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

dependencies {
    // No additional dependencies needed
}

tasks {
    // The standard jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("mojmap")
    }

    // Process plugin.yml with version substitution
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    
    // For MOJANG_PRODUCTION, we don't depend on reobfJar
    // The jar task output is already the final artifact
}

// Link the output of the 'jar' task to our custom 'devJar' configuration.
artifacts {
    add(devJar.name, tasks.jar)
}