// Runs the Bukkit-free back cosmetic checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/back-cosmetic-check.init.gradle.kts backCosmeticCheck   (issue #1516)
// Compiles scripts/BackCosmeticCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("backCosmeticCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/BackCosmeticCheck.kt"))
            into(layout.buildDirectory.dir("back-cosmetic-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("backCosmeticCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("backCosmeticCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the back cosmetic checks in scripts/BackCosmeticCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("BackCosmeticCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
