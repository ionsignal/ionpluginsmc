plugins {
    `java-base`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.github.ben-manes.versions") version "0.53.0"
}

allprojects {
    group = "com.ionsignal.minecraft"
    version = property("version").toString()
    
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") // PaperMC
        maven("https://repo.codemc.io/repository/maven-public/") // Terra
        maven("https://maven.solo-studios.ca/releases") // Tectonic
        maven("https://repo.bluecolored.de/releases") // BlueNBT
    }
}

val minecraftVersion: Provider<String> = providers.gradleProperty("minecraft_version")
val projectVersion: Provider<String> = provider { version.toString() }
val ioncore = project(":ioncore")
val ionnerrus = project(":ionnerrus")
val iongenesis = project(":iongenesis")

tasks {    
    runServer {
        // Use the default toolchain or specify directly
        javaLauncher.set(
            project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }
        )

        // Enable debugger with suspend (port 5005)
        jvmArgs(
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
            // "-XX:+AllowEnhancedClassRedefinition",
            "-Xmx4G", "-Xms2G", "-XX:+UseG1GC",
            "-Dlog4j.configurationFile=${project.projectDir}/log4j2-debug.xml"
        )

        minecraftVersion(minecraftVersion.get())
        runDirectory.set(layout.projectDirectory.dir("run"))

        // IonCore
        pluginJars(
            ioncore.tasks.named("shadowJar")
                .flatMap { task ->
                    @Suppress("UNCHECKED_CAST")
                    (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
                }
        )

        // IonGenesis
        pluginJars(
            iongenesis.tasks.named("shadowJar")
                .flatMap { task ->
                    @Suppress("UNCHECKED_CAST")
                    (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
                }
        )
        
        // For MOJANG_PRODUCTION, use shadowJar output (IonNerrus)
        pluginJars(
            ionnerrus.tasks.named("shadowJar")
                .flatMap { task ->
                    @Suppress("UNCHECKED_CAST")
                    (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
                }
        )

        // Ensure Terra addon is copied before server starts
        dependsOn(ioncore.tasks.named("shadowJar"))
        dependsOn(iongenesis.tasks.named("shadowJar"))
        dependsOn(ionnerrus.tasks.named("shadowJar"))
    }
}