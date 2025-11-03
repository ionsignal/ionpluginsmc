plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Terra addon for IonNerrus jigsaw integration"

dependencies {
    // Paper API is provided by paperweight-conventions
    // compileOnly(project(":ionnerrus"))
    // Terra API and dependencies (compileOnly - provided by Terra at runtime)
    compileOnly(libs.terra.api)
    compileOnly(libs.terra.manifest.loader)
    // Tectonic (compileOnly - provided by Terra)
    compileOnly(libs.tectonic.common)
    compileOnly(libs.tectonic.yaml)
    // BlueNBT - we shadow and relocate this
    implementation(libs.bluenbt)
}

tasks {
    // The regular jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("dev-mojmap")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    // Shadow task shades BlueNBT into the dev JAR
    shadowJar {
        archiveClassifier.set("dev-mojmap-all")
        
        // Relocate BlueNBT to a private package within our addon
        relocate("de.bluecolored.bluenbt", "com.ionsignal.minecraft.ionnerrus.terra.lib.bluenbt")
        
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
    }
    
    // ReobfJar takes the shadowed JAR and remaps it to Spigot mappings and
    // ensures shadowJar completes before reobfJar starts
    reobfJar {
        inputJar.set(shadowJar.flatMap { it.archiveFile })
    }

    // Make assemble depend on reobfJar (the final artifact)
    assemble {
        dependsOn(reobfJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("terra.addon.yml") {
            expand(props)
        }
    }
}