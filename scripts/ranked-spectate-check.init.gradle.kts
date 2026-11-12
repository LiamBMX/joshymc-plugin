// Runs the Ranked Team Wars spectate checks (issue #1511) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-spectate-check.init.gradle.kts rankedSpectateCheck
// Compiles scripts/RankedSpectateCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedSpectateCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedSpectateCheck.kt"))
            into(layout.buildDirectory.dir("ranked-spectate-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedSpectateCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankedSpectateCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars spectate checks in scripts/RankedSpectateCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedSpectateCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
