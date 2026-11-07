// Runs the crate Preview layout checks (issue #1414) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-preview-check.init.gradle.kts cratePreviewCheck
// Compiles scripts/CratePreviewCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("cratePreviewCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CratePreviewCheck.kt"))
            into(layout.buildDirectory.dir("crate-preview-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("cratePreviewCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("cratePreviewCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate Preview layout checks in scripts/CratePreviewCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CratePreviewCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
