plugins {
  `java-library`
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
  id("xyz.jpenilla.run-paper") version "3.0.0-beta.1" // runServer and runMojangMappedServer tasks for testing
  id("com.gradleup.shadow") version "9.1.0"
  id("com.github.ben-manes.versions") version "0.52.0" 
  id("eclipse") 
}

group = "com.ionsignal.minecraft.ionnerrus"
version = "0.0.7-alpha.1-SNAPSHOT" // Update in plugin.yml as well
description = "An LLM powered NPC decision engine"

java {
  // Configure the java toolchain. This allows gradle to auto-provision JDK 21 on systems that only have JDK 11 installed for example.
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
  compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
  paperweight.paperDevBundle("1.21.7-R0.1-SNAPSHOT")
  // Simple OpenAI API Layer
  implementation("io.github.sashirestela:simple-openai:3.22.1")
  // HTTP Client for future LLM integration
  implementation("com.squareup.okhttp3:okhttp:5.1.0")
  // Reflections library for classpath scanning
  implementation("org.reflections:reflections:0.10.2")
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
    minecraftVersion("1.21.7")
  }

  // shadowJar task to relocate dependencies
  shadowJar {
    // Add relocation rules for dependencies
    relocate("okio", "com.ionsignal.minecraft.ionnerrus.lib.okio")
    relocate("org.reflections", "com.ionsignal.minecraft.ionnerrus.lib.reflections")
    relocate("org.javassist", "com.ionsignal.minecraft.ionnerrus.lib.javassist")
    relocate("com.squareup.okhttp3", "com.ionsignal.minecraft.ionnerrus.lib.okhttp3")
    relocate("io.github.sashirestela.openai", "com.ionsignal.minecraft.ionnerrus.lib.openai")
    relocate("com.fasterxml.jackson", "com.ionsignal.minecraft.ionnerrus.lib.jackson")
    // single, shaded JAR without a classifier
    archiveClassifier.set("")
  }

  build {
    dependsOn(shadowJar)
  }
}
