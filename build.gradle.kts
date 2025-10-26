plugins {
  `java-library`
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
  id("xyz.jpenilla.run-paper") version "3.0.2"
  id("com.gradleup.shadow") version "9.2.2"
  id("com.github.ben-manes.versions") version "0.53.0" 
  id("eclipse") 
}

group = "com.ionsignal.minecraft.ionnerrus"
version = "0.0.9-alpha.1-SNAPSHOT"
description = "An LLM powered NPC decision engine"

java {
  // Configure the java toolchain. This allows gradle to auto-provision JDK 21 on systems that only have JDK 11 installed for example.
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
  compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
  paperweight.paperDevBundle("1.21.8-R0.1-SNAPSHOT")
  // FancyHolograms v2
  compileOnly(files("libs/fancyholograms-3.0.0.jar"))
  // Simple OpenAI API Layer
  implementation("io.github.sashirestela:simple-openai:3.22.1")
  // HTTP Client for future LLM integration
  implementation("com.squareup.okhttp3:okhttp:5.1.0")
  implementation("io.github.classgraph:classgraph:4.8.184")
}

repositories {
  mavenCentral()
  maven {
    name = "alessiodp"
    url = uri("https://repo.alessiodp.com/releases/")
  }
  maven {
    name = "papermc"
    url = uri("https://repo.papermc.io/repository/maven-public/")
  }
}

tasks {
  compileJava {
    // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
    // See https://openjdk.java.net/jeps/247 for more information.
    options.release = 21
  }
  javadoc {
    options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
  }
  named("eclipse") {
    dependsOn(named("cleanEclipse"))
  }
  runServer {
    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs(
      "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
      "-XX:+AllowEnhancedClassRedefinition"
    )
    minecraftVersion("1.21.8")
    // Explicit dependency on the addon's build task
    dependsOn(":ionnerrus-terra-addon:build")
    // Copy the Terra addon JAR to the addons directory before starting
    doFirst {
      val addonProject = project(":ionnerrus-terra-addon")
      val addonJar = addonProject.tasks.named("shadowJar").get().outputs.files.singleFile
      val terraAddonsDir = file("run/plugins/Terra/addons")
      terraAddonsDir.mkdirs()
      copy {
        from(addonJar)
        into(terraAddonsDir)
      }
      logger.lifecycle("Copied Terra addon to: ${terraAddonsDir}/${addonJar.name}")
    }
  }

  shadowJar {
    // Add relocation rules for dependencies
    relocate("okio", "com.ionsignal.minecraft.ionnerrus.lib.okio")
    relocate("io.github.classgraph", "com.ionsignal.minecraft.ionnerrus.lib.classgraph")
    relocate("com.squareup.okhttp3", "com.ionsignal.minecraft.ionnerrus.lib.okhttp3")
    relocate("io.github.sashirestela.openai", "com.ionsignal.minecraft.ionnerrus.lib.openai")
    relocate("com.fasterxml.jackson", "com.ionsignal.minecraft.ionnerrus.lib.jackson")
    // single, shaded JAR without a classifier
    archiveClassifier.set("")
  }

  build {
    dependsOn(shadowJar)
    // Build the addon before building the main plugin
    dependsOn(":ionnerrus-terra-addon:build")
  }
}