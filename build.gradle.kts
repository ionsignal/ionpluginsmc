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
val terraAddon = project(":ionnerrus-terra-addon")

abstract class CopyTerraAddonTask : DefaultTask() {
    @get:InputFile
    abstract val addonJar: RegularFileProperty
    
    @get:OutputDirectory
    abstract val targetDirectory: DirectoryProperty
    
    @TaskAction
    fun copy() {
        val source = addonJar.asFile.get()
        val targetDir = targetDirectory.asFile.get()
        val target = targetDir.resolve(source.name)
        try {
            // Skip if already up-to-date
            if (target.exists() && target.lastModified() >= source.lastModified()) {
                logger.info("Terra addon already up-to-date, skipping copy")
                return
            }
            logger.lifecycle("Copying Terra addon: ${source.name} -> ${targetDir.absolutePath}")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            source.copyTo(target, overwrite = true)
            logger.info("Successfully copied ${source.name}")
        } catch (e: Exception) {
            throw GradleException("Failed to copy Terra addon: ${e.message}", e)
        }
    }
}

val copyTerraAddon = tasks.register<CopyTerraAddonTask>("copyTerraAddon") {
    // For MOJANG_PRODUCTION, use shadowJar output
    addonJar.set(
        terraAddon.tasks.named("shadowJar")
            .flatMap { task ->
                @Suppress("UNCHECKED_CAST")
                (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
            }
    )
    // Output: Terra's addons directory
    targetDirectory.set(layout.projectDirectory.dir("run/plugins/Terra/addons"))
    // Ensure addon is built before copying
    dependsOn(terraAddon.tasks.named("shadowJar"))
}

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

        // For MOJANG_PRODUCTION, use jar outputs (IonCore)
        pluginJars(
            ioncore.tasks.named("shadowJar")
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
        dependsOn(copyTerraAddon)
        dependsOn(ionnerrus.tasks.named("shadowJar"))
    }
}