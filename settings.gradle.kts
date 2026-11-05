pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Downloads JDK 17 for core/ (jvmToolchain(17)) when the machine only has another JDK, e.g. Android Studio's JBR 21
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")          // usb-serial-for-android (serial key adapters)
    }
}
rootProject.name = "APRS-LoRa-Plus-Android"
include(":core", ":app")
