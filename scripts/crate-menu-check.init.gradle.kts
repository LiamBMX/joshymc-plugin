// Runs the crate GUI redesign checks (issue #1514) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-menu-check.init.gradle.kts crateMenuCheck
// Compiles scripts/CrateMenuCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateMenuCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateMenuCheck.kt"))
            into(layout.buildDirectory.dir("crate-menu-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateMenuCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateMenuCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate GUI checks in scripts/CrateMenuCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateMenuCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
