plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Core framework for Ion Signal plugins"

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
}

tasks {
    shadowJar {
        mergeServiceFiles()
        relocate("io.nats", "com.ionsignal.minecraft.ioncore.lib.nats")
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
        exclude("META-INF/versions/**")
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description,
            "api" to providers.gradleProperty("supported_version").get()
        )
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(props)
        }
    }

    assemble {
        dependsOn(shadowJar)
    }
}
