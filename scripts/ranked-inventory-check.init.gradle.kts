// Runs the Ranked Team Wars inventory-safety checks (issue #1506) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-inventory-check.init.gradle.kts rankedInventoryCheck
// Compiles scripts/RankedInventoryCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedInventoryCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedInventoryCheck.kt"))
            into(layout.buildDirectory.dir("ranked-inventory-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedInventoryCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("rankedInventoryCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars inventory-safety checks in scripts/RankedInventoryCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedInventoryCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
