plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

// The checks of the core are a plain main() (no test framework): "gradle :core:selfTest" runs them and fails if any check fails.
val selfTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the unit checks of the pure Kotlin core"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("es.ea7jrs.aprslorap.core.SelfTestKt")
}

tasks.named("check") { dependsOn(selfTest) }
