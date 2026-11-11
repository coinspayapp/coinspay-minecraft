plugins {
    `java-library`
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    api("org.yaml:snakeyaml:2.4")
    api("io.nayuki:qrcodegen:1.8.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Tests run on the build JDK; only the main code must stay Java 8.
tasks.compileTestJava { options.release.set(21) }
