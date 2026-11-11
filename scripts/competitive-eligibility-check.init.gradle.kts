// Runs the competitive Lifesteal reward eligibility checks (issue #1487) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/competitive-eligibility-check.init.gradle.kts competitiveEligibilityCheck
// Compiles scripts/CompetitiveEligibilityCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("competitiveEligibilityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CompetitiveEligibilityCheck.kt"))
            into(layout.buildDirectory.dir("competitive-eligibility-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("competitiveEligibilityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        // The server provides SQLite at runtime; the check needs its own copy of the driver.
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("competitiveEligibilityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the competitive eligibility checks in scripts/CompetitiveEligibilityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CompetitiveEligibilityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
