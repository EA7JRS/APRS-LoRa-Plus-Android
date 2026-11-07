plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "es.ea7jrs.aprslorap"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.ea7jrs.aprslorap"
        minSdk = 26
        targetSdk = 35
        versionCode = 50305
        versionName = "5.3.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    // Digital voice decoder (dsd-fme), built by native/dsd/build.sh into native/dsd/out/<abi>/libdsdfme.so (the CI does it).
    sourceSets["main"].jniLibs.srcDirs("../native/dsd/out", "../native/ft8/out", "../native/wspr/out", "../native/fst4w/out")
    // Extracted to the native library folder: Android only lets an app run a program from there.
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 3.10.0, not 3.11.0: 3.11.0 brings androidx.annotation 1.10.0 and kotlin-stdlib 2.2, which Kotlin 2.0.21 cannot read
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")       // serial key adapters (FTDI, CP210x, CH34x, PL2303, CDC)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")               // TrustedQSL .p12 files (old RC2 / 3DES encryption) for LoTW
}
