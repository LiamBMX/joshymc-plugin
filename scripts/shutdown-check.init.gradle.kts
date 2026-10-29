// Runs the plugin shutdown checks (issue #1325) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/shutdown-check.init.gradle.kts shutdownCheck
// Compiles scripts/ShutdownCheck.kt against the plugin's main classes and runs it. Paper
// ships the SQLite driver on a real server; here it's added to this check's runtime only,
// and the databases it opens live in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("shutdownCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/ShutdownCheck.kt"))
            into(layout.buildDirectory.dir("shutdown-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("shutdownCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("shutdownCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the shutdown checks in scripts/ShutdownCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("ShutdownCheckKt")
        }
    }
}
