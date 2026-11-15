// Runs the event-world inventory crash-safety checks (issue #1550) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/event-inventory-check.init.gradle.kts eventInventoryCheck
// Compiles scripts/EventInventoryCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("eventInventoryCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/EventInventoryCheck.kt"))
            into(layout.buildDirectory.dir("event-inventory-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("eventInventoryCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("eventInventoryCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the event inventory crash-safety checks in scripts/EventInventoryCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("EventInventoryCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
