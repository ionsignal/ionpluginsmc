plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "An LLM powered NPC decision engine"

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.datatype")
    exclude(group = "com.fasterxml.jackson.module")
}

dependencies {
    // IonCore
    implementation(project(":ioncore")) {
        isTransitive = false
    }
    // LLM and HTTP dependencies (will be shaded)
    implementation(libs.openai.java)
    implementation(libs.okhttp)
    // Reflection
    implementation(libs.classgraph)
    // JSON Schema Generation
    implementation(libs.jsonschema.generator)
    implementation(libs.jsonschema.module.jackson)
    // Compile-time Jackson references only
    compileOnly(libs.jackson.databind)
    compileOnly(libs.jackson.datatype.jdk8)
    compileOnly(libs.jackson.datatype.jsr310)
    compileOnly(libs.jackson.module.parameter.names)
    // CraftEngine
    compileOnly(files("libs/craft-engine-paper-plugin-0.0.64.jar"))
    // FancyHolograms
    compileOnly(files("libs/fancyholograms-3.0.0.jar"))
}

tasks {
    // Disable the thin jar
    jar {
        enabled = false
    }
    shadowJar {
        // Drop the "-all" classifier
        archiveClassifier.set("")
        // Exclude ioncore from the shadow jar
        dependencies {
            exclude(project(":ioncore"))
        }
        // Relocate dependencies to avoid conflicts
        relocate("okio", "com.ionsignal.minecraft.ionnerrus.lib.okio")
        relocate("io.github.classgraph", "com.ionsignal.minecraft.ionnerrus.lib.classgraph")
        relocate("com.squareup.okhttp3", "com.ionsignal.minecraft.ionnerrus.lib.okhttp3")
        relocate("com.openai", "com.ionsignal.minecraft.ionnerrus.lib.openai_java")
        relocate("com.github.victools", "com.ionsignal.minecraft.ionnerrus.lib.victools")
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
        exclude("META-INF/versions/**")
    }

    assemble {
        dependsOn(shadowJar)
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
}
