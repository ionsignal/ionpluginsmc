plugins {
    `java-library`
}

description = "Core debug framework for Ion Signal plugins"

dependencies {
    // Paper API is compileOnly - provided by runtime environment
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
