// Runs the Bukkit-free vanish visibility checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/vanish-check.init.gradle.kts vanishCheck   (issue #1467)
// Compiles scripts/VanishCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("vanishCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/VanishCheck.kt"))
            into(layout.buildDirectory.dir("vanish-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("vanishCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("vanishCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the vanish visibility checks in scripts/VanishCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("VanishCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
