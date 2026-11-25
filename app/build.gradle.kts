plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "es.ea7jrs.aprslorap"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.ea7jrs.aprslorap"
        minSdk = 26
        targetSdk = 36
        versionCode = 50500
        versionName = "5.5.0"
        // Google Play build (-Palp.play=true): features whose terms do not allow a paid app are hidden (BuildConfig.PLAY)
        buildConfigField("boolean", "PLAY", if (project.findProperty("alp.play") == "true") "true" else "false")
    }

    // Google Play upload key (Play App Signing re-signs the final app): only from the environment (CI secrets), never in the repository
    val uploadStore = System.getenv("ALP_UPLOAD_STORE_FILE")
    signingConfigs {
        if (uploadStore != null) create("upload") {
            storeFile = file(uploadStore)
            storePassword = System.getenv("ALP_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("ALP_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("ALP_UPLOAD_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
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
        buildConfig = true
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    // Digital voice decoder (dsd-fme), built by native/dsd/build.sh into native/dsd/out/<abi>/libdsdfme.so (the CI does it).
    sourceSets["main"].jniLibs.srcDirs("../native/dsd/out", "../native/ft8/out", "../native/wspr/out", "../native/fst4w/out")
    // Extracted to the native library folder: Android only lets an app run a program from there.
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
        // Google Play build (-Palp.play=true): without the digital voice decoder (mbelib, possible DVSI patents); the app hides it
        if (project.findProperty("alp.play") == "true") jniLibs { excludes += "**/libdsdfme.so" }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.car.app:app:1.4.0")                            // Android Auto screens (IoT and weather categories)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 3.10.0, not 3.11.0: 3.11.0 brings androidx.annotation 1.10.0 and kotlin-stdlib 2.2, which Kotlin 2.0.21 cannot read
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")       // serial key adapters (FTDI, CP210x, CH34x, PL2303, CDC)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")               // TrustedQSL .p12 files (old RC2 / 3DES encryption) for LoTW
    implementation("org.osmdroid:osmdroid-android:6.1.20")                 // map: OpenStreetMap, IGN, OpenSeaMap, Esri tiles (Apache 2.0)
}
