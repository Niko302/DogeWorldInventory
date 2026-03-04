plugins {
    `maven-publish`
    id("hytale-mod") version "0.+"
}

group = "com.doge"
version = "1.1.1"
val javaVersion = 25

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
    maven("https://maven.hytale.com/release") {
        name = "HytaleRelease"
    }
    maven("https://maven.hytale.com/pre-release") {
        name = "HytalePreRelease"
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
    // Hytale Server API
    compileOnly("com.hypixel.hytale:Server:+")
    // SnakeYAML for YAML config support
    implementation("org.yaml:snakeyaml:2.2")
    // Gson for JSON storage
    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    withSourcesJar()
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "plugin_description" to findProperty("plugin_description"),
        "plugin_author" to findProperty("plugin_author"),
        "plugin_website" to findProperty("plugin_website"),
        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "server_version" to findProperty("server_version")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

hytale {
}

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = version.toString()
    }
    // Bundle dependencies into the jar
    from({
        configurations.runtimeClasspath.get()
            .filter {
                it.name.contains("snakeyaml") ||
                it.name.contains("gson")
            }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
