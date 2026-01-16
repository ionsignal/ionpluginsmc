plugins {
    id("paperweight-conventions")
    alias(libs.plugins.shadow)
}

description = "Standalone Jigsaw Structure Generation Engine"

// Configure paperweight for Mojang production mappings
paperweight.reobfArtifactConfiguration.set(
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
)

dependencies {
    // IonCore (API Access)
    compileOnly(project(":ioncore", configuration = "devJar"))
    
    // Terra (Compile-time only, provided at runtime)
    compileOnly(files("libs/terra-paper-7.0.0-BETA+75dddb2af.jar"))
    compileOnly(libs.terra.manifest.loader)
    // Ensure we have access to the specific Terra implementation for NMS bridging if needed
    
    // Seismic
    compileOnly(libs.seismic)

    // Tectonic (Terra Config)
    compileOnly(libs.tectonic.common)
    compileOnly(libs.tectonic.yaml)
    
    // BlueNBT (Implementation detail, shadowed)
    implementation(libs.bluenbt)
}

tasks {
    jar {
        archiveClassifier.set("mojmap")
    }

    shadowJar {
        archiveClassifier.set("") 

        // Relocate BlueNBT to avoid classpath conflicts
        relocate("de.bluecolored.bluenbt", "com.ionsignal.minecraft.iongenesis.lib.bluenbt")
        
        exclude("META-INF/maven/**")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.SF")
    }
    
    assemble {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}