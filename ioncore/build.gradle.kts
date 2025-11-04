plugins {
    id("paperweight-conventions")
}

description = "Core framework for Ion Signal plugins"

dependencies {
    // No additional dependencies needed
}

tasks {
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