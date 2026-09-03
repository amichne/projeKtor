plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
    id("org.graalvm.buildtools.native") version "1.1.9"
}

val distributionName = providers.gradleProperty("distributionName").get()

application {
    mainClass.set("intelligence.cli.MainKt")
    applicationName = distributionName
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set(distributionName)
            mainClass.set("intelligence.cli.MainKt")
            buildArgs.addAll("-O2", "-march=compatibility", "--no-fallback")
            buildArgs.add("--initialize-at-build-time=kotlin.DeprecationLevel")
            buildArgs.add("-H:IncludeResources=schemas/.*\\.schema\\.json")
        }
    }
}

val distributionVersion = providers.gradleProperty("distributionVersion")
    .orElse("dev")

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/build-info/kotlin")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.property("distributionName", distributionName)
    inputs.property("distributionVersion", distributionVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val escapedName = distributionName
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val escapedVersion = distributionVersion.get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val output = generatedBuildInfoDir.get()
            .file("intelligence/cli/BuildInfo.kt")
            .asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            package intelligence.cli

            internal object BuildInfo {
                const val NAME: String = "$escapedName"
                const val VERSION: String = "$escapedVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(generatedBuildInfoDir)
    }
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt-core:5.1.0")
    implementation("io.github.optimumcode:json-schema-validator:0.5.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
}

tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("schemas")) {
        into("schemas")
        include("**/*.schema.json")
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.installDist)
    systemProperty(
        "distribution.installDir",
        layout.buildDirectory.dir("install/$distributionName").get().asFile.absolutePath,
    )
    systemProperty("distribution.name", distributionName)
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveBaseName.set(distributionName)
    archiveVersion.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<CreateStartScripts>("startScripts") {
    outputDir = layout.buildDirectory.dir("scripts/$distributionName").get().asFile
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
