// Read-only Ranked Team Wars closed-beta readiness summary (issue #1531), from the repo root:
//   ./gradlew -I scripts/ranked-beta-readiness.init.gradle.kts rankedBetaReadiness
// Optional, for a TEST server's files (copied while that server is stopped):
//   -PreadinessConfig=/path/to/plugins/Joshymc/lifesteal.yml
//   -PreadinessDb=/path/to/copy-of/data.db          (opened read-only)
// Compiles scripts/RankedBetaReadiness.kt against the plugin's main classes and runs it. It never
// writes a file or a database row; see docs/lifesteal-ranked-qa.md, section 15.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedBetaReadinessSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedBetaReadiness.kt"))
            into(layout.buildDirectory.dir("ranked-beta-readiness-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedBetaReadiness") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("rankedBetaReadiness", JavaExec::class.java) {
            group = "verification"
            description = "Prints a read-only Ranked Team Wars beta readiness summary (scripts/RankedBetaReadiness.kt)."
            classpath = check.runtimeClasspath
            mainClass.set("RankedBetaReadinessKt")
            workingDir = rootProject.projectDir
            val config = project.findProperty("readinessConfig")?.toString()
            val db = project.findProperty("readinessDb")?.toString()
            args(listOfNotNull(config?.let { "--config=$it" }, db?.let { "--db=$it" }))
        }
    }
}
