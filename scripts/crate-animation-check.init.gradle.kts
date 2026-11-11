// Runs the crate opening animation checks (issue #1503) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-animation-check.init.gradle.kts crateAnimationCheck
// Compiles scripts/CrateAnimationCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateAnimationCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateAnimationCheck.kt"))
            into(layout.buildDirectory.dir("crate-animation-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateAnimationCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateAnimationCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate opening animation checks in scripts/CrateAnimationCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateAnimationCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
