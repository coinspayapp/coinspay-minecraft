// One jar for every platform: Spigot/Paper/Folia read plugin.yml, BungeeCord reads bungee.yml,
// Velocity reads velocity-plugin.json. Each platform loads only its own main class, so the
// Velocity classes (Java 17) never load on a Java 8 server.
plugins {
    java
    id("com.gradleup.shadow") version "8.3.8"
}

allprojects {
    group = "com.coinspay"
    version = rootProject.version
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(8)
        options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:deprecation"))
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "bungee.yml", "velocity-plugin.json")) { expand(props) }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":bukkit"))
    implementation(project(":bungee"))
    implementation(project(":velocity"))
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("CoinsPay")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    relocate("com.google.gson", "com.coinspay.mc.lib.gson")
    relocate("org.yaml.snakeyaml", "com.coinspay.mc.lib.snakeyaml")
    relocate("io.nayuki.qrcodegen", "com.coinspay.mc.lib.qrcodegen")
    exclude("META-INF/maven/**", "META-INF/versions/**", "META-INF/proguard/**", "com/google/errorprone/**", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from("LICENSE")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
