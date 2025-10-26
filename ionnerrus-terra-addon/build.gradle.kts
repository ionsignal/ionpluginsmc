plugins {
  `java-library`
  id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
  id("com.gradleup.shadow") version "9.2.2"
}

group = "com.ionsignal.minecraft.ionnerrus"
version = "0.0.9-alpha.1-SNAPSHOT"
description = "Terra addon for IonNerrus jigsaw integration"

java {
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
  mavenCentral()
  maven {
    name = "papermc"
    url = uri("https://repo.papermc.io/repository/maven-public/")
  }
  maven {
    name = "terra"
    url = uri("https://repo.codemc.io/repository/maven-public/")
  }
  maven {
    name = "tectonic"
    url = uri("https://maven.solo-studios.ca/releases")
  }
  maven {
    name = "bluenbt"
    url = uri("https://repo.bluecolored.de/releases")
  }
}

dependencies {
  // Paper API
  compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
  paperweight.paperDevBundle("1.21.8-R0.1-SNAPSHOT")
  // Terra API and dependencies
  compileOnly("com.dfsek.terra:api:6.6.6-BETA+fb5e597a1")
  compileOnly("com.dfsek.terra:manifest-addon-loader:1.0.0-BETA+fb5e597a1")
  // Tectonic
  compileOnly("com.dfsek.tectonic:common:4.3.0")
  compileOnly("com.dfsek.tectonic:yaml:4.3.0")
  // BlueNBT
  implementation("de.bluecolored:bluenbt:3.0.3")
}

tasks {
  compileJava {
    options.release = 21
  }
  shadowJar {
    archiveClassifier.set("")
    // Relocate BlueNBT to a private package within our addon
    relocate("de.bluecolored.bluenbt", "com.ionsignal.minecraft.ionnerrus.terra.lib.bluenbt")
  }
  build {
    dependsOn(shadowJar)
  }
}
