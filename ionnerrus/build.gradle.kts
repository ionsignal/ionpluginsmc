plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "An LLM powered NPC decision engine"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

// Prevents openai-java's and other implementation dependencies' transitive Jackson and Kotlin
// from entering the shadow JAR.
//   - Jackson: Paper's URLClassLoader owns core Jackson at runtime (2.13.4-2); a bundled copy
//     sits behind Paper's entries in first-found ordering and is never reached.
//   - Kotlin: provided by IonCoreLoader's MavenLibraryResolver and inherited via join-classpath;
//     bundling a second copy would create a duplicate classloader split.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.datatype")
    exclude(group = "com.fasterxml.jackson.module")
}

dependencies {
    // IonCore
    compileOnly(project(":ioncore", configuration = "devJar"))
    // Cloud Command Framework
    implementation(libs.cloud.paper)
    implementation(libs.cloud.annotations)
    // Add your dependencies here
    // LLM and HTTP dependencies (will be shaded)
    implementation(libs.openai.java)
    implementation(libs.okhttp)
    implementation(libs.classgraph)
    // JSON Schema Generation
    implementation(libs.jsonschema.generator)
    implementation(libs.jsonschema.module.jackson)
    // Compile-time Jackson references only. Effective runtime version is Paper's bundled 2.13.4-2,
    // provided via Paper's shared URLClassLoader. The catalog version is pinned to 2.13.4 to match.
    // openai-java's transitive Jackson is excluded above and does not enter the shadow JAR.
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
        relocate("com.openai", "com.ionsignal.minecraft.ionnerrus.lib.openai_java")
        relocate("com.github.victools", "com.ionsignal.minecraft.ionnerrus.lib.victools")
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
        exclude("META-INF/versions/**")
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
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(props)
        }
    }
}
