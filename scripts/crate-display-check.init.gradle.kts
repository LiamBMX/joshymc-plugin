// Runs the per-location crate display placement checks (issue #1517) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-display-check.init.gradle.kts crateDisplayCheck
// Compiles scripts/CrateDisplayCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateDisplayCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateDisplayCheck.kt"))
            into(layout.buildDirectory.dir("crate-display-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateDisplayCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateDisplayCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the per-location crate display placement checks in scripts/CrateDisplayCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateDisplayCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
