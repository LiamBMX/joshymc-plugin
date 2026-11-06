// Runs the custom crate model checks (issue #1389) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-model-check.init.gradle.kts crateModelCheck
// Compiles scripts/CrateModelCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateModelCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateModelCheck.kt"))
            into(layout.buildDirectory.dir("crate-model-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateModelCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateModelCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the custom crate model checks in scripts/CrateModelCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateModelCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
