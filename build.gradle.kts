plugins {
    base
}

val distributionName = providers.gradleProperty("distributionName").get()

tasks.register<Sync>("installDevelopmentCli") {
    group = "application"
    description = "Update the repository-local projeKtor CLI from the Kotlin build output."

    dependsOn(":cli:installDist")

    from(project(":cli").layout.buildDirectory.dir("install/$distributionName"))
    into(layout.projectDirectory.dir(".local/$distributionName"))
}

tasks.register("verifyKotlinOnlyDevelopmentCli") {
    group = "verification"
    description = "Reject non-JVM source or native helpers in the installed CLI."

    dependsOn("installDevelopmentCli")

    doLast {
        val installRoot = layout.projectDirectory.dir(".local/$distributionName").asFile.toPath()
        val installedFiles =
            java.nio.file.Files.walk(installRoot).use { paths ->
                paths
                    .filter(java.nio.file.Files::isRegularFile)
                    .toList()
            }

        val unexpectedFiles =
            installedFiles
                .map(installRoot::relativize)
                .map { it.toString() }
                .map { it.replace('\\', '/') }
                .filterNot { relative ->
                    relative == "bin/$distributionName" ||
                        relative == "bin/$distributionName.bat" ||
                        relative.startsWith("lib/") && relative.endsWith(".jar")
                }
                .sorted()
        check(unexpectedFiles.isEmpty()) {
            "Installed CLI contains non-JVM files: ${unexpectedFiles.joinToString()}"
        }

        val forbiddenJarSuffixes =
            setOf(".py", ".pyc", ".pyo", ".rs", ".so", ".dylib", ".jnilib", ".dll", ".exe")
        val forbiddenJarEntries =
            installedFiles
                .filter { it.fileName.toString().endsWith(".jar") }
                .flatMap { jar ->
                    java.util.zip.ZipFile(jar.toFile()).use { zip ->
                        zip.entries().asSequence()
                            .filterNot { it.isDirectory }
                            .map { it.name }
                            .filter { entry -> forbiddenJarSuffixes.any(entry.lowercase()::endsWith) }
                            .map { entry -> "${jar.fileName}:$entry" }
                            .toList()
                    }
                }
                .sorted()
        check(forbiddenJarEntries.isEmpty()) {
            "Installed CLI JARs contain forbidden runtime entries: ${forbiddenJarEntries.joinToString()}"
        }
    }
}

tasks.named("check") {
    dependsOn("verifyKotlinOnlyDevelopmentCli")
}
