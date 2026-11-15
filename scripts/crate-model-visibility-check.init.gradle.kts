// Runs the crate model visibility checks (issue #1540) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-model-visibility-check.init.gradle.kts crateModelVisibilityCheck
// Compiles scripts/CrateModelVisibilityCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateModelVisibilityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateModelVisibilityCheck.kt"))
            into(layout.buildDirectory.dir("crate-model-visibility-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateModelVisibilityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateModelVisibilityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate model visibility checks in scripts/CrateModelVisibilityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateModelVisibilityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
