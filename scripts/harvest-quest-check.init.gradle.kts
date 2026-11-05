// Runs the Bukkit-free HARVEST_CROP checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/harvest-quest-check.init.gradle.kts harvestQuestCheck   (issue #1374)
// Compiles scripts/HarvestQuestCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("harvestQuestCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/HarvestQuestCheck.kt"))
            into(layout.buildDirectory.dir("harvest-quest-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("harvestQuestCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("harvestQuestCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the HARVEST_CROP checks in scripts/HarvestQuestCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("HarvestQuestCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
