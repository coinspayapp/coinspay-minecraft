plugins {
    java
}

dependencies {
    implementation(project(":core"))
    // The oldest supported API: code that compiles against it runs on 1.8.8 - 1.21. Newer
    // methods (Folia schedulers, map ids as int, player locale) go through reflection.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
}
