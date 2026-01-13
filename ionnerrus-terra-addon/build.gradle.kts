plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Terra addon for IonNerrus"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

dependencies {
    // IonCore 
    compileOnly(project(":ioncore"))
    // Paper API is provided by paperweight-conventions
    // compileOnly(project(":ionnerrus"))
    // Terra API and dependencies (compileOnly - provided by Terra at runtime)
    // compileOnly(libs.terra.api)
    compileOnly(libs.terra.manifest.loader)
    compileOnly(files("libs/terra-paper-7.0.0-BETA+75dddb2af.jar"))
    // Tectonic (compileOnly - provided by Terra)
    compileOnly(libs.tectonic.common)
    compileOnly(libs.tectonic.yaml)
    // BlueNBT - we shadow and relocate this
    implementation(libs.bluenbt)
}

tasks {
    // The regular jar task produces a dev JAR with Mojang mappings
    jar {
        archiveClassifier.set("mojmap")
    }

    // Shadow task shades BlueNBT into the dev JAR
    shadowJar {
        archiveClassifier.set("") // No classifier for the final artifact

        relocate("de.bluecolored.bluenbt", "com.ionsignal.minecraft.ionnerrus.terra.lib.bluenbt")
        
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
    }
    
    // For MOJANG_PRODUCTION, shadowJar is the final artifact
    assemble {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("terra.addon.yml") {
            expand(props)
        }
    }
}