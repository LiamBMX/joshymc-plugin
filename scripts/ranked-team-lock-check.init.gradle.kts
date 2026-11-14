// Runs the Ranked Team Wars team lock checks (issue #1530) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-team-lock-check.init.gradle.kts rankedTeamLockCheck
// Compiles scripts/RankedTeamLockCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedTeamLockCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedTeamLockCheck.kt"))
            into(layout.buildDirectory.dir("ranked-team-lock-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedTeamLockCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankedTeamLockCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars team lock checks in scripts/RankedTeamLockCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedTeamLockCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
