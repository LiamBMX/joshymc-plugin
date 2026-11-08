// Runs the /fly persistence checks (issue #1470) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/flight-restore-check.init.gradle.kts flightRestoreCheck
// Compiles scripts/FlightRestoreCheck.kt against the plugin's main classes and runs it from the
// repo root. No server is started.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("flightRestoreCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/FlightRestoreCheck.kt"))
            into(layout.buildDirectory.dir("flight-restore-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("flightRestoreCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("flightRestoreCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the /fly persistence checks in scripts/FlightRestoreCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("FlightRestoreCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
