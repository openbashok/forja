plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "com.openbash"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("net.portswigger.burp.extensions:montoya-api:2025.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.17.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
}

// Generate version.properties with git commit hash at build time
tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)
    doLast {
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()

        val gitHashFull = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()

        val buildTime = System.currentTimeMillis().toString()

        val propsDir = outputDir.get().asFile.resolve("version")
        propsDir.mkdirs()
        propsDir.resolve("version.properties").writeText(
            "version=${project.version}\n" +
            "commit=$gitHash\n" +
            "commit.full=$gitHashFull\n" +
            "build.time=$buildTime\n"
        )
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

tasks.processResources {
    dependsOn("generateVersionProperties")
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
