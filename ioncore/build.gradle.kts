plugins {
    id("paperweight-conventions")
}

description = "Core framework for Ion Signal plugins"

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
        archiveClassifier.set("dev-mojmap")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    // Process plugin.yml with version substitution
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // Ensure reobfJar task runs for plugin JAR
    assemble {
        dependsOn(reobfJar)
    }
}

// Link the output of the 'jar' task to our custom 'devJar' configuration.
artifacts {
    add(devJar.name, tasks.jar)
}