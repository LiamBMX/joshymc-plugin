// Runs the Lifesteal launch-progression checks (issue #1352) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-progression-check.init.gradle.kts lifestealProgressionCheck
// Compiles scripts/LifestealProgressionCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealProgressionCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealProgressionCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-progression-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealProgressionCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealProgressionCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal launch-progression checks in scripts/LifestealProgressionCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealProgressionCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
