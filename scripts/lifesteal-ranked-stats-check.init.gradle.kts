// Runs the Ranked leaderboard, division and statistics checks (issue #1367) without a test source
// set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-ranked-stats-check.init.gradle.kts lifestealRankedStatsCheck
// Compiles scripts/LifestealRankedStatsCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealRankedStatsCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealRankedStatsCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-ranked-stats-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealRankedStatsCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealRankedStatsCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked statistics checks in scripts/LifestealRankedStatsCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealRankedStatsCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
