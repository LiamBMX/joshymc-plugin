// Runs the Ranked Team Wars arena interference checks (issue #1507) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-arena-guard-check.init.gradle.kts rankedArenaGuardCheck
// Compiles scripts/RankedArenaGuardCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedArenaGuardCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedArenaGuardCheck.kt"))
            into(layout.buildDirectory.dir("ranked-arena-guard-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedArenaGuardCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankedArenaGuardCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars arena interference checks in scripts/RankedArenaGuardCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedArenaGuardCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
