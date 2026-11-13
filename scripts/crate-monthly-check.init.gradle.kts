// Runs the monthly crate rotation checks (issue #1520) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-monthly-check.init.gradle.kts crateMonthlyCheck
// Compiles scripts/CrateMonthlyCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateMonthlyCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateMonthlyCheck.kt"))
            into(layout.buildDirectory.dir("crate-monthly-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateMonthlyCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        // The plugin shades the SQLite driver on a real server; here it's added to this check's
        // runtime only, and the override store is tested in a fresh temp directory.
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("crateMonthlyCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the monthly crate rotation checks in scripts/CrateMonthlyCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateMonthlyCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
