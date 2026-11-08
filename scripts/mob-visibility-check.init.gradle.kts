// Runs the Bukkit-free Mobs OFF visibility checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/mob-visibility-check.init.gradle.kts mobVisibilityCheck   (issue #1421)
// Compiles scripts/MobVisibilityCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("mobVisibilityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/MobVisibilityCheck.kt"))
            into(layout.buildDirectory.dir("mob-visibility-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("mobVisibilityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("mobVisibilityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Mobs OFF visibility checks in scripts/MobVisibilityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("MobVisibilityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
