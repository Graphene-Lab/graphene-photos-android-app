import java.nio.file.Files
import java.nio.file.StandardOpenOption

// Native build shortcuts. Signing/configuration checks are enforced by :app.
mapOf(
    "buildLocalApk" to listOf(":app:assembleRelease"),
    "buildLocalAab" to listOf(":app:bundleRelease"),
    "buildLocalBoth" to listOf(":app:assembleRelease", ":app:bundleRelease")
).forEach { (taskName, buildTasks) ->
    tasks.register(taskName) {
        group = "release"
        description = "Build signed local release output(s); never publish."
        dependsOn(buildTasks)
    }
}

tasks.register("versionBump") {
    group = "release"
    description = "Bump Gradle versionName/versionCode and create separate release-note templates."
    doLast {
        val source = rootProject.file("app/build.gradle.kts")
        val text = source.readText()
        val namePattern = Regex("(?m)^(\\s*versionName\\s*=\\s*)\"(\\d+\\.\\d+\\.\\d+)\"")
        val codePattern = Regex("(?m)^(\\s*versionCode\\s*=\\s*)(\\d+)")
        val oldName = namePattern.findAll(text).single().groupValues[2]
        val oldCode = codePattern.findAll(text).single().groupValues[2].toLong()
        val oldParts = oldName.split('.').map { it.toLong() }
        val newName = providers.gradleProperty("versionName").orNull
            ?: "${oldParts[0]}.${oldParts[1]}.${oldParts[2] + 1}"
        require(Regex("\\d+\\.\\d+\\.\\d+").matches(newName)) { "versionName must be X.Y.Z." }
        val newParts = newName.split('.').map { it.toLong() }
        require(newParts.zip(oldParts).firstOrNull { it.first != it.second }?.let { it.first > it.second } == true) {
            "versionName must increase."
        }
        val requestedCode = providers.gradleProperty("versionCode").orNull
        require(requestedCode == null || requestedCode.toLongOrNull() != null) { "versionCode must be an integer." }
        val newCode = requestedCode?.toLong() ?: (oldCode + 1)
        require(newCode in (oldCode + 1)..2_100_000_000L) { "versionCode must increase and be at most 2100000000." }
        val github = rootProject.file("release-notes/$newName.md")
        val play = rootProject.file("fastlane/metadata/android/en-US/changelogs/$newCode.txt")
        fun template(file: File, contents: String) {
            file.parentFile.mkdirs()
            if (!file.exists()) {
                Files.writeString(file.toPath(), contents, StandardOpenOption.CREATE_NEW)
            }
        }
        template(github, "# Graphene Photos $newName\n\nRELEASE_NOTES_TODO: Write reviewed Markdown release notes.\n")
        template(play, "RELEASE_NOTES_TODO: Write a short plain-text summary (maximum 500 Unicode characters).\n")
        val updated = namePattern.replace(text) { "${it.groupValues[1]}\"$newName\"" }
        source.writeText(codePattern.replace(updated) { "${it.groupValues[1]}$newCode" })
        logger.lifecycle("Version $oldName ($oldCode) -> $newName ($newCode). Review $github and $play, then commit/push before manual dispatch.")
    }
}
