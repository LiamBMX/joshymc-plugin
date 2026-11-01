// Runs the Bukkit-free Bedrock logo checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/bedrock-logo-check.init.gradle.kts bedrockLogoCheck   (issue #1333)
// Compiles scripts/BedrockLogoCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("bedrockLogoCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/BedrockLogoCheck.kt"))
            into(layout.buildDirectory.dir("bedrock-logo-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("bedrockLogoCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("bedrockLogoCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Bedrock logo checks in scripts/BedrockLogoCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("BedrockLogoCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
