// Runs the Ranked Team Wars arena access and spectator integrity checks (issue #1524) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-arena-access-check.init.gradle.kts rankedArenaAccessCheck
// Compiles scripts/RankedArenaAccessCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedArenaAccessCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedArenaAccessCheck.kt"))
            into(layout.buildDirectory.dir("ranked-arena-access-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedArenaAccessCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankedArenaAccessCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars arena access checks in scripts/RankedArenaAccessCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedArenaAccessCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
