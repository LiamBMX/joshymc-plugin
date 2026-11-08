// Runs the per-location crate facing checks (issue #1472) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-rotation-check.init.gradle.kts crateRotationCheck
// Compiles scripts/CrateRotationCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateRotationCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateRotationCheck.kt"))
            into(layout.buildDirectory.dir("crate-rotation-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateRotationCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateRotationCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the per-location crate facing checks in scripts/CrateRotationCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateRotationCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
