plugins {
    java
}

dependencies {
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

// Velocity 3.3 runs on Java 17 and newer.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
